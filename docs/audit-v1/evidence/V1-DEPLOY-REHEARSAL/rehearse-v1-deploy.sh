#!/usr/bin/env bash
# V1-DEPLOY-REHEARSAL: ensayo COMPLETO local de las migraciones del despliegue V1 a MAIN (requisito 1 de OposiControl).
# NO toca MAIN ni la red: un contenedor PROPIO y desechable (PG17 Supabase fijado por digest, el mismo del gate), sin puerto
# publicado, 1 GB de RAM. Nunca usa los stacks compartidos oposi-exam-local ni oposi-control-e2e. Al acabar borra SOLO su
# contenedor, por nombre explicito.
#
# Diferencias con scripts/db-pg17-gate.sh (que sigue siendo el gate de CI, verde en develop d9d4d28d):
#   * forma de MAIN COMPLETA: la captura READ-ONLY de catalogo de MAIN del 2026-10-14 (95 tablas public, sin filas) en vez del
#     subconjunto; + vistas de la captura 2026-09-29 (la de 10-14 no las trae) + forma GoTrue de auth.users + stub de
#     storage.objects del expediente SEC-V1-01 (RB-16);
#   * semilla SINTETICA (seed_synthetic.sql) para recorrer los caminos de datos (relleno legacy_opening, indices unicos);
#   * ORDEN REAL del despliegue: las DEBT-156b fuera de PENDING (20261020100000, 20261021100000, 20261023100000..100400 y las
#     extra que se pasen) mezcladas por version con las 34 PENDING; 20261023100200 queda despues de 20261022102600 (CAND-04);
#   * PREFLIGHT de solo lectura (los mismos ficheros SQL que ejecutara el propietario en MAIN) antes y despues;
#   * aplica como `postgres` NO superusuario (como el editor SQL de MAIN); RB-16 se ensaya primero SIN pertenencia al dueno
#     de storage.objects (debe detenerse en su PREFLIGHT sin cambiar nada) y despues CON ella;
#   * ROLLBACK de todas en orden inverso (las dos LEGACY sin ROLLBACK quedan aplicadas: son forward-only), huella == estado
#     previo, re-aplicacion + VERIFY = 0 y huella == primera aplicacion.
#
# Uso (Git Bash, desde la raiz del worktree):
#   EXTRA_MIGRATIONS="<ruta 20261023100500...sql> [<ruta 20261023100600...sql>]" \
#   bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rehearse-v1-deploy.sh <directorio de salida>
# Entradas fuera del repo (solo lectura, sin secretos ni filas): MAIN_BASELINE (por defecto la captura 2026-10-14) y MAIN_VIEWS.
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 2
OUT="${1:?directorio de salida}"; mkdir -p "$OUT"
CTR="v1-deploy-rehearsal-pg17"
IMAGE="public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9" # 17.6.1.171
MAIN_BASELINE="${MAIN_BASELINE:-/c/Users/franr/.oposibots-gate/baseline-20261014/baseline_main.sql}"
MAIN_BASELINE_SHA="${MAIN_BASELINE_SHA:-07acead5e0598df501aabd83e1e281d8776e653bea846e98932f233021354a46}"   # recaptura (F3.6): pasar su sha256
MAIN_VIEWS="${MAIN_VIEWS:-/c/Users/franr/.oposibots-gate/baseline/06_views.sql}"
HERE=docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL
SQLD=docs/engineering/v1-deploy
LEGACY_NO_ROLLBACK="20261020100000_security_access_request_email_verified 20261021100000_security_audit_log_append_only"
STORAGE_MIG=20261023100100_security_storage_admin_write_revoke
LOG="$OUT/rehearsal.log"
fail() { echo "FAIL: $*" | tee -a "$LOG"; exit 1; }
say() { echo "$*" | tee -a "$LOG"; }
: > "$LOG"

