#!/usr/bin/env bash
# Prueba de apply-main-migration.sh (--verify-only / --record-only / negativas) contra un contenedor propio con el estado de MAIN 2026-10-22.
set -uo pipefail
REPO=/c/Users/franr/AppData/Local/Temp/claude/reh/tree3
C=v1-deploy-rehearsal-wrapper
IMG=public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9
cd "$REPO" || exit 2
docker rm -f $C >/dev/null 2>&1
docker run -d --name $C --memory 768m --cpus 1 -e POSTGRES_PASSWORD=postgres $IMG >/dev/null || exit 1
trap 'docker rm -f '$C' >/dev/null 2>&1' EXIT
for i in $(seq 1 90); do [ "$(docker exec $C psql -U postgres -h localhost -At -c "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] && break; sleep 2; done
p() { docker exec -i $C psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
pa() { docker exec -i $C psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
p -1 < /c/Users/franr/AppData/Local/Temp/claude/reh/boot_full2.sql >/dev/null 2>&1 || { echo "boot falla"; exit 1; }
pa < supabase/baseline/main_shape_auth_users.sql >/dev/null 2>&1
pa < supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql >/dev/null 2>&1
pa -c "create schema if not exists supabase_migrations; create table if not exists supabase_migrations.schema_migrations (version text primary key, statements text[], name text); grant usage on schema supabase_migrations to postgres; grant all on supabase_migrations.schema_migrations to postgres;" >/dev/null
# Estado de MAIN 2026-10-22: las 4 aplicadas como SQL suelto (sin fila de historial) y RB-16 a mano.
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do p < supabase/migrations/${v}_*.sql >/dev/null 2>&1 || { echo "no carga $v"; exit 1; }; done
pa -c "do \$\$ declare r record; begin for r in select polname from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*') and (coalesce(pg_get_expr(polqual, polrelid),'') ~ '(es_admin|can_manage_content)\\(' or coalesce(pg_get_expr(polwithcheck, polrelid),'') ~ '(es_admin|can_manage_content)\\(') loop execute format('drop policy %I on storage.objects', r.polname); end loop; end \$\$;" >/dev/null
echo "== estado: historial = $(p -At -c 'select count(*) from supabase_migrations.schema_migrations') filas; politicas de escritura admin en storage = $(p -At -c "select count(*) from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*')")"
export REHEARSAL_CONTAINER=$C
W=ops/deploy/v1/apply-main-migration.sh
run() { echo; echo "\$ $*"; "$@"; echo "rc=$?"; }
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do run bash $W --verify-only supabase/migrations/${v}_*.sql; done
run bash $W supabase/migrations/20261023100300_security_moderator_requires_active_account.sql
echo; echo '$ echo 20990101000000 | apply-main-migration.sh --record-only ...20261023100000 (version mal tecleada)'; echo 20990101000000 | bash $W --record-only supabase/migrations/20261023100000_security_content_admin_write_revoke.sql; echo "rc=$?"
echo "historial: $(p -At -c 'select count(*) from supabase_migrations.schema_migrations') filas"
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do echo; echo "\$ echo $v | apply-main-migration.sh --record-only ${v}"; echo $v | bash $W --record-only supabase/migrations/${v}_*.sql; echo "rc=$?"; done
echo; echo "\$ echo 20261023100000 | apply-main-migration.sh --record-only (segunda vez)"; echo 20261023100000 | bash $W --record-only supabase/migrations/20261023100000_security_content_admin_write_revoke.sql; echo "rc=$?"
echo; echo "== historial:"; p -At -c 'select version, name from supabase_migrations.schema_migrations order by 1'
run bash $W --record-only supabase/migrations/20261023100200_security_revenue_excludes_sandbox.sql
run bash $W supabase/migrations/20261023100100_security_storage_admin_write_revoke.sql
run bash $W --verify-only supabase/migrations/20261023100100_security_storage_admin_write_revoke.sql
run bash $W --verify-only supabase/migrations/20261023100200_security_revenue_excludes_sandbox.sql
echo; echo "== 00_history_readonly.sql (sin las tablas de MAIN de antes de 20261020: informativo)"; p < docs/engineering/v1-deploy/00_history_readonly.sql 2>&1 | tail -25
echo; echo "== 01_catalog_preflight_readonly.sql (estado 2026-10-22)"; p < docs/engineering/v1-deploy/01_catalog_preflight_readonly.sql 2>&1 | tail -30
