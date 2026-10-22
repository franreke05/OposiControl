#!/usr/bin/env bash
# V1-DEPLOY-REHEARSAL 2026-10-22b · ventana F4 COMPLETA con ops/deploy/v1/apply-main-migration.sh (el mismo script que usara el propietario),
# contra un contenedor propio (v1-deploy-rehearsal-window, borrado por nombre al terminar) con el estado de MAIN de 2026-10-22:
#   historial = las 77 versiones de supabase/baseline/MAIN_MIGRATION_HISTORY.txt; 100000/100300/100400/100500 aplicadas SIN fila de
#   historial; 14 politicas de escritura de storage.objects borradas a mano; 2 examenes oficiales heredados; semilla sintetica.
# Prueba: negativas del manifiesto (version fuera, blob distinto, predecesora ausente, clase), las 40 de la ventana en orden con el
# paso F4.3b (05/06/07, actor no admin rechazado, 08 deshacer + rehacer) justo despues de 103400, --record-only de las 4, y los
# preflight de solo lectura al final (00 historial, 01 catalogo con TRUNCATE).
# Uso: bash window-selftest.sh <arbol con los blobs del manifiesto> <boot_full2.sql>
set -uo pipefail
TREE="${1:?arbol}"; BOOT="${2:?boot}"
C=v1-deploy-rehearsal-window
IMG=public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9
cd "$TREE" || exit 2
docker rm -f $C >/dev/null 2>&1
docker run -d --name $C --memory 1g --cpus 2 -e POSTGRES_PASSWORD=postgres $IMG >/dev/null || exit 1
trap 'docker rm -f '$C' >/dev/null 2>&1; rm -rf "$TMPD"' EXIT
TMPD="$(mktemp -d)"
for i in $(seq 1 90); do [ "$(docker exec $C psql -U postgres -h localhost -At -c "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] && break; sleep 2; done
p() { docker exec -i $C psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
pa() { docker exec -i $C psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
p -1 < "$BOOT" >/dev/null 2>&1 || { echo "FAIL boot"; exit 1; }
pa < supabase/baseline/main_shape_auth_users.sql >/dev/null 2>&1
pa < supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql >/dev/null 2>&1
p < docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/seed_synthetic.sql >/dev/null || { echo "FAIL seed"; exit 1; }
p >/dev/null <<'SQL' || { echo "FAIL oficiales heredados"; exit 1; }
begin; set local session_replication_role = replica;
update public.examenes set es_oficial = true where codigo_examen = 'REH-1';
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
  values ('d4000000-0000-0000-0000-000000000002', 'REH-2', 'd2000000-0000-0000-0000-000000000001', 'd3000000-0000-0000-0000-000000000001',
          'Oficial borrador', 'rafaga', 'gratuito', false, true);
commit;
SQL
pa -c "create schema if not exists supabase_migrations; create table if not exists supabase_migrations.schema_migrations (version text primary key, statements text[], name text); grant usage on schema supabase_migrations to postgres; grant all on supabase_migrations.schema_migrations to postgres;" >/dev/null
tr -d '\r' < supabase/baseline/MAIN_MIGRATION_HISTORY.txt | grep -Ev '^[[:space:]]*(#|$)' | awk '{printf "insert into supabase_migrations.schema_migrations (version, name) values (%c%s%c, %c%s%c);\n", 39, $1, 39, 39, $2, 39}' | p >/dev/null
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do p < supabase/migrations/${v}_*.sql >/dev/null 2>&1 || { echo "FAIL preaplicada $v"; exit 1; }; done
pa -c "do \$\$ declare r record; begin for r in select polname from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*') and (coalesce(pg_get_expr(polqual, polrelid),'') ~ '(es_admin|can_manage_content)\\(' or coalesce(pg_get_expr(polwithcheck, polrelid),'') ~ '(es_admin|can_manage_content)\\(') loop execute format('drop policy %I on storage.objects', r.polname); end loop; end \$\$;" >/dev/null
echo "== estado de partida: historial $(p -At -c "select count(*) || ' versiones, ultima ' || max(version) from supabase_migrations.schema_migrations"); escritura admin en storage: $(p -At -c "select count(*) from pg_policy where polrelid = 'storage.objects'::regclass and polcmd in ('a','w','d','*')")"
export REHEARSAL_CONTAINER=$C
W=ops/deploy/v1/apply-main-migration.sh
mf() { ls supabase/migrations/${1}_*.sql; }
neg() { local label="$1"; shift; echo; echo "-- NEGATIVA: $label"; local out rc; out="$("$@" 2>&1 </dev/null)"; rc=$?; echo "$out" | grep -E 'STOP|ERROR' | head -2; echo "rc=$rc"; [ $rc -ne 0 ] || { echo "FAIL: la negativa '$label' no se nego"; exit 1; }; }
neg "version fuera del manifiesto (20261015100000)" bash $W "$(mf 20261015100000)"
cp "$(mf 20261022100000)" "$TMPD/"; echo "-- cambio" >> "$TMPD/$(basename "$(mf 20261022100000)")"
neg "blob distinto del ensayado (20261022100000 con una linea mas)" bash $W "$TMPD/$(basename "$(mf 20261022100000)")"
neg "predecesora ausente (20261022100000 sin 20261021100000 en el historial)" bash -c "echo 20261022100000 | bash $W $(mf 20261022100000)"
neg "--record-only de una clase apply (20261022100100)" bash -c "echo 20261022100100 | bash $W --record-only $(mf 20261022100100)"
neg "clase never (20261023100100)" bash -c "echo 20261023100100 | bash $W $(mf 20261023100100)"
neg "clase record aplicada (20261023100300)" bash -c "echo 20261023100300 | bash $W $(mf 20261023100300)"
echo; echo "== VENTANA: las 40 en el orden del manifiesto"
n=0
for v in $(tr -d '\r' < ops/deploy/v1/main-migrations.manifest | grep -Ev '^[[:space:]]*(#|$)' | awk '$4 == "apply" || $4 == "apply-or-record" {print $1}'); do
  out="$(echo "$v" | bash $W "$(mf "$v")" 2>&1)"; rc=$?
  echo "$out" | grep -E '^(OK|STOP|FALLO|NO-GO|ERROR|AVISO)' ; [ $rc = 0 ] || { echo "$out" | tail -5; echo "FAIL en $v"; exit 1; }
  n=$((n + 1))
  if [ "$v" = 20261022103400 ]; then
    echo; echo "== F4.3b relleno de la penalizacion racional"
    p < docs/engineering/v1-deploy/05_penalty_backfill_count_readonly.sql
    N=$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")
    echo "-- NEGATIVA: actor que no es admin activo (usuario d0..02)"
    sed "s/<ADMIN_UUID>/d0000000-0000-0000-0000-000000000002/; s/<N>/$N/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'ERROR' | head -1
    [ "$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")" = "$N" ] || { echo "FAIL: el actor no admin cambio algo"; exit 1; }
    sed "s/<ADMIN_UUID>/d0000000-0000-0000-0000-000000000001/; s/<N>/$N/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'NOTICE|ERROR'
    [ "$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")" = 0 ] || { echo "FAIL: el relleno no dejo 0 candidatos"; exit 1; }
    p < docs/engineering/v1-deploy/07_penalty_backfill_postcheck_readonly.sql
    echo "-- 08 deshacer (N = $N) y post-check"
    sed "s/<ADMIN_UUID>/d0000000-0000-0000-0000-000000000001/; s/<N>/$N/" docs/engineering/v1-deploy/08_penalty_backfill_undo.sql | p 2>&1 | grep -E 'NOTICE|ERROR'
    [ "$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")" = "$N" ] || { echo "FAIL: deshacer no devolvio los $N"; exit 1; }
    echo "candidatos tras deshacer: $(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0") (esperado $N)"
    echo "-- rehacer el relleno (estado final de la ventana)"
    sed "s/<ADMIN_UUID>/d0000000-0000-0000-0000-000000000001/; s/<N>/$N/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'NOTICE|ERROR'
    [ "$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")" = 0 ] || { echo "FAIL: rehacer no dejo 0 candidatos"; exit 1; }
    echo
  fi
done
echo "== ventana: $n migraciones aplicadas con el script (manifiesto, predecesora, VERIFY = 0, historial)"
echo; echo "== --record-only de las 4 aplicadas fuera del historial (D-10)"
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do echo "$v" | bash $W --record-only "$(mf "$v")" 2>&1 | grep -o -E '(OK|STOP|NO-GO|ERROR)[^:]*:.*'; done
neg "re-aplicar una ya registrada (20261022100000)" bash -c "echo 20261022100000 | bash $W $(mf 20261022100000)"
echo; echo "== historial final: $(p -At -c "select count(*) || ' versiones, ultima ' || max(version) from supabase_migrations.schema_migrations")"
echo; echo "== 00_history_readonly.sql"; p < docs/engineering/v1-deploy/00_history_readonly.sql 2>&1 | tail -22
echo; echo "== 01_catalog_preflight_readonly.sql (DESPUES de la ventana; con TRUNCATE)"; p < docs/engineering/v1-deploy/01_catalog_preflight_readonly.sql 2>&1
echo; echo "== cartera"; p -At -c "select 'reconcile_rows=' || (select count(*) from public.app_wallet_reconcile(null)) || ' legacy_opening=' || (select count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening')"
echo "WINDOW-SELFTEST OK"
