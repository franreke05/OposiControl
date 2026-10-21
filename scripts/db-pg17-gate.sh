#!/usr/bin/env bash
# DB-CI-PG17: gate REPRODUCIBLE de migraciones en PostgreSQL 17 (imagen de Supabase fijada por digest), sin secretos ni MAIN.
#   1. Manifiesto: supabase/baseline/PENDING_MIGRATIONS.txt == migraciones de supabase/migrations cuya version NO esta en
#      supabase/baseline/MAIN_MIGRATION_HISTORY.txt (historial REAL de MAIN, leido read-only), salvo las divergencias de
#      historial conocidas (HISTORY_DIVERGENT, abajo; README.md). Cada pendiente tiene bloque ROLLBACK y VERIFY.
#      Los manifiestos por slice (SLICE*_MIGRATIONS.txt) los comprueba scripts/verify-migrations-manifest.sh.
#      DEBT-156b (develop a8928f8, acordado con OposiBot): las 202610*_security_* que NO son V1 tampoco son pendientes aunque
#      no esten en el historial capturado (se aplican a MAIN a mano con GO y se prueban en backend/dbtest contra stubs con la
#      forma de MAIN). Paso 1b: cada una lleva VERIFY, ROLLBACK y una clase de dbtest que la nombre (salvo las listas LEGACY_*).
#      Una migracion V1 (20261022* o V1_MIGRATIONS.txt) con _security_ (p. ej. 20261022100100_security_exam_publication_time)
#      NUNCA queda fuera: es pendiente y debe figurar en PENDING_MIGRATIONS.txt (si falta, FALLA con su propio mensaje).
#   2. Arranca un contenedor nuevo, carga supabase/baseline/main_shape_subset.sql (captura de catalogo de MAIN de la misma
#      fecha que el historial, scripts/db-main-catalog-capture.sql) con ON_ERROR_STOP=1 en UNA transaccion y toma la huella F0.
#   3. Aplica cada pendiente EN ORDEN con ON_ERROR_STOP=1 y ejecuta su VERIFY justo despues: cada fila debe ser
#      `<check>|0|...`; un valor distinto de 0, un resultado vacio o un bloque ausente FALLA. Huella F1.
#   4. supabase/baseline/assertions.sql (transaccion con ROLLBACK): comportamiento + matriz de grants/RLS (ASSERT = fallo).
#   5. ROLLBACK de cada pendiente en orden inverso -> la huella debe volver a F0; re-aplicar + VERIFY -> debe volver a F1.
#   6. Carreras REALES de pagos de tienda con dos/tres conexiones psql (N3-3, N3-1): fallan si se quita o se mueve el cerrojo por clave.
# Cobertura y limites (frente al gate local completo): supabase/baseline/README.md.
# MIG_ROOT apunta el gate a otro arbol (lo usa db-pg17-gate.test.sh); PG17_IMAGE permite fijar otra imagen.
set -uo pipefail
cd "${MIG_ROOT:-$(dirname "$0")/..}" || exit 2
IMAGE="${PG17_IMAGE:-public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9}" # 17.6.1.171
# 20260928100100 (ya publicada) es anterior a la convencion VERIFY; su tabla la cubre assertions.sql (RLS, sin grants de cliente).
NO_VERIFY="20260928100100_idempotency_keys"
# Ficheros del repo cuya version no figura en el historial de MAIN y que NO son pendientes (README.md): el contenido de
# news_canvas_schema esta aplicado en MAIN con la version 20260923090948, y 20260730120000 nunca se registro en MAIN (su
# contenido lo cubre 20260803092306 del historial).
HISTORY_DIVERGENT="20260730120000 20260923103000"
fail() { echo "FAIL: $*"; exit 1; }

boot=supabase/baseline/main_shape_subset.sql; pend=supabase/baseline/PENDING_MIGRATIONS.txt; hist=supabase/baseline/MAIN_MIGRATION_HISTORY.txt
for f in "$boot" "$pend" "$hist" supabase/baseline/assertions.sql; do [ -s "$f" ] || fail "$f vacio o ausente"; done
pending="$(tr -d '\r' < "$pend" | grep -Ev '^[[:space:]]*(#|$)')"
[ -n "$pending" ] || fail "$pend sin pendientes"
[ "$pending" = "$(echo "$pending" | sort -u)" ] || fail "$pend no esta ordenado o tiene duplicados"
applied="$(tr -d '\r' < "$hist" | grep -Ev '^[[:space:]]*(#|$)' | cut -d' ' -f1)"
[ "$(echo "$applied" | wc -l | tr -d ' ')" -ge 70 ] || fail "$hist no parece el historial completo de MAIN"
v1list="$( [ -f supabase/V1_MIGRATIONS.txt ] && tr -d '\r' < supabase/V1_MIGRATIONS.txt | grep -Ev '^[[:space:]]*(#|$)' )"
is_v1() { case "$1" in 20261022*) return 0 ;; esac; printf '%s\n' "$v1list" | grep -qx "$1"; }
security="$( (cd supabase/migrations && ls | grep -E '^202610.*_security_.*\.sql$' | sed 's/\.sql$//' | sort) )"
for v in $security; do
  is_v1 "$v" || continue
  # once MAIN_MIGRATION_HISTORY.txt is recaptured after the V1 deploy, an applied V1 _security_ migration is no longer pending
  echo "$applied" | grep -qx "${v%%_*}" && continue
  printf '%s\n' "$pending" | grep -qx "$v" || fail "$v: una migracion V1 (20261022* o V1_MIGRATIONS.txt) con _security_ es pendiente y no figura en $pend (quedaria fuera del gate)"