[ "$(sha256sum "$MAIN_BASELINE" | cut -c1-64)" = "$MAIN_BASELINE_SHA" ] || fail "la captura de MAIN no es la esperada ($MAIN_BASELINE)"
tmp="$(mktemp -d)"; err="$tmp/err"
cleanup() { docker rm -f "$CTR" >/dev/null 2>&1; rm -rf "$tmp"; }
trap cleanup EXIT
docker rm -f "$CTR" >/dev/null 2>&1
docker run -d --name "$CTR" --memory 1g --cpus 2 -e POSTGRES_PASSWORD=postgres "$IMAGE" >/dev/null || fail "no arranca $IMAGE"
psqlc() { docker exec -i "$CTR" psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
psqla() { docker exec -i "$CTR" psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
for _ in $(seq 1 90); do
  [ "$(psqlc -Atc "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] \
    && [ "$(psqlc -Atc "select to_regclass('auth.users') is not null" 2>/dev/null)" = t ] && break
  sleep 2
done
say "ok:   $(psqlc -Atc "select version()" | cut -d' ' -f1-2); postgres superusuario = $(psqlc -Atc "select rolsuper from pg_roles where rolname = 'postgres'")"

# ---- 1. Forma de MAIN completa ----
lines=$(grep -n '^-- seccion 09' "$MAIN_BASELINE" | cut -d: -f1)
{ head -n $((lines - 1)) "$MAIN_BASELINE"; sed -n '6,$p' "$MAIN_VIEWS"; tail -n +"$lines" "$MAIN_BASELINE"; } \
  | grep -v '^ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin' > "$tmp/boot.sql"
psqlc -1 < "$tmp/boot.sql" >/dev/null 2>"$err" || { grep -v NOTICE "$err" | tee -a "$LOG"; fail "la captura de MAIN no carga con ON_ERROR_STOP=1"; }
psqla < supabase/baseline/main_shape_auth_users.sql >/dev/null 2>"$err" || { cat "$err"; fail "main_shape_auth_users.sql"; }
psqla < supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql >/dev/null 2>"$err" || { cat "$err"; fail "stub de storage.objects"; }
psqlc < "$HERE/seed_synthetic.sql" >/dev/null 2>"$err" || { cat "$err"; fail "semilla sintetica"; }
say "ok:   forma de MAIN: $(psqlc -Atc "select count(*) from pg_tables where schemaname = 'public'") tablas public, $(psqlc -Atc "select count(*) from pg_proc where pronamespace = 'public'::regnamespace") funciones public, $(psqlc -Atc "select count(*) from pg_policies where schemaname in ('public','storage')") politicas (public+storage); semilla sintetica cargada"

# ---- 2. Orden real ----
block() { # bloque comentado -- NAME-BEGIN/END (misma regla que el gate)
  awk -v n="$2" '$0 ~ "^[[:space:]]*-- "n"-END[[:space:]]*$" {e=1; exit} s {print} $0 ~ "^[[:space:]]*-- "n"-BEGIN[[:space:]]*$" {s=1} END {exit !(s && e)}' "$1" \
    | { bad=0; while IFS= read -r l; do l="${l%$'\r'}"; case "$l" in --*) l="${l#--}"; echo "${l# }" ;; *) bad=1 ;; esac; done; exit $bad; }
}
declare -A PATHOF
for m in $(tr -d '\r' < supabase/baseline/PENDING_MIGRATIONS.txt | grep -Ev '^[[:space:]]*(#|$)'); do PATHOF[$m]="supabase/migrations/$m.sql"; done
for f in supabase/migrations/20261020100000_*.sql supabase/migrations/20261021100000_*.sql supabase/migrations/20261023*_security_*.sql; do
  m="$(basename "$f" .sql)"; PATHOF[$m]="$f"; done
