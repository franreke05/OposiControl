#!/usr/bin/env bash
# Ensayo LOCAL del HOTFIX HF1 (20261115000000_v1_servicios_seccion_permiso, hf/servicios-seccion): sobre un punto de partida con la forma de MAIN de HOY
# (captura de MAIN 2026-10-14 + ventana V1 2026-10-22 + ventana servidor 2026-11 + F20 + las 11 de la ventana 1.3, todas aplicadas y registradas, igual que
# docs/ledger/2026/11/rehearse-v1-3-2026-11.sh), aplica la migracion con el MISMO script y su manifiesto. NO toca MAIN ni la red: contenedor desechable
# `hf1-2026-11-rehearsal` (PG17 17.6.1.171), sin puerto.
#   MANIFEST=ops/deploy/v1/main-migrations-2026-11-hf1.manifest ops/deploy/v1/apply-main-migration.sh (REHEARSAL_CONTAINER), version tecleada.
# Comprueba: ANTES, el guardado de Control como service_role (rol de PostgREST con la clave de servicio de Ktor) FALLA con el 42501 de produccion
# ("permission denied for function servicio_seccion_por_tipo"); negativas del script; aplicada + VERIFY = 0 + registrada; DESPUES la matriz de escrituras
# de Control (insert sin seccion / panel viejo sin visible ni posicion, upsert con seccion null explicito, seccion explicita, update a null, update de otro
# campo, upsert sobre fila existente) funciona como service_role, igual que el PATCH de Control > Configuracion (mismo defecto en
# trg_configuracion_v1_bounds), y el cliente sigue sin poder ejecutar los helpers; la consulta de barrido del acta (disparadores de public.* con funcion
# NO definer que llaman a una funcion que service_role no puede ejecutar) da la lista de produccion ANTES (esperada: trg_servicios_seccion,
# trg_configuracion_v1_bounds, trg_soluciones_markup, trg_staging_markup) y vacia DESPUES; ROLLBACK -> huella == punto de partida y vuelven los 42501;
# re-aplicar -> huella == primera aplicacion.
# Uso (Git Bash, desde la raiz del arbol): bash docs/ledger/2026/11/rehearse-hf1-2026-11.sh <directorio de salida>
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 2
OUT="${1:?directorio de salida}"; mkdir -p "$OUT"; LOG="$OUT/rehearsal.log"; : > "$LOG"
C=hf1-2026-11-rehearsal
IMG="public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9" # 17.6.1.171
MANIFEST=ops/deploy/v1/main-migrations-2026-11-hf1.manifest
HF=supabase/migrations/20261115000000_v1_servicios_seccion_permiso.sql
BASE="${MAIN_BASELINE:-/c/Users/franr/.oposibots-gate/baseline-20261014/baseline_main.sql}"
BASE_SHA=07acead5e0598df501aabd83e1e281d8776e653bea846e98932f233021354a46
VIEWS="${MAIN_VIEWS:-/c/Users/franr/.oposibots-gate/baseline/06_views.sql}"
PRE="20261023100000 20261023100300 20261023100400 20261023100500"; NEVER="20261023100100"
say() { echo "$*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; exit 1; }
[ "$(sha256sum "$BASE" | cut -c1-64)" = "$BASE_SHA" ] || fail "captura de MAIN inesperada ($BASE)"
versions() { tr -d '\r' < "$1" | grep -Ev '^[[:space:]]*(#|$)' | awk '{print $1}'; }
NEW="$(versions "$MANIFEST")"
[ "$NEW" = 20261115000000 ] || fail "el manifiesto HF1 debe tener solo 20261115000000"
in_list() { local v; for v in $2; do [ "${1%%_*}" = "$v" ] && return 0; done; return 1; }

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

# ---- punto de partida: forma de MAIN de hoy (igual que rehearse-v1-3-2026-11.sh, mas las 11 de la ventana 1.3 ya desplegadas) ----
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
declare -A PATHOF
for m in $(tr -d '\r' < supabase/baseline/PENDING_MIGRATIONS.txt | grep -Ev '^[[:space:]]*(#|$)'); do PATHOF[$m]="supabase/migrations/$m.sql"; done
for f in supabase/migrations/20261020100000_*.sql supabase/migrations/20261021100000_*.sql supabase/migrations/20261023*_security_*.sql; do PATHOF[$(basename "$f" .sql)]="$f"; done
n=0
for m in $(printf '%s\n' "${!PATHOF[@]}" | sort); do
  in_list "$m" "$NEW $PRE $NEVER" && continue
  p < "${PATHOF[$m]}" >/dev/null 2>"$TMPD/err" || { grep -E 'ERROR|HINT' "$TMPD/err"; fail "punto de partida: $m no aplica"; }
  reg "$m"; n=$((n + 1))
done
last="$(p -At -c "select max(version) from supabase_migrations.schema_migrations")"
[ "$last" = 20261114500000 ] || fail "punto de partida: la ultima version registrada es $last (se esperaba 20261114500000, MAIN de hoy)"
say "ok:   punto de partida = MAIN de hoy: $n migraciones posteriores a la captura aplicadas y registradas; ultima $last"
# La captura de MAIN (2026-10-14) no trae ACL de esquemas (solo CREATE SCHEMA). En MAIN service_role SI tiene USAGE en private: lo concede
# 20260928100000 (linea 251; en MAIN_MIGRATION_HISTORY.txt) y el error de produccion nombra la FUNCION, no el esquema (sin USAGE seria
# "permission denied for schema private"). Se repone para que el punto de partida sea el de produccion.
[ "$(p -At -c "select has_schema_privilege('service_role', 'private', 'usage')")" = f ] || fail "la captura ya trae USAGE de private para service_role: revisar este paso"
tr -d '\r' < supabase/baseline/MAIN_MIGRATION_HISTORY.txt | grep -q '^20260928100000 ' || fail "20260928100000 no esta en el historial de MAIN"
grep -q '^grant usage on schema private to service_role;' supabase/migrations/20260928100000_identity_canonical_profile_expand.sql || fail "20260928100000 ya no concede USAGE de private"
p -c "grant usage on schema private to service_role" >/dev/null || fail "USAGE de private"
say "ok:   service_role USAGE en el esquema private repuesto como en MAIN (20260928100000; la captura no trae ACL de esquemas)"

fingerprint() { # misma huella que rehearse-v1-3-2026-11.sh
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
# Consulta de barrido del acta (docs/ledger/2026/11/2026-11-15-hf1-servicios-seccion.md), copia literal entre SWEEP-BEGIN/END del acta.
ACTA=docs/ledger/2026/11/2026-11-15-hf1-servicios-seccion.md
sed -n '/^-- SWEEP-BEGIN/,/^-- SWEEP-END/p' "$ACTA" | grep -v '^-- SWEEP-' > "$TMPD/sweep.sql"
[ -s "$TMPD/sweep.sql" ] || fail "consulta de barrido ausente del acta"
sweep() { p -At -F '|' < "$TMPD/sweep.sql" 2>&1 || fail "la consulta de barrido no se ejecuta"; }
# El guardado de Control tal cual lo hace PostgREST con la clave de servicio: `set role service_role`, INSERT de una fila sin seccion/visible/posicion.
probe() {
  pa -At 2>&1 <<'SQL' | grep -oE 'PROBE .*' | tail -1
begin;
set local role service_role;
do $d$
declare v text;
begin
  insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('hf1_probe', 'Tutoria', 'tutoria', 5) returning seccion into v;
  raise notice 'PROBE OK %', v;
exception when others then
  raise notice 'PROBE ERR:% %', sqlstate, sqlerrm;
end
$d$;
rollback;
SQL
}
# La matriz completa de escrituras de Control como service_role (DESPUES), deshecha.
probe_matrix() {
  pa -At 2>&1 <<'SQL' | grep -oE 'MATRIX .*' | tail -1
begin;
set local role service_role;
do $d$
declare r text; id1 uuid := gen_random_uuid();
begin
  -- 1. panel viejo: ni seccion, ni visible, ni posicion
  insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('hf1_a', 'A', 'modo_papel', 5);
  -- 2. el upsert de Control (merge-duplicates): seccion null explicito; y otra vez sobre la MISMA fila (rama ON CONFLICT DO UPDATE)
  insert into public.servicios (id, codigo, nombre, tipo_servicio, precio_tickets, seccion, usos, duracion_horas, multiplicador)
    values (id1, 'hf1_b', 'B', 'potenciador', 10, null, 1, null, 3)
    on conflict (id) do update set nombre = excluded.nombre, precio_tickets = excluded.precio_tickets, seccion = excluded.seccion;
  insert into public.servicios (id, codigo, nombre, tipo_servicio, precio_tickets, seccion)
    values (id1, 'hf1_b', 'B2', 'potenciador', 12, null)
    on conflict (id) do update set nombre = excluded.nombre, precio_tickets = excluded.precio_tickets, seccion = excluded.seccion;
  -- 3. seccion explicita: se respeta
  insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, seccion) values ('hf1_c', 'C', 'premium', 5, 'OPOSITICKETS');
  -- 4. update a null: se re-deriva; 5. update de otro campo: la seccion queda
  update public.servicios set seccion = null where codigo = 'hf1_c';
  update public.servicios set precio_tickets = 7 where codigo = 'hf1_a';
  select string_agg(codigo || ':' || seccion || ':' || visible || ':' || posicion || ':' || precio_tickets, ',' order by codigo) into r
    from public.servicios where codigo like 'hf1\_%';
  raise notice 'MATRIX OK %', r;
exception when others then
  raise notice 'MATRIX ERR:% %', sqlstate, sqlerrm;
end
$d$;
rollback;
SQL
}
# Control > Configuracion (AdminControlConfig: PATCH /rest/v1/configuracion_v1) como service_role: reescribe una clave con su mismo valor, deshecho.
probe_cfg() {
  pa -At 2>&1 <<'SQL' | grep -oE 'CFG .*' | tail -1
begin;
set local role service_role;
do $d$
begin
  update public.configuracion_v1 set valor_integer = valor_integer where clave = (select min(clave) from public.configuracion_v1 where clave <> 'zona_horaria');
  raise notice 'CFG OK';
exception when others then
  raise notice 'CFG ERR:% %', sqlstate, sqlerrm;
end
$d$;
rollback;
SQL
}
fingerprint "$OUT/fp_start.txt"
before="$(probe)"
[ "$before" = "PROBE ERR:42501 permission denied for function servicio_seccion_por_tipo" ] || fail "sonda ANTES: '$before' (se esperaba el 42501 de produccion)"
say "ok:   sonda ANTES (forma de MAIN de hoy, service_role): $before  <- el error del log de Ktor"
SWEEP_EXPECT="configuracion_v1|trg_configuracion_v1_bounds|private.tg_configuracion_v1_bounds()|private.config_v1_range(text)
preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_plain(jsonb)
preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_valid(jsonb)
servicios|trg_servicios_seccion|private.tg_servicios_seccion()|private.servicio_seccion_por_tipo(text)
soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_plain(jsonb)
soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_valid(jsonb)"
cfgb="$(probe_cfg)"; [ "$cfgb" = "CFG ERR:42501 permission denied for function config_v1_range" ] || fail "sonda de configuracion ANTES: '$cfgb'"
say "ok:   sonda de configuracion ANTES (service_role): $cfgb  <- mismo defecto, Control > Configuracion"
sweep > "$OUT/sweep_before.txt"
[ "$(cut -d'|' -f1-4 "$OUT/sweep_before.txt" | sed 's/^public.//')" = "$SWEEP_EXPECT" ]   || { cat "$OUT/sweep_before.txt"; fail "barrido ANTES: se esperaban exactamente los 4 disparadores (6 llamadas)"; }
say "ok:   barrido ANTES (todos los disparadores de public.* del MAIN de hoy): $(wc -l < "$OUT/sweep_before.txt" | tr -d ' ') filas:"
while IFS= read -r l; do say "        $l"; done < "$OUT/sweep_before.txt"

export REHEARSAL_CONTAINER=$C MANIFEST
W=ops/deploy/v1/apply-main-migration.sh
# ---- negativas ----
out="$(echo x | bash $W supabase/migrations/20261114500000_*.sql 2>&1)"; echo "$out" | grep -q "no esta en el manifiesto" || { echo "$out"; fail "negativa: version fuera del manifiesto"; }
echo "delete from supabase_migrations.schema_migrations where version = '20261114500000';" | p >/dev/null
out="$(echo 20261115000000 | bash $W "$HF" 2>&1)"; echo "$out" | grep -q "predecesora" || { echo "$out"; fail "negativa: predecesora ausente"; }
reg 20261114500000_v1_ranking_lotes_shape
fingerprint "$TMPD/fp_neg.txt"; cmp -s "$OUT/fp_start.txt" "$TMPD/fp_neg.txt" || fail "una negativa cambio el catalogo"
say "ok:   negativas: version fuera del manifiesto = STOP; predecesora 20261114500000 ausente = STOP; catalogo intacto"

# ---- aplicar con el script ----
echo 20261115000000 | bash $W "$HF" > "$OUT/apply_20261115000000.log" 2>&1 || { tail -15 "$OUT/apply_20261115000000.log"; fail "no aplica con el script"; }
grep -q "aplicada, VERIFY = 0, registrada" "$OUT/apply_20261115000000.log" || fail "sin 'aplicada, VERIFY = 0, registrada'"
say "ok:   20261115000000: $(grep -oE 'aplicada en [0-9]+ s' "$OUT/apply_20261115000000.log"); VERIFY = 0 ($(grep -c 'VERIFY |' "$OUT/apply_20261115000000.log") filas); registrada"
out="$(echo 20261115000000 | bash $W "$HF" 2>&1)"; echo "$out" | grep -q "ya figura en el historial" || { echo "$out"; fail "re-aplicar no se detiene"; }
say "ok:   re-aplicar una ya registrada = STOP"
after="$(probe)"; [ "$after" = "PROBE OK SERVICIOS" ] || fail "sonda DESPUES: '$after'"
say "ok:   sonda DESPUES (service_role): $after"
matrix="$(probe_matrix)"
[ "$matrix" = "MATRIX OK hf1_a:SERVICIOS:true:0:7,hf1_b:POTENCIADORES:true:0:12,hf1_c:SERVICIOS:true:0:5" ] || fail "matriz DESPUES: '$matrix'"
say "ok:   matriz de escrituras de Control DESPUES (service_role): $matrix"
cfga="$(probe_cfg)"; [ "$cfga" = "CFG OK" ] || fail "sonda de configuracion DESPUES: '$cfga'"
say "ok:   sonda de configuracion DESPUES (service_role): $cfga"
cl="$(p -At -c "select count(*) from unnest(array['private.servicio_seccion_por_tipo(text)', 'private.config_v1_range(text)', 'private.markup_valid(jsonb)', 'private.markup_plain(jsonb)', 'private.tg_servicios_seccion()', 'private.tg_configuracion_v1_bounds()', 'private.tg_soluciones_markup()', 'private.tg_staging_markup()']::regprocedure[]) f where has_function_privilege('anon', f, 'execute') or has_function_privilege('authenticated', f, 'execute')")"
[ "$cl" = 0 ] || fail "un rol cliente puede ejecutar un helper o una funcion de disparador ($cl)"
say "ok:   anon/authenticated siguen sin EXECUTE sobre los 4 helpers y las 4 funciones de disparador"
sweep > "$OUT/sweep_after.txt"; [ ! -s "$OUT/sweep_after.txt" ] || { cat "$OUT/sweep_after.txt"; fail "barrido DESPUES no vacio"; }
say "ok:   barrido DESPUES: 0 filas"
fingerprint "$OUT/fp_applied.txt"
diff "$OUT/fp_start.txt" "$OUT/fp_applied.txt" > "$OUT/apply_diff.txt"
[ "$(grep -c '^[<>]' "$OUT/apply_diff.txt")" = 8 ] \
  && [ "$(grep -cE '^> fn:private\.tg_(servicios_seccion|configuracion_v1_bounds|soluciones_markup|staging_markup)\(\):true:' "$OUT/apply_diff.txt")" = 4 ] \
  && [ "$(sed -n 's/^< \(fn:[^:]*\):false:/\1:/p' "$OUT/apply_diff.txt" | sort)" = "$(sed -n 's/^> \(fn:[^:]*\):true:/\1:/p' "$OUT/apply_diff.txt" | sort)" ] \
  || { cat "$OUT/apply_diff.txt"; fail "la migracion cambia algo mas que prosecdef de las 4 funciones de disparador"; }
say "ok:   cambio de catalogo: solo las 4 funciones de disparador pasan a SECURITY DEFINER (mismo cuerpo md5, search_path y ACL)"

# ---- ROLLBACK -> huella == punto de partida (y vuelve el 42501); re-aplicar -> huella == primera aplicacion ----
block "$HF" ROLLBACK | p >/dev/null 2>"$TMPD/err" || { cat "$TMPD/err"; fail "ROLLBACK"; }
echo "delete from supabase_migrations.schema_migrations where version = '20261115000000';" | p >/dev/null
fingerprint "$OUT/fp_rolledback.txt"
diff "$OUT/fp_start.txt" "$OUT/fp_rolledback.txt" > "$OUT/rollback_diff.txt" || { head -20 "$OUT/rollback_diff.txt"; fail "el ROLLBACK no devuelve el punto de partida"; }
back="$(probe)"; [ "$back" = "$before" ] || fail "sonda tras ROLLBACK: '$back'"
[ "$(probe_cfg)" = "$cfgb" ] || fail "sonda de configuracion tras ROLLBACK"
say "ok:   ROLLBACK: huella == punto de partida; sonda = ANTES ($back)"
echo 20261115000000 | bash $W "$HF" > "$OUT/reapply_20261115000000.log" 2>&1 || { tail -15 "$OUT/reapply_20261115000000.log"; fail "re-aplicar"; }
fingerprint "$OUT/fp_reapplied.txt"; cmp -s "$OUT/fp_applied.txt" "$OUT/fp_reapplied.txt" || fail "re-aplicar no da la misma huella"
[ "$(probe)" = "PROBE OK SERVICIOS" ] || fail "sonda tras re-aplicar"
say "ok:   re-aplicacion con el script: VERIFY = 0, huella == primera aplicacion, sonda OK"
say "HF1-2026-11-REHEARSAL OK"