done
actual="$( (cd supabase/migrations && ls | grep -E '^[0-9]{14}_.*\.sql$' | sed 's/\.sql$//' | sort) | while read -r m; do
  v="${m%%_*}"; echo "$applied" | grep -qx "$v" && continue; case " $HISTORY_DIVERGENT " in *" $v "*) continue ;; esac
  case "$m" in 202610*_security_*) is_v1 "$m" || continue ;; esac; echo "$m"; done)"
[ "$pending" = "$actual" ] || { echo "FAIL: $pend y (repo - historial de MAIN) difieren (< pendientes, > real):"; diff <(echo "$pending") <(echo "$actual"); exit 1; }

block() { # bloque comentado `-- NAME-BEGIN/END` (misma regla que Slice0Db.bloqueComentado): toda linea debe empezar por --
  awk -v n="$2" '$0 ~ "^[[:space:]]*-- "n"-END[[:space:]]*$" {e=1; exit} s {print} $0 ~ "^[[:space:]]*-- "n"-BEGIN[[:space:]]*$" {s=1} END {exit !(s && e)}' "$1" \
    | { bad=0; while IFS= read -r l; do l="${l%$'\r'}"; case "$l" in --*) l="${l#--}"; echo "${l# }" ;; *) bad=1 ;; esac; done; exit $bad; }
}
for v in $pending; do
  f="supabase/migrations/$v.sql"
  block "$f" ROLLBACK >/dev/null || fail "$v: bloque ROLLBACK ausente o con lineas sin comentar"
  if [ "$v" != "$NO_VERIFY" ]; then block "$f" VERIFY >/dev/null || fail "$v: bloque VERIFY ausente o con lineas sin comentar"; fi
done