for f in ${EXTRA_MIGRATIONS:-}; do [ -s "$f" ] || fail "EXTRA_MIGRATIONS: $f no existe"; m="$(basename "$f" .sql)"; PATHOF[$m]="$f"; done
ALL="$(printf '%s\n' "${!PATHOF[@]}" | sort)"
# Estado de MAIN despues de 2026-10-22: PREAPPLIED_ON_MAIN = versiones ya aplicadas en MAIN FUERA del historial (se cargan como
# parte del punto de partida y NO entran en el orden ni en el ROLLBACK); NEVER_RUN = versiones que no se ejecutan nunca (100100:
# RB-16 hecho a mano); MANUAL_STORAGE_DROP=1 reproduce el borrado manual de las 14 politicas de escritura de storage.objects.
PRE_V="${PREAPPLIED_ON_MAIN:-}"; NEVER_V="${NEVER_RUN:-}"
in_v() { local m="$1" v; for v in $2; do [ "${m%%_*}" = "$v" ] && return 0; done; return 1; }
ORDER="$(for m in $ALL; do in_v "$m" "$PRE_V $NEVER_V" || echo "$m"; done)"
PRE_ORDER="$(for m in $ALL; do in_v "$m" "$PRE_V" && echo "$m"; done)"
n=0; : > "$OUT/order.txt"
for m in $ORDER; do n=$((n + 1)); printf '%2d %s %s\n' "$n" "$m" "$(sha256sum "${PATHOF[$m]}" | cut -c1-16)" >> "$OUT/order.txt"; done
say "ok:   orden real: $n migraciones (fichero order.txt con sha256/16 de cada una)"
p26="$(grep -n '20261022102600_' "$OUT/order.txt" | cut -d: -f1)"; p02="$(grep -n '20261023100200_' "$OUT/order.txt" | cut -d: -f1)"
[ "$p02" -gt "$p26" ] || fail "CAND-04: 20261023100200 no va despues de 20261022102600"
say "ok:   CAND-04: 20261023100200 (posicion $p02) despues de 20261022102600 (posicion $p26)"

fingerprint() { # catalogo public/private/storage por NOMBRE (como el gate, + politicas de storage)
  psqlc -At > "$tmp/fp.raw" 2>"$err" <<'SQL' || { cat "$err"; fail "huella"; }
select 'col:'||table_schema||'.'||table_name||'.'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema in ('public','private');
select 'con:'||conrelid::regclass::text||'.'||conname||':'||pg_get_constraintdef(oid) from pg_constraint where connamespace in ('public'::regnamespace,'private'::regnamespace);
select 'idx:'||schemaname||'.'||tablename||'.'||indexname||':'||indexdef from pg_indexes where schemaname in ('public','private');
select 'trg:'||tgrelid::regclass::text||'.'||tgname||':'||tgfoid::regproc::text from pg_trigger where not tgisinternal and (tgrelid::regclass::text not like 'auth.%' or tgrelid = 'auth.users'::regclass);
select 'fn:'||p.oid::regprocedure::text||':'||p.prosecdef::text||':'||coalesce(p.proconfig::text,'')||':'||coalesce(p.proacl::text,'') from pg_proc p where p.pronamespace in ('public'::regnamespace,'private'::regnamespace);
select 'pol:'||schemaname||'.'||tablename||'.'||policyname||':'||cmd||':'||roles::text||':'||coalesce(qual,'')||':'||coalesce(with_check,'') from pg_policies where schemaname in ('public','private','storage');
select 'rel:'||c.oid::regclass::text||':'||c.relkind::text||':'||c.relrowsecurity::text||':'||coalesce(c.relacl::text,'') from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace,'storage'::regnamespace) and c.relkind in ('r','p','v','S');
SQL
  sort "$tmp/fp.raw" > "$1"; [ -s "$1" ] || fail "huella vacia"
}
verify() { # $1 = migracion; $2 = etiqueta; exige >= 1 fila y columna 2 = 0
  local out f="${PATHOF[$1]}"
  out="$(block "$f" VERIFY | psqlc -At -F '|' 2>&1)" || { echo "$out" | tee -a "$LOG"; fail "$1: VERIFY no se ejecuta"; }
  [ -n "$out" ] || fail "$1: VERIFY sin filas"
  echo "$out" | sed "s/^/  $2 $1 | /" >> "$OUT/verify.txt"
  echo "$out" | awk -F'|' '$2 != "0" {bad=1; print "  VERIFY " $1 " = " $2} END {exit bad}' | tee -a "$LOG"; [ "${PIPESTATUS[1]}" = 0 ] || fail "$1: VERIFY distinto de 0 ($2)"
}
verify_before() { # informativo: el VERIFY ANTES de aplicar (en MAIN es la foto de solo lectura previa)
  local f="${PATHOF[$1]}"
  { echo "## $1"; block "$f" VERIFY | psqlc -At -F '|' 2>&1 || true; } >> "$OUT/verify_before.txt"
}
apply() { # $1 = migracion; $2 = etiqueta
  local f="${PATHOF[$1]}" t0 t1
  t0=$(date +%s%N)
  psqlc < "$f" > "$tmp/apply.out" 2>"$err" || { grep -E 'ERROR|DETAIL|HINT' "$err" | tee -a "$LOG"; return 1; }
  t1=$(date +%s%N)
  printf '%s %s %d ms\n' "$2" "$1" $(( (t1 - t0) / 1000000 )) >> "$OUT/timings.txt"
}
run_sql() { # $1 = fichero; $2 = salida
  psqlc -v ON_ERROR_STOP=1 < "$1" > "$2" 2>&1 || { tail -5 "$2" | tee -a "$LOG"; fail "$1 no se ejecuta"; }
}

