#!/usr/bin/env bash
# V1-DEPLOY-REHEARSAL 2026-10-22 · ensayo del paso F4.3b (relleno de la penalizacion racional en examenes OFICIALES existentes).
# Contenedor propio v1-deploy-rehearsal-penalty (borrado por nombre al terminar). Punto de partida: forma de MAIN 2026-10-14 + semilla
# sintetica + 2 examenes OFICIALES heredados (uno publicado, uno borrador) + las 4 ya aplicadas en MAIN; despues las migraciones del
# orden real hasta 20261022103400 inclusive; despues 05 (recuento), 06 (UPDATE en una transaccion, con negativas) y 07 (post-check).
# Uso: bash penalty-backfill-test.sh <arbol con los blobs de SOURCES.txt> <directorio del ensayo con order.txt> <captura boot_full2.sql>
set -uo pipefail
TREE="${1:?arbol}"; RUN="${2:?run}"; BOOT="${3:?boot}"
C=v1-deploy-rehearsal-penalty
IMG=public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9
cd "$TREE" || exit 2
docker rm -f $C >/dev/null 2>&1
docker run -d --name $C --memory 1g --cpus 2 -e POSTGRES_PASSWORD=postgres $IMG >/dev/null || exit 1
trap 'docker rm -f '$C' >/dev/null 2>&1' EXIT
for i in $(seq 1 90); do [ "$(docker exec $C psql -U postgres -h localhost -At -c "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] && break; sleep 2; done
p() { docker exec -i $C psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
pa() { docker exec -i $C psql -U supabase_admin -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
p -1 < "$BOOT" >/dev/null 2>&1 || { echo "FAIL boot"; exit 1; }
pa < supabase/baseline/main_shape_auth_users.sql >/dev/null 2>&1
pa < supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql >/dev/null 2>&1
p < docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/seed_synthetic.sql >/dev/null || { echo "FAIL seed"; exit 1; }
# Examenes OFICIALES heredados (como los que ya hay en MAIN antes de 20261022101300): REH-1 publicado pasa a oficial; REH-2 borrador oficial.
p >/dev/null <<'SQL' || { echo "FAIL oficiales heredados"; exit 1; }
begin; set local session_replication_role = replica;
update public.examenes set es_oficial = true where codigo_examen = 'REH-1';
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
  values ('d4000000-0000-0000-0000-000000000002', 'REH-2', 'd2000000-0000-0000-0000-000000000001', 'd3000000-0000-0000-0000-000000000001',
          'Oficial borrador', 'rafaga', 'gratuito', false, true);
commit;
SQL
for v in 20261023100000 20261023100300 20261023100400 20261023100500; do p < supabase/migrations/${v}_*.sql >/dev/null 2>&1 || { echo "FAIL preaplicada $v"; exit 1; }; done
n=0
for m in $(awk '{print $2}' "$RUN/order.txt"); do
  n=$((n + 1)); p < supabase/migrations/$m.sql >/dev/null 2>/tmp/err.$$ || { echo "FAIL $m"; cat /tmp/err.$$; exit 1; }
  [ "${m%%_*}" = 20261022103400 ] && break
done
echo "ok: punto de partida + $n migraciones del orden real hasta 20261022103400 aplicadas (2 examenes oficiales heredados, penalizacion_racional = false)"
echo; echo "== 05 recuento (antes)"; p < docs/engineering/v1-deploy/05_penalty_backfill_count_readonly.sql
N=$(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")
ADMIN=d0000000-0000-0000-0000-000000000001
echo; echo "== 06 SIN sustituir los marcadores (debe fallar y no cambiar nada)"
p < docs/engineering/v1-deploy/06_penalty_backfill_apply.sql 2>&1 | grep -E 'ERROR' | head -2
echo "candidatos tras el intento: $(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")"
echo; echo "== 06 con <N> equivocado ($((N + 1))): debe deshacerse"
sed "s/<ADMIN_UUID>/$ADMIN/; s/<N>/$((N + 1))/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'ERROR|DETAIL' | head -2
echo "candidatos tras el intento: $(p -At -c "select count(*) from public.examenes where es_oficial and not penalizacion_racional and penalizacion_num = 0")"
echo; echo "== 06 con <ADMIN_UUID> y <N> = $N"
sed "s/<ADMIN_UUID>/$ADMIN/; s/<N>/$N/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'NOTICE|ERROR|DETAIL'; echo "rc=${PIPESTATUS[1]}"
echo; echo "== 07 post-check"; p < docs/engineering/v1-deploy/07_penalty_backfill_postcheck_readonly.sql
echo; echo "== actor y motivo en auditoria_log"; p -At -c "select usuario_id, despues ->> 'motivo' from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' order by creado_en"
echo; echo "== 06 otra vez con <N> = 0 (idempotente: nada que actualizar)"
sed "s/<ADMIN_UUID>/$ADMIN/; s/<N>/0/" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | p 2>&1 | grep -E 'NOTICE|ERROR'
echo "PENALTY-BACKFILL-TEST FIN"