# 1b. DEBT-156b (acordado con OposiBot, 2026-10-20). Las listas LEGACY son cerradas: una *_security_* nueva no puede entrar en ellas
#     sin cambiar este fichero, y se justifican en docs/registry/TECH_DEBT.md (DEBT-156b).
#     - Sin ROLLBACK: anteriores a la convencion ROLLBACK/VERIFY en las *_security_* (forward-only).
#     - Sin clase de dbtest que las nombre: anteriores a la regla; su VERIFY es la unica comprobacion automatica.
LEGACY_SECURITY_NO_ROLLBACK="20261012100000_security_client_write_hardening 20261013100000_security_admin_access_requests 20261014100000_security_user_notices 20261015100000_security_audit_log_index 20261020100000_security_access_request_email_verified 20261021100000_security_audit_log_append_only"
LEGACY_SECURITY_NO_DBTEST="20261012100000_security_client_write_hardening 20261014100000_security_user_notices 20261015100000_security_audit_log_index"
in_list() { case " $2 " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
# (Las V1 con _security_ son pendientes: las cubre el bucle de pendientes de arriba y no se repiten aqui.)
for v in $security; do
  is_v1 "$v" && continue
  f="supabase/migrations/$v.sql"
  block "$f" VERIFY >/dev/null || fail "$v: bloque VERIFY ausente o con lineas sin comentar (migracion _security_)"
  in_list "$v" "$LEGACY_SECURITY_NO_ROLLBACK" || block "$f" ROLLBACK >/dev/null || fail "$v: bloque ROLLBACK ausente o con lineas sin comentar (migracion _security_)"
  in_list "$v" "$LEGACY_SECURITY_NO_DBTEST" || grep -rqF "$v" backend/dbtest/src/test 2>/dev/null || fail "$v: ninguna prueba de backend/dbtest la nombra (migracion _security_)"
done
echo "ok:   manifiesto ($(echo "$pending" | wc -l | tr -d ' ') pendientes) y bloques VERIFY/ROLLBACK"

ctr="db-pg17-gate-$$-$RANDOM"
tmp="$(mktemp -d)"; err="$tmp/err"
trap 'docker rm -f "$ctr" >/dev/null 2>&1; rm -rf "$tmp"' EXIT
docker run -d --name "$ctr" -e POSTGRES_PASSWORD=postgres "$IMAGE" >/dev/null || fail "no arranca $IMAGE"
psqlc() { docker exec -i "$ctr" psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
ready=0
for _ in $(seq 1 90); do # TCP solo responde tras los scripts de init de la imagen (el servidor temporal no escucha en TCP)
  [ "$(psqlc -Atc "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] \
    && [ "$(psqlc -Atc "select to_regclass('auth.users') is not null" 2>/dev/null)" = t ] && { ready=1; break; }
  sleep 2
done
[ "$ready" = 1 ] || { docker logs "$ctr" 2>&1 | tail -20; fail "PostgreSQL 17 no quedo listo"; }
echo "ok:   $(psqlc -Atc 'select version()' | cut -d' ' -f1-2)"

fingerprint() { # $1 = fichero: catalogo de public/private por NOMBRE (no cuerpos: un rollback verbatim reformatea el texto)
  psqlc -At > "$tmp/fp.raw" 2>"$err" <<'SQL' || { cat "$err"; fail "la huella de catalogo no se puede calcular"; }
select 'col:'||table_schema||'.'||table_name||'.'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema in ('public','private');
select 'con:'||conrelid::regclass::text||'.'||conname||':'||pg_get_constraintdef(oid) from pg_constraint where connamespace in ('public'::regnamespace,'private'::regnamespace);
select 'idx:'||schemaname||'.'||tablename||'.'||indexname||':'||indexdef from pg_indexes where schemaname in ('public','private');
select 'trg:'||tgrelid::regclass::text||'.'||tgname||':'||tgfoid::regproc::text from pg_trigger where not tgisinternal and (tgrelid::regclass::text not like 'auth.%' or tgrelid = 'auth.users'::regclass);
select 'fn:'||p.oid::regprocedure::text||':'||p.prosecdef::text||':'||coalesce(p.proconfig::text,'')||':'||coalesce(p.proacl::text,'') from pg_proc p where p.pronamespace in ('public'::regnamespace,'private'::regnamespace);
select 'pol:'||schemaname||'.'||tablename||'.'||policyname||':'||cmd||':'||roles::text||':'||coalesce(qual,'')||':'||coalesce(with_check,'') from pg_policies where schemaname in ('public','private');
select 'rel:'||c.oid::regclass::text||':'||c.relkind::text||':'||c.relrowsecurity::text||':'||coalesce(c.relacl::text,'') from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace) and c.relkind in ('r','p','v','S');
SQL
  sort "$tmp/fp.raw" > "$1"; [ -s "$1" ] || fail "huella de catalogo vacia"
}
same() { cmp -s "$1" "$2" || { diff "$1" "$2" | head -20; fail "$3"; }; }
verify() { # $1 = version: ejecuta su VERIFY y exige >=1 fila y failures (columna 2) = 0 en todas
  local out
  [ "$1" = "$NO_VERIFY" ] && return 0
  out="$(block "supabase/migrations/$1.sql" VERIFY | psqlc -At -F '|' 2>&1)" || { echo "$out"; fail "$1: el VERIFY no se ejecuta"; }
  [ -n "$out" ] || fail "$1: VERIFY sin filas"
  echo "$out" | awk -F'|' '$2 != "0" {bad=1; print "  VERIFY " $1 " = " $2} END {exit bad}' || fail "$1: VERIFY con contadores distintos de 0"
}
apply_all() {
  for v in $pending; do
    psqlc < "supabase/migrations/$v.sql" >/dev/null 2>"$err" || { cat "$err"; fail "$v no aplica (ON_ERROR_STOP)"; }
    verify "$v"
  done
}

psqlc -1 < "$boot" >/dev/null 2>"$err" || { cat "$err"; fail "$boot no carga con ON_ERROR_STOP=1"; }
# V1: objetos de MAIN que el bootstrap generado no arrastra y sobre los que construyen las migraciones V1 (compras_usuario, transacciones_cartera).
extra=supabase/baseline/main_shape_subset_extra.sql
[ -s "$extra" ] && { psqlc < "$extra" >/dev/null 2>"$err" || { cat "$err"; fail "$extra no carga con ON_ERROR_STOP=1"; }; }
# V1 Access v2: beneficios_plan y suscripciones (objetos de MAIN fuera del subconjunto; ver el fichero).
extra_plans=supabase/baseline/main_shape_subset_extra_plans.sql
[ -s "$extra_plans" ] && { psqlc < "$extra_plans" >/dev/null 2>"$err" || { cat "$err"; fail "$extra_plans no carga con ON_ERROR_STOP=1"; }; }
# V1 wave 2: retos, registros, rachas, ranking_liga y las funciones recalcular_* de MAIN (con sus grants de cliente de HOY, que 20261022101200 retira).
extra_auth=supabase/baseline/main_shape_auth_users.sql
[ -s "$extra_auth" ] && { docker exec -i "$ctr" psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 < "$extra_auth" >/dev/null 2>"$err" || { cat "$err"; fail "$extra_auth no carga con ON_ERROR_STOP=1"; }; }

extra_eng=supabase/baseline/main_shape_subset_extra_engagement.sql
[ -s "$extra_eng" ] && { psqlc < "$extra_eng" >/dev/null 2>"$err" || { cat "$err"; fail "$extra_eng no carga con ON_ERROR_STOP=1"; }; }
# V1 NIGHT-A: la vertical de importacion en bloque de MAIN (staging + RPC de importacion), fuera del subconjunto generado.
extra_imp=supabase/baseline/main_shape_subset_extra_import.sql
[ -s "$extra_imp" ] && { psqlc < "$extra_imp" >/dev/null 2>"$err" || { cat "$err"; fail "$extra_imp no carga con ON_ERROR_STOP=1"; }; }
fingerprint "$tmp/f0"
apply_all
fingerprint "$tmp/f1"
echo "ok:   bootstrap + $(echo "$pending" | wc -l | tr -d ' ') migraciones aplicadas en orden; VERIFY = 0"
psqlc < supabase/baseline/assertions.sql >/dev/null 2>"$err" || { grep -E 'ERROR|ASSERT' "$err"; fail "assertions.sql"; }
echo "ok:   assertions.sql (comportamiento + grants/RLS)"
for v in $(echo "$pending" | sort -r); do
  block "supabase/migrations/$v.sql" ROLLBACK | psqlc >/dev/null 2>"$err" || { cat "$err"; fail "$v: el ROLLBACK falla"; }
done
fingerprint "$tmp/f2"; same "$tmp/f0" "$tmp/f2" "tras los ROLLBACK el catalogo no vuelve al bootstrap (< bootstrap, > tras rollback)"
apply_all
fingerprint "$tmp/f3"; same "$tmp/f1" "$tmp/f3" "re-aplicar tras el rollback no reproduce el mismo catalogo (< primera, > re-aplicada)"
echo "ok:   rollback inverso == bootstrap; re-aplicacion == primera aplicacion (VERIFY = 0)"
# Secuenciacion DETERMINISTA de las carreras (pasos 6 y 7), sin sleeps de reloj: cada conexion de una carrera lleva application_name
# gate-race-<id>-<rol>. La que retiene hace su trabajo y espera DENTRO de su transaccion la senal gate-race-<id>-go (una sesion con ese
# application_name); la segunda solo arranca cuando la primera esta retenida (su DO /* gate-race-hold */ activo en pg_stat_activity), y la
# senal solo se da cuando la segunda esta BLOQUEADA en un cerrojo (pg_stat_activity.wait_event_type = 'Lock'). Toda espera tiene tope
# (RACE_TIMEOUT s): si vence, o si la segunda termina sin bloquearse, la carrera FALLA con su id; nada se cuelga.
RACE_TIMEOUT="${RACE_TIMEOUT:-90}"
rq() { psqlc -At -c "$1" 2>&1; }                                         # una lectura de comprobacion
psqla() { docker exec -i -e PGAPPNAME="$1" "$ctr" psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 -At "${@:2}"; }
bg() { psqla "gate-race-$rid-$1" >"$tmp/race.$1.out" 2>"$tmp/race.$1.err" <<<"$2" & eval "race_job_$1=\$!"; }  # conexion en segundo plano
held() { # $1 = trabajo; despues espera la senal gate-race-<id>-go con la transaccion abierta
  echo "begin; $1 do \$\$ declare t0 timestamptz := clock_timestamp(); begin /* gate-race-hold */ loop perform pg_stat_clear_snapshot();" \
    "exit when exists (select 1 from pg_stat_activity where application_name = 'gate-race-$rid-go'); if clock_timestamp() - t0 > interval '$RACE_TIMEOUT seconds'" \
    "then raise exception 'gate-race-hold $rid: sin senal de liberacion'; end if; perform pg_sleep(0.01); end loop; end \$\$; commit;"
}
race_until() { # $1 = rol que debe seguir vivo, $2 = condicion SQL sobre pg_stat_activity -> 0 cumplida, 1 el rol termino antes, 2 tope o error
  local job="race_job_$1" end=$((SECONDS + RACE_TIMEOUT)) out
  while [ "$SECONDS" -lt "$end" ]; do
    out="$(rq "do \$\$ declare t0 timestamptz := clock_timestamp(); begin loop perform pg_stat_clear_snapshot(); exit when $2; if clock_timestamp() - t0 > interval '2 seconds' then raise exception 'gate-race-pending'; end if; perform pg_sleep(0.01); end loop; end \$\$")"
    [ -z "$out" ] && return 0
    case "$out" in *gate-race-pending*) ;; *) echo "  race $rid: espera: $(echo "$out" | head -1)"; return 2 ;; esac
    kill -0 "${!job}" 2>/dev/null || return 1
  done
  return 2
}
race_held() { # la conexion $1 hizo su trabajo y retiene la transaccion
  race_until "$1" "exists (select 1 from pg_stat_activity where application_name = 'gate-race-$rid-$1' and state = 'active' and query like '%gate-race-hold%')" \
    || { echo "  race $rid: la conexion $1 no llego a retener su transaccion"; bad=1; return 1; }
}
race_blocked() { # la conexion $1 espera un cerrojo; con $2 = settle basta con que termine (no se exige bloqueo)
  local rc; race_until "$1" "exists (select 1 from pg_stat_activity where application_name = 'gate-race-$rid-$1' and wait_event_type = 'Lock')"; rc=$?
  [ "$rc" = 0 ] || { [ "$rc" = 1 ] && [ "${2:-}" = settle ]; } \
    || { echo "  race $rid: la conexion $1 no se bloqueo en un cerrojo (termino antes o tope de ${RACE_TIMEOUT}s)"; bad=1; return 1; }
}
race_release() { # senal gate-race-<id>-go mientras la conexion $1 siga retenida
  psqla "gate-race-$rid-go" -c "do \$\$ declare t0 timestamptz := clock_timestamp(); begin loop perform pg_stat_clear_snapshot(); exit when not exists (select 1 from pg_stat_activity where application_name = 'gate-race-$rid-$1' and state = 'active' and query like '%gate-race-hold%'); if clock_timestamp() - t0 > interval '$RACE_TIMEOUT seconds' then raise exception 'gate-race-go: tope'; end if; perform pg_sleep(0.01); end loop; end \$\$" >/dev/null 2>&1 \
    || { echo "  race $rid: la senal de liberacion vencio"; bad=1; }
}
race2() { # $1 = id, $2 = trabajo de a (retenido), $3 = trabajo de b: b arranca con a retenido y a se libera con b bloqueado
  rid="$1"; rm -f "$tmp"/race.*
  bg a "$(held "$2")"; race_held a && { bg b "$3"; race_blocked b; }; race_release a; wait; noerr a b
}
race3() { # $1 = id, $2 = cerrojo de c (retenido), $3 = trabajo de a (debe bloquearse tras c), $4 = trabajo de b (con a bloqueado: se bloquea
          # o termina, segun el codigo); c se libera cuando b ya se resolvio
  rid="$1"; rm -f "$tmp"/race.*
  bg c "$(held "$2")"; race_held c && { bg a "$3"; race_blocked a && { bg b "$4"; race_blocked b settle; }; }; race_release c; wait; noerr a b c
}
# 6. N3-3 / N3-1 (re-auditoria Control ronda 3) + tarea 4: carreras REALES con dos/tres conexiones psql sobre la base ya migrada. La primera
#    transaccion hace su trabajo y se queda ABIERTA; la segunda empieza entonces y se bloquea. Sin el cerrojo por clave de tienda la segunda no
#    ve el pedido (o el huerfano) sin confirmar y los dos hechos nunca se ligan: el paso FALLA. Lo prueba db-pg17-gate.test.sh moviendo el cerrojo.
store_race() {
  local u k i bad=0 out rid
  expect() { [ "$2" = "$3" ] || { echo "  race $1: '$2' (esperado '$3')"; bad=1; }; }
  noerr() { local f; for f in "$@"; do [ ! -s "$tmp/race.$f.err" ] || { echo "  race $f: $(head -1 "$tmp/race.$f.err")"; bad=1; }; done; }
  u() { printf 'a0000000-0000-0000-0000-%012d' $((950 + $1)); }
  credit() { echo "select outcome from public.app_store_credit_purchase('$(u "$1")', public.app_store_account_token('$(u "$1")'), 'app_store', 'produccion', 'race-pack-100', 'com.x.ot100', '$2', '{}'::jsonb);"; }
  inbox() { rq "select nuevo from public.app_store_inbox_put('app_store', '$1', '$2', '$3', 'produccion', encode(sha256(convert_to('$1', 'utf8')), 'hex'), '{}'::jsonb)" >/dev/null; }
  reversal() { echo "select public.app_store_refund_reversed('app_store', '$1', '$2');"; }
  refund() { echo "select outcome from public.app_store_refund_purchase('app_store', '$1', '$2');"; }
  sub() { echo "select outcome from public.app_store_upsert_subscription('$(u "$1")', public.app_store_account_token('$(u "$1")'), 'app_store', 'produccion', 'race-premium', 'com.x.premium', '$2', 'activa', '2026-10-01T00:00:00Z', '2026-11-01T00:00:00Z', null, null, 100, null);"; }
  st() { rq "select estado || '|' || (select count(*) from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets') || '|' || (select count(*) from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'devolucion') from public.compras_usuario c where identificador_transaccion = '$1'"; }
  psqlc >/dev/null 2>"$err" <<'SQL' || { cat "$err"; fail "carreras de tienda: no se siembran los datos"; }
insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-' || lpad((950 + n)::text, 12, '0'))::uuid, 'race' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 12) n;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
  select ('a0000000-0000-0000-0000-' || lpad((950 + n)::text, 12, '0'))::uuid, 'race' || n, 'race_user_' || n, true, 'activa' from generate_series(1, 12) n
  on conflict (usuario_id) do update set username = excluded.username;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible)
  values ('b9950000-0000-0000-0000-000000000001', 'race-pack-100', 'pack_tickets', 'Race 100', 99, 'EUR', 100, true, true),
         ('b9950000-0000-0000-0000-000000000002', 'race-premium', 'suscripcion', 'Race Premium', 999, 'EUR', null, true, true);