# ---- 2b. Punto de partida = MAIN de hoy: lo ya aplicado fuera del historial y el borrado manual de RB-16 ----
: > "$OUT/preapplied.txt"
for m in $PRE_ORDER; do
  psqlc < "${PATHOF[$m]}" > /dev/null 2>"$err" || { cat "$err" | tee -a "$LOG"; fail "$m (ya aplicada en MAIN) no carga en el punto de partida"; }
  out="$(block "${PATHOF[$m]}" VERIFY | psqlc -At -F '|' 2>&1)"; echo "$out" | sed "s/^/  preapplied $m | /" >> "$OUT/preapplied.txt"
  echo "$out" | awk -F'|' '$2 != "0" {bad=1} END {exit bad}' || fail "$m: VERIFY del punto de partida distinto de 0"
done
[ -n "$PRE_ORDER" ] && say "ok:   punto de partida = MAIN 2026-10-22: aplicadas fuera del historial con VERIFY = 0: $(echo $PRE_ORDER | tr ' ' ',')"
if [ "${MANUAL_STORAGE_DROP:-0}" = 1 ]; then
  psqla -At > "$OUT/manual_storage_drop.txt" 2>"$err" <<'SQL' || { cat "$err"; fail "borrado manual de storage"; }
do $$ declare p record; n int := 0; begin
  for p in select polname from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*')
             and (coalesce(pg_get_expr(polqual, polrelid), '') ~ '(es_admin|can_manage_content)\(' or coalesce(pg_get_expr(polwithcheck, polrelid), '') ~ '(es_admin|can_manage_content)\(')
  loop execute format('drop policy %I on storage.objects', p.polname); n := n + 1; end loop;
  raise notice 'politicas borradas: %', n; end $$;
SQL
  say "ok:   RB-16 a mano (como el propietario en Storage > Policies): $(grep -oE 'borradas: [0-9]+' "$err" || echo '?') politicas de escritura de storage.objects; $NEVER_V no se ejecuta nunca"
fi

# Huella del punto de partida (comparable con la de una RECAPTURA de MAIN, F3.6: misma funcion de huella).
fingerprint "$OUT/fingerprint_start.txt"
say "ok:   huella del punto de partida: fingerprint_start.txt ($(wc -l < "$OUT/fingerprint_start.txt") lineas)"

# ---- 3. PREFLIGHT de solo lectura (los mismos ficheros que el propietario ejecutara en MAIN) ----
run_sql "$SQLD/01_catalog_preflight_readonly.sql" "$OUT/pre_01_catalog.txt"
run_sql "$SQLD/02_data_preflight_readonly.sql" "$OUT/pre_02_data.txt"
run_sql scripts/db-exam-community-audit.sql "$OUT/pre_exam_community_audit.txt"
if in_v 20261023100000 "$PRE_V"; then
  say "ok:   PREFLIGHT de solo lectura ejecutado (catalogo, datos, auditoria de comunidad); SEC-V1-01 ya aplicada: su preflight no aplica"
else
  run_sql supabase/canonical/sec_v1_01_main_intervention/01_preflight_readonly.sql "$OUT/pre_sec_v1_01_preflight.txt"
  run_sql supabase/canonical/sec_v1_01_main_intervention/02_checkpoint_and_compat_readonly.sql "$OUT/pre_sec_v1_01_checkpoint.txt"
  falses="$(grep -cE '\| f +\|' "$OUT/pre_sec_v1_01_preflight.txt" || true)"
  say "ok:   PREFLIGHT de solo lectura ejecutado (catalogo, datos, SEC-V1-01 preflight/checkpoint, auditoria de comunidad); SEC-V1-01 filas ok=false: $falses"
  [ "$falses" = 0 ] || fail "SEC-V1-01 preflight con filas false"
