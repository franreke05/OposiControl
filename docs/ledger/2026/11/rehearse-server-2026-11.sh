#!/usr/bin/env bash
# Ensayo LOCAL de la ventana de migraciones del despliegue servidor 2026-11 (runbook 2026-11-08-server-deploy-runbook.md).
# NO toca MAIN ni la red: contenedor propio y desechable `server-2026-11-rehearsal` (PG17 Supabase 17.6.1.171 fijado por el mismo digest
# que el gate), sin puerto publicado; se borra por nombre al terminar.
#
# Punto de partida = MAIN despues del lanzamiento V1 (2026-10-22), reconstruido como en run-2026-10-22b/window-selftest.sh:
#   captura de catalogo de MAIN 2026-10-14 + vistas + auth.users GoTrue + stub de storage.objects + semilla sintetica; historial = las 77
#   versiones de supabase/baseline/MAIN_MIGRATION_HISTORY.txt; 20261023100000/100300/100400/100500 aplicadas antes de la ventana; RB-16
#   (14 politicas de escritura de storage) borrado a mano; despues las migraciones de la ventana V1 (todas las de PENDING menos las de este
#   despliegue, + 20261020/21 + 20261023*_security_* menos 100100) en orden de version, cada una registrada en el historial.
# Ventana ensayada con el MISMO script y manifiesto que usara el propietario:
#   MANIFEST=ops/deploy/v1/main-migrations-2026-11.manifest ops/deploy/v1/apply-main-migration.sh (REHEARSAL_CONTAINER), version tecleada.
# Comprueba: negativas del manifiesto (version fuera, predecesora ausente), aplicar + VERIFY = 0 + registro de cada una, re-aplicar = STOP,
# ROLLBACK en orden inverso con huella == punto de partida, re-aplicar con huella == primera aplicacion.
# Uso (Git Bash, desde la raiz del arbol a ensayar): bash docs/ledger/2026/11/rehearse-server-2026-11.sh <directorio de salida>
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 2
OUT="${1:?directorio de salida}"; mkdir -p "$OUT"; LOG="$OUT/rehearsal.log"; : > "$LOG"
C=server-2026-11-rehearsal
IMG="public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9" # 17.6.1.171
MANIFEST=ops/deploy/v1/main-migrations-2026-11.manifest
BASE="${MAIN_BASELINE:-/c/Users/franr/.oposibots-gate/baseline-20261014/baseline_main.sql}"
BASE_SHA=07acead5e0598df501aabd83e1e281d8776e653bea846e98932f233021354a46
VIEWS="${MAIN_VIEWS:-/c/Users/franr/.oposibots-gate/baseline/06_views.sql}"
PRE="20261023100000 20261023100300 20261023100400 20261023100500"; NEVER="20261023100100"
say() { echo "$*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; exit 1; }
[ "$(sha256sum "$BASE" | cut -c1-64)" = "$BASE_SHA" ] || fail "captura de MAIN inesperada ($BASE)"
NEW="$(tr -d '\r' < "$MANIFEST" | grep -Ev '^[[:space:]]*(#|$)' | awk '{print $1}')"
is_new() { printf '%s\n' "$NEW" | grep -qx "${1%%_*}"; }
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

# ---- punto de partida: forma de MAIN (igual que rehearse-v1-deploy.sh) ----
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
# ventana V1 (2026-10-22) en orden de version, sin las de este despliegue
declare -A PATHOF
for m in $(tr -d '\r' < supabase/baseline/PENDING_MIGRATIONS.txt | grep -Ev '^[[:space:]]*(#|$)'); do PATHOF[$m]="supabase/migrations/$m.sql"; done
for f in supabase/migrations/20261020100000_*.sql supabase/migrations/20261021100000_*.sql supabase/migrations/20261023*_security_*.sql; do PATHOF[$(basename "$f" .sql)]="$f"; done
n=0
for m in $(printf '%s\n' "${!PATHOF[@]}" | sort); do
  is_new "$m" && continue; in_list "$m" "$PRE $NEVER" && continue
  [ "${m%%_*}" = 20261023100100 ] && continue
  p < "${PATHOF[$m]}" >/dev/null 2>"$TMPD/err" || { grep -E 'ERROR|HINT' "$TMPD/err"; fail "ventana V1: $m no aplica"; }
  reg "$m"; n=$((n + 1))
done
say "ok:   punto de partida = MAIN tras V1: $n migraciones de la ventana V1 aplicadas y registradas; historial $(p -At -c "select count(*) || ' versiones, ultima ' || max(version) from supabase_migrations.schema_migrations")"

fingerprint() { # misma huella que rehearse-v1-deploy.sh (public/private/storage por nombre)
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
fingerprint "$OUT/fp_start.txt"

export REHEARSAL_CONTAINER=$C MANIFEST
W=ops/deploy/v1/apply-main-migration.sh
files="$(for v in $NEW; do ls supabase/migrations/${v}_*.sql; done)"
first="$(echo "$files" | head -1)"; second="$(echo "$files" | sed -n 2p)"

# ---- negativas (no conectan o no cambian nada) ----
out="$(echo x | bash $W supabase/migrations/20261023100600_*.sql 2>&1)"; echo "$out" | grep -q "no esta en el manifiesto" || { echo "$out"; fail "negativa: version fuera del manifiesto"; }
out="$(echo "$(basename "$second" | cut -c1-14)" | bash $W "$second" 2>&1)"; echo "$out" | grep -q "predecesora" || { echo "$out"; fail "negativa: predecesora ausente"; }
fingerprint "$TMPD/fp_neg.txt"; cmp -s "$OUT/fp_start.txt" "$TMPD/fp_neg.txt" || fail "una negativa cambio el catalogo"
say "ok:   negativas: version fuera del manifiesto = STOP; predecesora ausente = STOP; catalogo intacto"

# ---- ventana: apply-main-migration.sh una a una ----
for f in $files; do
  v="$(basename "$f" | cut -c1-14)"
  echo "$v" | bash $W "$f" > "$OUT/apply_$v.log" 2>&1 || { cat "$OUT/apply_$v.log" | tail -15; fail "$v no aplica con el script"; }
  grep -q "aplicada, VERIFY = 0, registrada" "$OUT/apply_$v.log" || fail "$v sin 'aplicada, VERIFY = 0, registrada'"
  say "ok:   $v: $(grep -oE 'aplicada en [0-9]+ s' "$OUT/apply_$v.log"); VERIFY = 0 ($(grep -c 'VERIFY |' "$OUT/apply_$v.log") filas); registrada"
done
out="$(echo "$(basename "$first" | cut -c1-14)" | bash $W "$first" 2>&1)"; echo "$out" | grep -q "ya figura en el historial" || { echo "$out"; fail "re-aplicar no se detiene"; }
say "ok:   re-aplicar una ya registrada = STOP"
fingerprint "$OUT/fp_applied.txt"
say "ok:   cambios de catalogo de la ventana: $(diff "$OUT/fp_start.txt" "$OUT/fp_applied.txt" | grep -c '^[<>]') lineas (fp_start.txt vs fp_applied.txt)"

# ---- ROLLBACK en orden inverso -> huella == punto de partida; re-aplicar -> huella == primera aplicacion ----
for f in $(echo "$files" | sort -r); do
  block "$f" ROLLBACK | p >/dev/null 2>"$TMPD/err" || { cat "$TMPD/err"; fail "ROLLBACK de $(basename "$f")"; }
  echo "delete from supabase_migrations.schema_migrations where version = '$(basename "$f" | cut -c1-14)';" | p >/dev/null
done
fingerprint "$OUT/fp_rolledback.txt"
diff "$OUT/fp_start.txt" "$OUT/fp_rolledback.txt" > "$OUT/rollback_diff.txt" || { head -20 "$OUT/rollback_diff.txt"; fail "el ROLLBACK no devuelve el punto de partida"; }
say "ok:   ROLLBACK inverso de $(echo "$files" | wc -l | tr -d ' ') migraciones: huella == punto de partida (cuerpos de funcion incluidos, md5(prosrc))"
for f in $files; do v="$(basename "$f" | cut -c1-14)"; echo "$v" | bash $W "$f" > "$OUT/reapply_$v.log" 2>&1 || fail "re-aplicar $v"; done
fingerprint "$OUT/fp_reapplied.txt"; cmp -s "$OUT/fp_applied.txt" "$OUT/fp_reapplied.txt" || fail "re-aplicar no da la misma huella"
say "ok:   re-aplicacion con el script: VERIFY = 0 y huella == primera aplicacion"
say "SERVER-2026-11-REHEARSAL OK"