SQL
  for i in 1 2; do
    # R1 credito (abierto) || REFUND_REVERSED de la misma clave -> la reversion se registra en el pedido, nunca huerfana
    k="r1-$i"; inbox "r1-rev-$i" REEMBOLSO_REVERTIDO "$k"
    race2 "R1.$i" "$(credit 1 "$k")" "$(reversal "r1-rev-$i" "$k")"
    expect "R1.$i" "$(cat "$tmp/race.b.out")" REVERSAL_RECORDED
    # R2 credito (abierto) || reembolso de la misma clave -> el reembolso se aplica al pedido
    k="r2-$i"; inbox "r2-ref-$i" REEMBOLSO "$k"
    race2 "R2.$i" "$(credit 2 "$k")" "$(refund "r2-ref-$i" "$k")"
    expect "R2.$i" "$(cat "$tmp/race.b.out")|$(st "$k")" "REFUNDED|reembolsada|1|1"
    # R3 reversion huerfana (abierta) || credito -> el credito la ve y la liga
    k="r3-$i"; inbox "r3-rev-$i" REEMBOLSO_REVERTIDO "$k"
    race2 "R3.$i" "$(reversal "r3-rev-$i" "$k")" "$(credit 3 "$k")"
    expect "R3.$i" "$(rq "select resultado from public.tienda_bandeja where evento_id = 'r3-rev-$i'")" REVERSAL_HELD
    # R4 reembolso huerfano (abierto) || credito -> pedido a revision, nada acreditado
    k="r4-$i"; inbox "r4-ref-$i" REEMBOLSO "$k"
    race2 "R4.$i" "$(refund "r4-ref-$i" "$k")" "$(credit 4 "$k")"
    expect "R4.$i" "$(st "$k")" "en_revision|0|0"
    # S1 la MISMA compra verificada a la vez por dos conexiones -> un credito y un REPLAY
    k="s1-$i"
    race2 "S1.$i" "$(credit 5 "$k")" "$(credit 5 "$k")"
    expect "S1.$i" "$(sort "$tmp/race.a.out" "$tmp/race.b.out" | tr '\n' ' ')|$(st "$k")" "CREDITED REPLAY |pagada|1|0"
    # S2 el MISMO aviso de reembolso entregado dos veces a la vez -> un debito y un REPLAY
    k="s2-$i"; rq "$(credit 6 "$k")" >/dev/null; inbox "s2-ref-$i" REEMBOLSO "$k"
    race2 "S2.$i" "$(refund "s2-ref-$i" "$k")" "$(refund "s2-ref-$i" "$k")"
    expect "S2.$i" "$(sort "$tmp/race.a.out" "$tmp/race.b.out" | tr '\n' ' ')|$(st "$k")" "REFUNDED REPLAY |reembolsada|1|1"
    # S3 la primera entrega de una suscripcion dos veces a la vez -> una fila, sin violar el indice unico
    k="s3-$i"
    race2 "S3.$i" "$(sub 7 "$k")" "$(sub 7 "$k")"
    expect "S3.$i" "$(sort "$tmp/race.a.out" "$tmp/race.b.out" | tr '\n' ' ')|$(rq "select count(*) from public.suscripciones where identificador_externo = '$k'")" "CREATED REPLAY |1"
    # D1 (N3-1) reconcile || reintento de credito con contencion en la cartera -> sin 40P01: el reconcile salta la clave ocupada
    k="d1-$i"; rq "$(credit 8 "$k")" >/dev/null; inbox "d1-rev-$i" REEMBOLSO_REVERTIDO "$k"
    rq "update public.tienda_bandeja set resultado = 'REVERSAL_ORPHAN_HELD', estado = 'procesado' where evento_id = 'd1-rev-$i'" >/dev/null
    race3 "D1.$i" "select null from pg_advisory_xact_lock(hashtextextended('wallet:$(u 8)', 0));" "$(credit 8 "$k")" "select public.app_store_reconcile_orphans(50, false);"
    expect "D1.$i" "$(rq "select resultado from public.tienda_bandeja where evento_id = 'd1-rev-$i'")" REVERSAL_HELD
    # D2 (N4-6) lo mismo con el paso de una fila por transaccion que usa el trabajo: sin 40P01, la clave ocupada se salta (BUSY) y el credito liga
    k="d2-$i"; rq "$(credit 9 "$k")" >/dev/null; inbox "d2-rev-$i" REEMBOLSO_REVERTIDO "$k"
    rq "update public.tienda_bandeja set resultado = 'REVERSAL_ORPHAN_HELD', estado = 'procesado' where evento_id = 'd2-rev-$i'" >/dev/null
    race3 "D2.$i" "select null from pg_advisory_xact_lock(hashtextextended('wallet:$(u 9)', 0));" "$(credit 9 "$k")" "select resultado from public.app_store_reconcile_orphan_step(false, null, null, null) where evento_id = 'd2-rev-$i';"
    expect "D2.$i" "$(rq "select resultado from public.tienda_bandeja where evento_id = 'd2-rev-$i'")" REVERSAL_HELD
  done
  # N4-6: el paso del trabajo retiene UNA clave y UNA cartera por transaccion (el lote de 20261022103000 retenia 2 por fila hasta el final) y sigue
  # el cursor: tres huerfanos de tres usuarios se ligan en tres transacciones; un cuarto paso no devuelve nada.
  for i in 10 11 12; do
    k="n46-$i"; rq "$(credit "$i" "$k")" >/dev/null; inbox "n46-rev-$i" REEMBOLSO_REVERTIDO "$k"
    rq "update public.tienda_bandeja set resultado = 'REVERSAL_ORPHAN_HELD', estado = 'procesado', recibido_en = now() - interval '1 hour' + interval '1 second' * $i where evento_id = 'n46-rev-$i'" >/dev/null
  done
  rid=N4-6; rm -f "$tmp"/race.*; bg a "$(held "select resultado from public.app_store_reconcile_orphan_step(false, null, null, null);")"; race_held a
  expect "N4-6 cerrojos de un paso" "$(rq "select count(*) from pg_locks where locktype = 'advisory' and granted")" 2
  race_release a; wait; noerr a
  out="$(rq "select evento_id || '|' || resultado from public.app_store_reconcile_orphan_step(false, (select recibido_en from public.tienda_bandeja where evento_id = 'n46-rev-10'), 'app_store', 'n46-rev-10')")"
  expect "N4-6 cursor" "$out" "n46-rev-11|REVERSAL_RECORDED"
  out="$(rq "select evento_id || '|' || resultado from public.app_store_reconcile_orphan_step(false, (select recibido_en from public.tienda_bandeja where evento_id = 'n46-rev-11'), 'app_store', 'n46-rev-11')")"
  expect "N4-6 cursor 2" "$out" "n46-rev-12|REVERSAL_RECORDED"
  expect "N4-6 fin" "$(rq "select count(*) from public.app_store_reconcile_orphan_step(false, (select recibido_en from public.tienda_bandeja where evento_id = 'n46-rev-12'), 'app_store', 'n46-rev-12')")" 0
  expect "N4-6 ligados" "$(rq "select string_agg(resultado, ',' order by evento_id) from public.tienda_bandeja where evento_id like 'n46-rev-%'")" "REVERSAL_RECORDED,REVERSAL_RECORDED,REVERSAL_RECORDED"
  for i in 9 10 11 12; do
    expect "libro=saldo u$i" "$(rq "select coalesce(sum(cantidad), 0) = coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '$(u "$i")'), 0) from public.transacciones_cartera where usuario_id = '$(u "$i")'")" t
  done
  # N4-2: un aviso aplazado por G1 queda retenido (sin intento, nunca muerto aunque haya dead letter), se mide aparte (no es `pendiente`) y solo
  # se vuelve a pedir cuando el trabajo nombra su motivo (interruptor encendido).
  out="$(rq "select coalesce((select n from public.app_store_inbox_stats() where estado = 'pendiente'), 0)")"
  k="$(rq "select reclamo from public.app_store_inbox_put('app_store', 'n42-1', 'COMPRA', 'n42-key', 'produccion', repeat('b', 64), '{}'::jsonb)")"
  expect "N4-2 mark" "$(rq "select public.app_store_inbox_mark('app_store', 'n42-1', 'retenido', null, 'LIVE_CREDIT_DISABLED', '$k', 1)")" t
  expect "N4-2 retenido" "$(rq "select estado || '|' || intentos from public.tienda_bandeja where evento_id = 'n42-1'")" "retenido|0"
  expect "N4-2 stats" "$(rq "select string_agg(estado || ':' || n, ',' order by estado) from public.app_store_inbox_stats() where estado like 'retenido%'")|$(rq "select coalesce((select n from public.app_store_inbox_stats() where estado = 'pendiente'), 0)")" "retenido_decision:1|$out"
  expect "N4-2 interruptor apagado" "$(rq "select count(*) from public.app_store_inbox_pending(200, array['REFUNDS_DISABLED', 'SUBSCRIPTIONS_DISABLED']) where evento_id = 'n42-1'")" 0
  expect "N4-2 interruptor encendido" "$(rq "select count(*) from public.app_store_inbox_pending(200, array['LIVE_CREDIT_DISABLED']) where evento_id = 'n42-1'")" 1
  # N5-1: una lectura de Notification History a medias guarda el token y su ventana SIN mover el cursor; al acabar la ventana el cursor avanza y
  # el token se olvida. N5-2: la sobrecarga booleana de app_store_inbox_pending ya no existe.
  rq "select public.app_store_poll_advance('app_store', 'notification_history', 1000)" >/dev/null
  out="$(rq "select public.app_store_poll_page('app_store', 'notification_history', 'tok-2', 900, 5000)")"
  expect "N5-1 pagina" "$out|$(rq "select cursor_ms || '|' || pagina_token || '|' || ventana_inicio_ms || '|' || ventana_fin_ms from public.app_store_poll_get('app_store', 'notification_history')")" "t|1000|tok-2|900|5000"
  out="$(rq "select public.app_store_poll_advance('app_store', 'notification_history', 5000)")"
  expect "N5-1 fin" "$out|$(rq "select cursor_ms || '|' || coalesce(pagina_token, '-') || '|' || coalesce(ventana_fin_ms::text, '-') from public.app_store_poll_get('app_store', 'notification_history')")" "5000|5000|-|-"
  expect "N5-2 sobrecarga" "$(rq "select to_regprocedure('public.app_store_inbox_pending(integer,boolean)') is null")" t
  # N6-2: un token de 5000 caracteres se guarda (el limite de 20261022103200 era 4096)
  expect "N6-2 token largo" "$(rq "select public.app_store_poll_page('app_store', 'notification_history', repeat('t', 5000), 5000, 9000)")|$(rq "select length(pagina_token) from public.app_store_poll_get('app_store', 'notification_history')")" "t|5000"
  # N4-5: con 300k avisos procesados, app_store_inbox_stats lee el indice parcial del atasco (contador de escaneos del indice, no el texto del plan)
  psqlc >/dev/null 2>"$err" <<'SQL' || { cat "$err"; fail "N4-5: no se siembra la bandeja"; }