fi
grep -E 'BLOQUEA' "$OUT/pre_02_data.txt" | awk -F'|' '$2+0 != 0 && $3 !~ /BLOQUEA si 0/ {print; bad=1} END {exit bad}' | tee -a "$LOG"; [ "${PIPESTATUS[1]}" = 0 ] || fail "preflight de datos con un BLOQUEA distinto de 0"

# ---- 4. Primera aplicacion, en el orden real, con VERIFY despues de cada una ----
: > "$OUT/verify.txt"; : > "$OUT/verify_before.txt"; : > "$OUT/timings.txt"
fp_after_legacy=""
for m in $ORDER; do
  verify_before "$m"
  if [ "$m" = "$STORAGE_MIG" ]; then
    fingerprint "$tmp/s0"
    if apply "$m" first-noowner 2>/dev/null; then fail "$m aplico SIN pertenencia al dueno de storage.objects (se esperaba PREFLIGHT)"; fi
    grep -q STORAGE_ADMIN_WRITE_PREFLIGHT "$err" || { cat "$err"; fail "$m fallo por otra causa"; }
    fingerprint "$tmp/s1"; cmp -s "$tmp/s0" "$tmp/s1" || fail "$m cambio el catalogo al detenerse en su PREFLIGHT"
    say "ok:   $m como postgres SIN pertenencia: STORAGE_ADMIN_WRITE_PREFLIGHT, catalogo intacto (caso probable en MAIN, ver plan)"
    psqla -c "grant supabase_storage_admin to postgres" >/dev/null || fail "grant de pertenencia"
    say "info: grant supabase_storage_admin to postgres (solo en el ensayo; en MAIN es una decision del propietario)"
  fi
  apply "$m" first || fail "$m no aplica (ON_ERROR_STOP)"
  verify "$m" first
  case " $LEGACY_NO_ROLLBACK " in *" $m "*) fp_after_legacy=pending ;; esac
  if [ "$fp_after_legacy" = pending ] && [ "$m" = "${LEGACY_NO_ROLLBACK##* }" ]; then fingerprint "$tmp/f0"; fp_after_legacy=done; fi
done
[ "$fp_after_legacy" = done ] || fail "no se tomo la huella tras las LEGACY"
fingerprint "$tmp/f1"
say "ok:   primera aplicacion: $n migraciones en orden real, VERIFY = 0 en todas ($(wc -l < "$OUT/verify.txt") filas)"

# ---- 5. Comprobaciones de datos y de catalogo despues ----
run_sql "$SQLD/01_catalog_preflight_readonly.sql" "$OUT/post_01_catalog.txt"
psqlc -At -F '|' > "$OUT/post_wallet.txt" 2>&1 <<'SQL' || fail "comprobaciones de cartera"
select 'legacy_opening_rows', count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening';
select 'legacy_opening_sum', coalesce(sum(cantidad), 0) from public.transacciones_cartera where referencia_tipo = 'legacy_opening';
select 'reconcile_rows', count(*) from public.app_wallet_reconcile(null);
select 'saldos', string_agg(saldo_tickets::text, ',' order by usuario_id) from public.carteras_usuario where usuario_id::text like 'd0000000%';
select 'filas_sin_seq', count(*) from public.transacciones_cartera where seq is null;
SQL
say "ok:   cartera tras aplicar: $(tr '\n' ' ' < "$OUT/post_wallet.txt")"
grep -q '^reconcile_rows|0$' "$OUT/post_wallet.txt" || fail "app_wallet_reconcile no da 0 filas"
grep -q '^legacy_opening_rows|2$' "$OUT/post_wallet.txt" || fail "se esperaban 2 aperturas legacy (u2 = 50, u4 = 10)"
grep -q '^saldos|50,30,20$' "$OUT/post_wallet.txt" || fail "un saldo cambio"
if psqlc < supabase/baseline/assertions.sql > "$OUT/assertions.txt" 2>&1; then say "ok:   assertions.sql del gate sobre la forma COMPLETA + semilla: OK"
else say "info: assertions.sql del gate sobre la forma COMPLETA + semilla: FALLA (ver assertions.txt; escrito para el subconjunto vacio)"; fi

