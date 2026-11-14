#!/usr/bin/env bash
# Ensayo LOCAL COMBINADO de la ventana servidor 1.3 (integ/v1-3-server): las 11 migraciones en el orden de despliegue, con el MISMO script y el manifiesto
# consolidado que usara el propietario, sobre un punto de partida con la forma de MAIN de hoy: captura de MAIN (2026-10-14) + ventana V1 (2026-10-22) +
# ventana servidor 2026-11 + F20 (ya desplegadas), reconstruido como docs/ledger/2026/11/rehearse-f27-2026-11.sh. NO toca MAIN ni la red: contenedor
# desechable `v1-3-2026-11-rehearsal` (PG17 17.6.1.171), sin puerto.
#   MANIFEST=ops/deploy/v1/main-migrations-2026-11-v1-3.manifest ops/deploy/v1/apply-main-migration.sh (REHEARSAL_CONTAINER), version tecleada.
# Comprueba: negativas (fuera del manifiesto; predecesora ausente); cada migracion aplicada + VERIFY = 0 + registrada; re-aplicar = STOP;
# sondas ANTES/DESPUES/tras ROLLBACK: (a) F27: datos de partida escritos con las reglas de produccion y su relleno, y comportamiento en transaccion deshecha;
# (b) 20261114500000: los trabajos de dificultad y ranking FALLAN en la forma de MAIN y funcionan tras la ventana; (c) una sola sobrecarga de
# app_answer_daily_challenge (la de 6 argumentos); ROLLBACK inverso con huella == punto de partida; re-aplicar con huella == primera aplicacion.
# Uso (Git Bash, desde la raiz del arbol): bash docs/ledger/2026/11/rehearse-v1-3-2026-11.sh <directorio de salida>
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 2
OUT="${1:?directorio de salida}"; mkdir -p "$OUT"; LOG="$OUT/rehearsal.log"; : > "$LOG"
C=v1-3-2026-11-rehearsal
IMG="public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9" # 17.6.1.171
MANIFEST=ops/deploy/v1/main-migrations-2026-11-v1-3.manifest
DEPLOYED_2026_11=ops/deploy/v1/main-migrations-2026-11.manifest; DEPLOYED_F20=ops/deploy/v1/main-migrations-2026-11-f20.manifest
BASE="${MAIN_BASELINE:-/c/Users/franr/.oposibots-gate/baseline-20261014/baseline_main.sql}"
BASE_SHA=07acead5e0598df501aabd83e1e281d8776e653bea846e98932f233021354a46
VIEWS="${MAIN_VIEWS:-/c/Users/franr/.oposibots-gate/baseline/06_views.sql}"
PRE="20261023100000 20261023100300 20261023100400 20261023100500"; NEVER="20261023100100"
say() { echo "$*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; exit 1; }
[ "$(sha256sum "$BASE" | cut -c1-64)" = "$BASE_SHA" ] || fail "captura de MAIN inesperada ($BASE)"
versions() { tr -d '\r' < "$1" | grep -Ev '^[[:space:]]*(#|$)' | awk '{print $1}'; }
NEW="$(versions "$MANIFEST")"; NOV="$(versions "$DEPLOYED_2026_11"; versions "$DEPLOYED_F20")"
is_in() { printf '%s\n' "$2" | grep -qx "${1%%_*}"; }
in_list() { local v; for v in $2; do [ "${1%%_*}" = "$v" ] && return 0; done; return 1; }
# el manifiesto consolidado va en orden de version (el mismo del gate PG17 y de backend/dbtest) y es una cadena lineal
[ "$NEW" = "$(echo "$NEW" | sort)" ] || fail "el manifiesto consolidado no esta en orden de version"
[ "$(echo "$NEW" | wc -l | tr -d ' ')" = 11 ] || fail "el manifiesto consolidado no tiene las 11 migraciones de la ventana"

TMPD="$(mktemp -d)"
docker rm -f $C >/dev/null 2>&1
trap 'docker rm -f '$C' >/dev/null 2>&1; rm -rf "$TMPD"' EXIT
docker run -d --name $C --memory 1g --cpus 2 -e POSTGRES_PASSWORD=postgres "$IMG" >/dev/null || fail "no arranca $IMG"
p()  { docker exec -i $C psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
pa() { docker exec -i $C psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
for _ in $(seq 1 90); do
  [ "$(p -At -c "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] \
    && [ "$(p -At -c "select to_regclass('auth.users') is not null" 2>/dev/null)" = t ] && break; sleep 2; done
say "ok:   $(p -At -c "select version()" | cut -d' ' -f1-2)"

# ---- punto de partida: forma de MAIN tras V1 (igual que rehearse-server-2026-11.sh) ----
lines=$(grep -n '^-- seccion 09' "$BASE" | cut -d: -f1)
{ head -n $((lines - 1)) "$BASE"; sed -n '6,$p' "$VIEWS"; tail -n +"$lines" "$BASE"; } | grep -v '^ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin' > "$TMPD/boot.sql"
p -1 < "$TMPD/boot.sql" >/dev/null 2>"$TMPD/err" || { grep -v NOTICE "$TMPD/err" | tail -5; fail "boot"; }
pa < supabase/baseline/main_shape_auth_users.sql >/dev/null 2>&1 || fail "auth.users"
pa < supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql >/dev/null 2>&1 || fail "stub storage"
p < docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/seed_synthetic.sql >/dev/null || fail "semilla"
pa -c "create schema if not exists supabase_migrations; create table if not exists supabase_migrations.schema_migrations (version text primary key, statements text[], name text); grant usage on schema supabase_migrations to postgres; grant all on supabase_migrations.schema_migrations to postgres;" >/dev/null
tr -d '\r' < supabase/baseline/MAIN_MIGRATION_HISTORY.txt | grep -Ev '^[[:space:]]*(#|$)' \
  | awk '{printf "insert into supabase_migrations.schema_migrations (version, name) values (%c%s%c, %c%s%c);\n", 39, $1, 39, 39, $2, 39}' | p >/dev/null
reg() { echo "insert into supabase_migrations.schema_migrations (version, name) values ('${1%%_*}', '${1#*_}') on conflict do nothing;" | p >/dev/null; }
for v in $PRE; do f=$(ls supabase/migrations/${v}_*.sql); p < "$f" >/dev/null 2>&1 || fail "preaplicada $v"; reg "$(basename "$f" .sql)"; done
pa -c "do \$\$ declare r record; begin for r in select polname from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*') and (coalesce(pg_get_expr(polqual, polrelid),'') ~ '(es_admin|can_manage_content)\\(' or coalesce(pg_get_expr(polwithcheck, polrelid),'') ~ '(es_admin|can_manage_content)\\(') loop execute format('drop policy %I on storage.objects', r.polname); end loop; end \$\$;" >/dev/null
# ventana V1 (2026-10-22), la ventana servidor 2026-11 y F20 (desplegadas), en orden de version; las 11 de la ventana 1.3 quedan fuera
declare -A PATHOF
for m in $(tr -d '\r' < supabase/baseline/PENDING_MIGRATIONS.txt | grep -Ev '^[[:space:]]*(#|$)'); do PATHOF[$m]="supabase/migrations/$m.sql"; done
for f in supabase/migrations/20261020100000_*.sql supabase/migrations/20261021100000_*.sql supabase/migrations/20261023*_security_*.sql; do PATHOF[$(basename "$f" .sql)]="$f"; done
n=0; k=0
for m in $(printf '%s\n' "${!PATHOF[@]}" | sort); do
  is_in "$m" "$NEW" && continue; in_list "$m" "$PRE $NEVER" && continue
  [ "${m%%_*}" = 20261023100100 ] && continue
  p < "${PATHOF[$m]}" >/dev/null 2>"$TMPD/err" || { grep -E 'ERROR|HINT' "$TMPD/err"; fail "punto de partida: $m no aplica"; }
  reg "$m"; if is_in "$m" "$NOV"; then k=$((k + 1)); else n=$((n + 1)); fi
done
[ "$k" = "$(echo "$NOV" | wc -l | tr -d ' ')" ] || fail "punto de partida: la ventana 2026-11 + F20 no esta completa ($k)"
say "ok:   punto de partida = MAIN de hoy: $n de la ventana V1 + $k de la ventana 2026-11 + F20 aplicadas y registradas; historial $(p -At -c "select count(*) || ' versiones, ultima ' || max(version) from supabase_migrations.schema_migrations")"
[ "$(p -At -c "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name in ('tipo', 'total_usuarios_procesados')")" = 1 ] \
  || fail "punto de partida: lotes_actualizacion_ranking no tiene la forma de la captura de MAIN"

fingerprint() { # misma huella que rehearse-server-2026-11.sh
  p -At > "$TMPD/fp.raw" <<'SQL' || fail "huella"
select 'col:'||table_schema||'.'||table_name||'.'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema in ('public','private');
select 'con:'||conrelid::regclass::text||'.'||conname||':'||pg_get_constraintdef(oid) from pg_constraint where connamespace in ('public'::regnamespace,'private'::regnamespace);
select 'idx:'||schemaname||'.'||tablename||'.'||indexname||':'||indexdef from pg_indexes where schemaname in ('public','private');
select 'trg:'||tgrelid::regclass::text||'.'||tgname||':'||tgfoid::regproc::text from pg_trigger where not tgisinternal and (tgrelid::regclass::text not like 'auth.%' or tgrelid = 'auth.users'::regclass);
select 'fn:'||p.oid::regprocedure::text||':'||p.prosecdef::text||':'||coalesce(p.proconfig::text,'')||':'||coalesce(p.proacl::text,'')||':'||md5(p.prosrc) from pg_proc p where p.pronamespace in ('public'::regnamespace,'private'::regnamespace);
select 'pol:'||schemaname||'.'||tablename||'.'||policyname||':'||cmd||':'||roles::text||':'||coalesce(qual,'')||':'||coalesce(with_check,'') from pg_policies where schemaname in ('public','private','storage');
select 'rel:'||c.oid::regclass::text||':'||c.relkind::text||':'||c.relrowsecurity::text||':'||coalesce(c.relacl::text,'') from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace,'storage'::regnamespace) and c.relkind in ('r','p','v','S');
SQL
  sort "$TMPD/fp.raw" > "$1"
}
block() { awk -v n="$2" '$0 ~ "^[[:space:]]*-- "n"-END[[:space:]]*$" {e=1; exit} s {print} $0 ~ "^[[:space:]]*-- "n"-BEGIN[[:space:]]*$" {s=1} END {exit !(s && e)}' "$1" \
  | { bad=0; while IFS= read -r l; do l="${l%$'\r'}"; case "$l" in --*) l="${l#--}"; echo "${l# }" ;; *) bad=1 ;; esac; done; exit $bad; }; }