insert into public.tienda_bandeja (proveedor, evento_id, tipo, clave_compra, entorno, huella, estado, resultado, procesado_en)
  select 'google_play', 'n45-' || g, 'COMPRA', 'n45-k' || g, 'produccion', repeat('a', 64), 'procesado', 'CREDITED', now() from generate_series(1, 300000) g;
analyze public.tienda_bandeja;
SQL
  out="$(rq "select coalesce((select idx_scan from pg_stat_user_indexes where indexrelname = 'ix_tienda_bandeja_atasco'), -1)")"
  rq "select count(*) from public.app_store_inbox_stats()" >/dev/null; sleep 1   # el contador se publica al cerrar la sesion
  expect "N4-5 indice del atasco" "$(rq "select coalesce((select idx_scan from pg_stat_user_indexes where indexrelname = 'ix_tienda_bandeja_atasco'), -1) > $out and $out >= 0")" t
  out="$(rq "select count(*) from public.tienda_bandeja b where b.resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD') and exists (select 1 from public.compras_usuario c where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra)")"
  expect "huerfanos con pedido" "$out" 0
  for i in 1 2 3 4 5 6 8; do
    expect "libro=saldo u$i" "$(rq "select coalesce(sum(cantidad), 0) = coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '$(u "$i")'), 0) from public.transacciones_cartera where usuario_id = '$(u "$i")'")" t
  done
  expect "app_wallet_reconcile" "$(rq 'select count(*) from public.app_wallet_reconcile(null)')" 0
  [ "$bad" = 0 ] || fail "carreras de tienda con dos conexiones (cerrojo por clave / orden de cerrojos)"
}
store_race
echo "ok:   carreras de tienda con dos conexiones reales (R1..R4, S1..S3, D1 x2): cerrojo por clave, sin 40P01, libro = saldo"
# 7. H-04 (docs/qa/EXAM_TIMER_H04.md): carreras REALES del cierre del intento con dos conexiones. CARACTERIZAN el comportamiento de hoy
#    (sin limite de tiempo): cierre y respuesta se serializan por el FOR UPDATE de la fila del intento. F1 doble cierre -> uno cierra y el
#    otro ve `already`; F2 cierre (abierto) || respuesta -> la respuesta espera, se rechaza y no escribe; F3 respuesta (abierta) || cierre ->
#    el cierre espera y la cuenta; F4 cierre (abierto) || abandono -> el abandono se rechaza; F5 tras el cierre nada mueve la fila.
exam_race() {
  local i a bad=0 snap rid ex='e4040000-0000-0000-0000-000000000002'
  expect() { [ "$2" = "$3" ] || { echo "  race $1: '$2' (esperado '$3')"; bad=1; }; }
  noerr() { local f; for f in "$@"; do [ ! -s "$tmp/race.$f.err" ] || { echo "  race $f: $(head -1 "$tmp/race.$f.err")"; bad=1; }; done; }
  u() { printf 'a0000000-0000-0000-0000-0000000004a%d' "$1"; }
  q() { printf 'f4040000-0000-0000-0000-00000000001%d' "$1"; }
  ok() { printf '0a404000-0000-0000-001%d-000000000001' "$1"; }
  start() { rq "select public.app_start_exam_attempt('$(u "$1")', '$ex') ->> 'intento_id'"; }
  fin() { echo "select public.app_finish_exam_attempt('$(u "$1")', '$2') ->> 'already';"; }
  ans() { echo "select public.app_record_attempt_answer('$(u "$1")', '$2', '$(q "$3")', '$(ok "$3")', 1000, 0, null) ->> 'outcome';"; }
  row() { rq "select estado || '|' || coalesce(motivo_fin, '-') || '|' || total_respondidas || '|' || total_correctas || '|' || (select count(*) from public.respuestas_intento r where r.intento_id = ie.id) from public.intentos_examen ie where id = '$1'"; }
  fp() { rq "select md5((to_jsonb(ie) - 'actualizado_en')::text) from public.intentos_examen ie where id = '$1'"; }
  psqlc >/dev/null 2>"$err" <<'SQL' || { cat "$err"; fail "carreras del intento: no se siembran los datos"; }
insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-0000000004a' || n)::uuid, 'h04race' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 4) n;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-000000000405', 'H4R', 'H4R', 'h4r', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-000000000405', 'H4R', 'Com H4R', 'com-h4r');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select ('a0000000-0000-0000-0000-0000000004a' || n)::uuid, 'h04race' || n, 'h04_race_' || n, true, 'activa', 'e1000000-0000-0000-0000-000000000405' from generate_series(1, 4) n
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
begin;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, duracion_minutos)
  values ('e4040000-0000-0000-0000-000000000002', 'H04-RACE', 'e1000000-0000-0000-0000-000000000405', 'c1000000-0000-0000-0000-000000000405', 'H04 race', 'rafaga', 'gratuito', true, now() - interval '1 day', 1);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
  select ('f4040000-0000-0000-0000-00000000001' || n)::uuid, 'H04-R' || n, 'rafaga', 'Pregunta ' || n, true from generate_series(1, 3) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
  select ('0a404000-0000-0000-001' || n || '-00000000000' || o)::uuid, ('f4040000-0000-0000-0000-00000000001' || n)::uuid, chr(64 + o), 'Opcion', o
  from generate_series(1, 3) n, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
  select ('f4040000-0000-0000-0000-00000000001' || n)::uuid, ('0a404000-0000-0000-001' || n || '-000000000001')::uuid, 'x' from generate_series(1, 3) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
  select 'e4040000-0000-0000-0000-000000000002', ('f4040000-0000-0000-0000-00000000001' || n)::uuid, n, n from generate_series(1, 3) n;