# ---- 6. ROLLBACK en orden inverso (las LEGACY quedan: forward-only) ----
rbn=0
for m in $(echo "$ORDER" | sort -r); do
  case " $LEGACY_NO_ROLLBACK " in *" $m "*) continue ;; esac
  block "${PATHOF[$m]}" ROLLBACK | psqlc >/dev/null 2>"$err" || { cat "$err" | tee -a "$LOG"; fail "$m: ROLLBACK falla"; }
  rbn=$((rbn + 1))
done
fingerprint "$tmp/f2"
if cmp -s "$tmp/f0" "$tmp/f2"; then say "ok:   ROLLBACK inverso de $rbn migraciones: catalogo == estado tras las 2 LEGACY"
else
  diff "$tmp/f0" "$tmp/f2" > "$OUT/rollback_diff.txt"
  say "HALLAZGO: los bloques ROLLBACK solos NO devuelven la forma de MAIN ($(grep -c '^[<>]' "$OUT/rollback_diff.txt") lineas, rollback_diff.txt; < MAIN, > tras ROLLBACK)"
  psqlc < "$SQLD/R2_post_rollback_restore_main_acl.sql" >/dev/null 2>"$err" || { cat "$err" | tee -a "$LOG"; fail "R2_post_rollback_restore_main_acl.sql no se ejecuta"; }
  fingerprint "$tmp/f2b"
  cmp -s "$tmp/f0" "$tmp/f2b" || { diff "$tmp/f0" "$tmp/f2b" > "$OUT/rollback_diff_after_restore.txt"; fail "ROLLBACK + R2_post_rollback_restore_main_acl.sql sigue difiriendo (rollback_diff_after_restore.txt)"; }
  say "ok:   ROLLBACK inverso de $rbn migraciones + R2_post_rollback_restore_main_acl.sql: catalogo == estado tras las 2 LEGACY (forma de MAIN exacta)"
fi
psqlc -At -F '|' > "$OUT/post_rollback_wallet.txt" 2>&1 <<'SQL'
select 'legacy_opening_rows', count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening';
select 'saldos', string_agg(saldo_tickets::text, ',' order by usuario_id) from public.carteras_usuario where usuario_id::text like 'd0000000%';
SQL
say "info: datos tras el ROLLBACK (las aperturas son filas normales y quedan, documentado en 20261022100300): $(tr '\n' ' ' < "$OUT/post_rollback_wallet.txt")"

# ---- 7. Re-aplicacion + VERIFY = 0, huella == primera aplicacion ----
for m in $ORDER; do
  case " $LEGACY_NO_ROLLBACK " in *" $m "*) continue ;; esac
  apply "$m" reapply || fail "$m no re-aplica"
  verify "$m" reapply
done
fingerprint "$tmp/f3"
cmp -s "$tmp/f1" "$tmp/f3" || { diff "$tmp/f1" "$tmp/f3" > "$OUT/reapply_diff.txt"; fail "re-aplicar no reproduce el catalogo (reapply_diff.txt)"; }
psqlc -At -F '|' > "$OUT/post_reapply_wallet.txt" 2>&1 <<'SQL'
select 'legacy_opening_rows', count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening';
select 'reconcile_rows', count(*) from public.app_wallet_reconcile(null);
select 'saldos', string_agg(saldo_tickets::text, ',' order by usuario_id) from public.carteras_usuario where usuario_id::text like 'd0000000%';
SQL
grep -q '^legacy_opening_rows|2$' "$OUT/post_reapply_wallet.txt" || fail "la re-aplicacion duplico aperturas legacy"
grep -q '^reconcile_rows|0$' "$OUT/post_reapply_wallet.txt" || fail "reconcile tras re-aplicar"
say "ok:   re-aplicacion: VERIFY = 0, catalogo == primera aplicacion, sin aperturas duplicadas ($(tr '\n' ' ' < "$OUT/post_reapply_wallet.txt"))"
run_sql "$SQLD/01_catalog_preflight_readonly.sql" "$OUT/post_reapply_01_catalog.txt"
cp "$tmp/f1" "$OUT/fingerprint_after_apply.txt"
say "V1-DEPLOY-REHEARSAL OK"