# datos de partida (COMMIT, antes de la huella), los mismos que el ensayo de F27: examen penalizado (racional, 4 opciones, q1..q4) y examen premium cerrado
# (5 preguntas, demo). El usuario A, con las reglas de produccion y stats_activo = 0 (en la forma de MAIN el hook de 101600 falla): demo de q11, acceso, q11
# real, examen penalizado 3 bien + 1 mal. Relleno esperado de F27: q11 cuenta (quemada por la demo); ranking reconstruido con los 5.
seed_sql() { cat <<'SQL'
begin;
set local session_replication_role = replica;
insert into auth.users (id, email, aud, role, email_confirmed_at) values ('a0000000-0000-0000-0000-00000000f271', 'f27-a@test.local', 'authenticated', 'authenticated', now()),
  ('a0000000-0000-0000-0000-00000000f272', 'f27-b@test.local', 'authenticated', 'authenticated', now());
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-00000000f271', 'F27P', 'F27P', 'f27p', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-00000000f271', 'F27P', 'Com F27P', 'com-f27p');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  values ('a0000000-0000-0000-0000-00000000f271', 'a', 'f27_probe_a', true, 'activa', 'e1000000-0000-0000-0000-00000000f271'),
         ('a0000000-0000-0000-0000-00000000f272', 'b', 'f27_probe_b', true, 'activa', 'e1000000-0000-0000-0000-00000000f271')
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, regla_anuladas, penalizacion_racional)
  values ('e5000000-0000-0000-0000-00000000f271', 'F27P-PEN', 'e1000000-0000-0000-0000-00000000f271', 'c1000000-0000-0000-0000-00000000f271', 'Pen', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', true),
         ('e5000000-0000-0000-0000-00000000f272', 'F27P-LCK', 'e1000000-0000-0000-0000-00000000f271', 'c1000000-0000-0000-0000-00000000f271', 'Lck', 'rafaga', 'premium', true, now() - interval '1 day', 'NO_PUNTUA', false);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
  select ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid, 'F27P-' || n, 'rafaga', 'P' || n, true from generate_series(1, 15) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
  select ('0e800000-0000-0000-000' || o || '-0000000f27' || lpad(n::text, 2, '0'))::uuid, ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Op', o
    from generate_series(1, 15) n, generate_series(1, 4) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
  select ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid, ('0e800000-0000-0000-0001-0000000f27' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 15) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
  select 'e5000000-0000-0000-0000-00000000f271'::uuid, ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid, n, n from generate_series(1, 4) n
  union all
  select 'e5000000-0000-0000-0000-00000000f272'::uuid, ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid, n - 10, n - 10 from generate_series(11, 15) n;
set local session_replication_role = origin;
update public.configuracion_v1 set valor_integer = 0 where clave = 'stats_activo';
create function pg_temp.ans(u uuid, a uuid, n int, ok boolean) returns jsonb language sql as $f$
  select public.app_record_attempt_answer(u, a, ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid,
                                          ('0e800000-0000-0000-000' || case when ok then 1 else 2 end || '-0000000f27' || lpad(n::text, 2, '0'))::uuid, 1500, 0, null) $f$;
do $d$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-00000000f271';
  a uuid;
begin
  a := (public.app_start_exam_attempt(ua, 'e5000000-0000-0000-0000-00000000f272', 'DEMO') ->> 'intento_id')::uuid;
  perform pg_temp.ans(ua, a, 11, true);
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values (ua, 'e5000000-0000-0000-0000-00000000f272', 'admin', now() - interval '1 hour', now() + interval '5 days');
  a := (public.app_start_exam_attempt(ua, 'e5000000-0000-0000-0000-00000000f272', 'NORMAL') ->> 'intento_id')::uuid;
  perform pg_temp.ans(ua, a, 11, true);
  a := (public.app_start_exam_attempt(ua, 'e5000000-0000-0000-0000-00000000f271', 'NORMAL') ->> 'intento_id')::uuid;
  perform pg_temp.ans(ua, a, 1, true); perform pg_temp.ans(ua, a, 2, true); perform pg_temp.ans(ua, a, 3, true); perform pg_temp.ans(ua, a, 4, false);
  perform public.app_finish_exam_attempt(ua, a);
end
$d$;
update public.configuracion_v1 set valor_integer = 1 where clave = 'stats_activo';
commit;
SQL
}
probe_data() {
  echo "select 'PROBE ' || (s #>> '{totales,aciertos}') || ' ' || (s #>> '{totales,fallos}') || ' ' || (s #>> '{totales,noContestadas}') || ' ' || coalesce(s #>> '{notaMedia,valor}', 'null') || ' ' || (s ->> 'preguntasValidas') from (select public.app_stats_summary('a0000000-0000-0000-0000-00000000f271') as s) x;" \
    | pa -At 2>"$TMPD/probe.err" | grep -E '^PROBE ' | tail -1
}
probe() {
  pa -At <<'SQL' 2>"$TMPD/probe.err" | grep -E '^PROBE ' | tail -1
begin;
create function pg_temp.ans(u uuid, a uuid, n int, ok boolean) returns jsonb language sql as $f$
  select public.app_record_attempt_answer(u, a, ('f8000000-0000-0000-0000-0000000f27' || lpad(n::text, 2, '0'))::uuid,
                                          ('0e800000-0000-0000-000' || case when ok then 1 else 2 end || '-0000000f27' || lpad(n::text, 2, '0'))::uuid, 1500, 0, null) $f$;
create temp table pr (a uuid);
do $d$
declare
  ub constant uuid := 'a0000000-0000-0000-0000-00000000f272';
  a uuid;
begin
  a := (public.app_start_exam_attempt(ub, 'e5000000-0000-0000-0000-00000000f272', 'DEMO') ->> 'intento_id')::uuid;
  perform pg_temp.ans(ub, a, 12, true);
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values (ub, 'e5000000-0000-0000-0000-00000000f272', 'admin', now() - interval '1 hour', now() + interval '5 days');
  a := (public.app_start_exam_attempt(ub, 'e5000000-0000-0000-0000-00000000f272', 'NORMAL') ->> 'intento_id')::uuid;
  perform pg_temp.ans(ub, a, 12, true);
  insert into pr values (a);
end
$d$;
select 'PROBE ' || (s #>> '{totales,aciertos}') || ' ' || (s ->> 'preguntasValidas') || ' ' || (select ie.cuenta_para_ranking::text from public.intentos_examen ie join pr on pr.a = ie.id)
  from (select public.app_stats_summary('a0000000-0000-0000-0000-00000000f272') as s) x;
rollback;
SQL
}
# trabajos V1 de dificultad y ranking (los que el DailyChallengeJob de Ktor llama en cada vuelta), forzados, en transaccion deshecha; y sobrecargas del reto
probe_jobs() {
  pa -At <<'SQL' 2>"$TMPD/jobs.err" | grep -E '^JOBS ' | tail -1
begin;
create function pg_temp.try_job(f text) returns text language plpgsql as $f$
declare r text;
begin
  execute format('select public.%I(true) ->> ''estado''', f) into r;
  return r;
exception when others then
  return 'ERR:' || sqlstate;
end
$f$;
select 'JOBS ' || pg_temp.try_job('app_job_dificultad') || ' ' || pg_temp.try_job('app_job_ranking') || ' '
       || (select string_agg(p.oid::regprocedure::text, ',' order by p.oid::regprocedure::text) from pg_proc p where p.proname = 'app_answer_daily_challenge');
rollback;
SQL
}
seed_sql | pa >/dev/null 2>"$TMPD/seed.err" || { cat "$TMPD/seed.err"; fail "datos de partida"; }
fingerprint "$OUT/fp_start.txt"
dbefore="$(probe_data)"; [ "$dbefore" = "PROBE 0 0 0 null 0" ] || { cat "$TMPD/probe.err"; fail "datos ANTES: '$dbefore'"; }
say "ok:   datos ANTES (reglas de produccion): $dbefore"
before="$(probe)"; [ "$before" = "PROBE 0 0 false" ] || { cat "$TMPD/probe.err"; fail "sonda ANTES: '$before'"; }
say "ok:   sonda F27 ANTES (forma de produccion: la demo quema, ningun intento cuenta para ranking): $before"
jbefore="$(probe_jobs)"
[ "$jbefore" = "JOBS ERR:42703 ERR:42703 app_answer_daily_challenge(uuid,uuid,uuid,uuid)" ] || { cat "$TMPD/jobs.err"; fail "sonda de trabajos ANTES: '$jbefore'"; }
say "ok:   sonda de trabajos ANTES (forma de MAIN: dificultad y ranking fallan por columna inexistente, 42703): $jbefore"

export REHEARSAL_CONTAINER=$C MANIFEST
W=ops/deploy/v1/apply-main-migration.sh
files="$(for v in $NEW; do ls supabase/migrations/${v}_*.sql; done)"
first="$(echo "$files" | head -1)"

# ---- negativas (no conectan o no cambian nada) ----
out="$(echo x | bash $W supabase/migrations/20261108100500_*.sql 2>&1)"; echo "$out" | grep -q "no esta en el manifiesto" || { echo "$out"; fail "negativa: version fuera del manifiesto"; }
pred="$(tr -d '\r' < "$MANIFEST" | grep -Ev '^[[:space:]]*(#|$)' | awk 'NR==1 {print $3}')"
echo "delete from supabase_migrations.schema_migrations where version = '$pred';" | p >/dev/null
out="$(echo "$(basename "$first" | cut -c1-14)" | bash $W "$first" 2>&1)"; echo "$out" | grep -q "predecesora" || { echo "$out"; fail "negativa: predecesora ausente"; }
reg "$(basename "$(ls supabase/migrations/${pred}_*.sql)" .sql)"
second="$(echo "$files" | sed -n 2p)"
out="$(echo "$(basename "$second" | cut -c1-14)" | bash $W "$second" 2>&1)"; echo "$out" | grep -q "predecesora" || { echo "$out"; fail "negativa: saltarse el orden (segunda antes que la primera)"; }
fingerprint "$TMPD/fp_neg.txt"; cmp -s "$OUT/fp_start.txt" "$TMPD/fp_neg.txt" || fail "una negativa cambio el catalogo"
say "ok:   negativas: version fuera del manifiesto = STOP; predecesora $pred ausente = STOP; saltarse el orden = STOP; catalogo intacto"

# ---- ventana: apply-main-migration.sh, en el orden del manifiesto ----
for f in $files; do
  v="$(basename "$f" | cut -c1-14)"
  echo "$v" | bash $W "$f" > "$OUT/apply_$v.log" 2>&1 || { tail -15 "$OUT/apply_$v.log"; fail "$v no aplica con el script"; }
  grep -q "aplicada, VERIFY = 0, registrada" "$OUT/apply_$v.log" || fail "$v sin 'aplicada, VERIFY = 0, registrada'"
  say "ok:   $v: $(grep -oE 'aplicada en [0-9]+ s' "$OUT/apply_$v.log"); VERIFY = 0 ($(grep -c 'VERIFY |' "$OUT/apply_$v.log") filas); registrada"
done
out="$(echo "$(basename "$first" | cut -c1-14)" | bash $W "$first" 2>&1)"; echo "$out" | grep -q "ya figura en el historial" || { echo "$out"; fail "re-aplicar no se detiene"; }
say "ok:   re-aplicar una ya registrada = STOP"
dafter="$(probe_data)"; [ "$dafter" = "PROBE 1 0 0 7.33 5" ] || { cat "$TMPD/probe.err"; fail "datos DESPUES: '$dafter'"; }
say "ok:   datos DESPUES (relleno F27: q11 cuenta; ranking reconstruido con la penalizacion congelada): $dafter"
after="$(probe)"; [ "$after" = "PROBE 1 1 true" ] || { cat "$TMPD/probe.err"; fail "sonda DESPUES: '$after'"; }
say "ok:   sonda F27 DESPUES (la demo no quema; el primer intento real cuenta para ranking): $after"
jafter="$(probe_jobs)"
[ "$jafter" = "JOBS OK OK app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean)" ] || { cat "$TMPD/jobs.err"; fail "sonda de trabajos DESPUES: '$jafter'"; }
say "ok:   sonda de trabajos DESPUES (dificultad y ranking OK; una sola sobrecarga del reto, la de 6 argumentos): $jafter"
fingerprint "$OUT/fp_applied.txt"
say "ok:   cambios de catalogo: $(diff "$OUT/fp_start.txt" "$OUT/fp_applied.txt" | grep -c '^[<>]') lineas (fp_start.txt vs fp_applied.txt)"

# ---- ROLLBACK inverso -> huella == punto de partida; re-aplicar -> huella == primera aplicacion ----
for f in $(echo "$files" | sort -r); do
  block "$f" ROLLBACK | p >/dev/null 2>"$TMPD/err" || { cat "$TMPD/err"; fail "ROLLBACK de $(basename "$f")"; }
  echo "delete from supabase_migrations.schema_migrations where version = '$(basename "$f" | cut -c1-14)';" | p >/dev/null
done
fingerprint "$OUT/fp_rolledback.txt"
diff "$OUT/fp_start.txt" "$OUT/fp_rolledback.txt" > "$OUT/rollback_diff.txt" || { head -20 "$OUT/rollback_diff.txt"; fail "el ROLLBACK no devuelve el punto de partida"; }
back="$(probe)"; [ "$back" = "$before" ] || fail "sonda tras ROLLBACK: '$back'"
jback="$(probe_jobs)"; [ "$jback" = "$jbefore" ] || fail "sonda de trabajos tras ROLLBACK: '$jback'"
dback="$(probe_data)"; [ "$dback" = "$dafter" ] || fail "datos tras ROLLBACK: '$dback' (los contadores rellenados son datos y se quedan)"
say "ok:   ROLLBACK inverso: huella == punto de partida (cuerpos de funcion incluidos, md5(prosrc)); sondas = ANTES"
for f in $files; do v="$(basename "$f" | cut -c1-14)"; echo "$v" | bash $W "$f" > "$OUT/reapply_$v.log" 2>&1 || { tail -15 "$OUT/reapply_$v.log"; fail "re-aplicar $v"; }; done
fingerprint "$OUT/fp_reapplied.txt"; cmp -s "$OUT/fp_applied.txt" "$OUT/fp_reapplied.txt" || fail "re-aplicar no da la misma huella"
say "ok:   re-aplicacion con el script: VERIFY = 0 y huella == primera aplicacion"
say "V1-3-2026-11-REHEARSAL OK"
