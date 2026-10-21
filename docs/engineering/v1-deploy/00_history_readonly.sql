-- V1 deploy · F1.1 · Historial de migraciones de MAIN (SOLO LECTURA; sin datos de usuarios).
-- Lo ejecuta el PROPIETARIO en el editor SQL de MAIN (ndiooyyqtaeysnedywer). Guardar la salida COMPLETA como main-history-<fecha>.txt.
-- Esperado (captura 2026-10-14): 77 versiones, ultima 20261015100000. Cualquier version >= 20261016 que NO este en la lista de
-- docs/engineering/V1_PRODUCTION_DEPLOY_PLAN.md seccion 4 es DERIVA: NO-GO hasta revisarla.
begin read only;
select count(*) as versiones, max(version) as ultima from supabase_migrations.schema_migrations;
select version, name from supabase_migrations.schema_migrations where version >= '20261012000000' order by version;
-- Las dos que DEBT-156b aplica a mano y que el Ktor de develop NECESITA (sin 20261020100000 el registro responde 503):
select v as version_esperada, exists (select 1 from supabase_migrations.schema_migrations m where m.version = v) as registrada
from (values ('20261020100000'), ('20261021100000')) t(v);
-- Huella fisica de 20261020100000 / 20261021100000 (por si se aplicaron sin registrar la version):
select 'solicitudes_acceso_admin.correo_verificado_en' as objeto,
       exists (select 1 from pg_attribute where attrelid = to_regclass('public.solicitudes_acceso_admin') and attname = 'correo_verificado_en' and not attisdropped) as existe
union all
select 'trigger auditoria_log_append_only_row',
       exists (select 1 from pg_trigger where tgrelid = 'public.auditoria_log'::regclass and tgname = 'auditoria_log_append_only_row');
commit;