commit;
SQL
  for i in 1 2; do
    a="$(start 1)"; rq "$(ans 1 "$a" 1)" >/dev/null
    race2 "F1.$i" "$(fin 1 "$a")" "$(fin 1 "$a")"
    expect "F1.$i" "$(cat "$tmp/race.a.out")|$(cat "$tmp/race.b.out")|$(row "$a")" "false|true|finalizado|USER|1|1|1"
    a="$(start 2)"; rq "$(ans 2 "$a" 1)" >/dev/null
    race2 "F2.$i" "$(fin 2 "$a")" "$(ans 2 "$a" 2)"
    expect "F2.$i" "$(cat "$tmp/race.b.out")|$(row "$a")" "ATTEMPT_NOT_IN_PROGRESS|finalizado|USER|1|1|1"
    a="$(start 3)"
    race2 "F3.$i" "$(ans 3 "$a" 1)" "$(fin 3 "$a")"
    expect "F3.$i" "$(cat "$tmp/race.a.out")|$(cat "$tmp/race.b.out")|$(row "$a")" "OK|false|finalizado|USER|1|1|1"
    a="$(start 4)"
    race2 "F4.$i" "$(fin 4 "$a")" "select public.app_abandon_exam_attempt('$(u 4)', '$a', 1, 'salida_usuario') ->> 'outcome';"
    expect "F4.$i" "$(cat "$tmp/race.b.out")|$(row "$a")" "ATTEMPT_ALREADY_FINISHED|finalizado|USER|0|0|0"
    snap="$(fp "$a")"
    expect "F5.$i" "$(rq "$(fin 4 "$a")")|$(rq "$(ans 4 "$a" 1)")|$(fp "$a")" "true|ATTEMPT_NOT_IN_PROGRESS|$snap"
  done
  expect "TIMEOUT" "$(rq "select count(*) from public.intentos_examen where motivo_fin = 'TIMEOUT'")" 0
  [ "$bad" = 0 ] || fail "carreras del intento con dos conexiones (H-04)"
}
exam_race
echo "ok:   carreras del intento con dos conexiones reales (F1..F5 x2): cierre serializado, nada tras el cierre, sin TIMEOUT"
echo "db-pg17-gate OK"
