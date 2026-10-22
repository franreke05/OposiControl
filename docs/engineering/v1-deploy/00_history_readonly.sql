-- V1 deploy · F1.1 · Historial de migraciones de MAIN (SOLO LECTURA; sin datos de usuarios).
-- Lo ejecuta el PROPIETARIO en el editor SQL de MAIN (ndiooyyqtaeysnedywer). Guardar la salida COMPLETA como main-history-<fecha>.txt.
-- Esperado (captura 2026-10-14): 77 versiones, ultima 20261015100000. Cualquier version >= 20261016 que NO este en la lista de
-- docs/engineering/V1_PRODUCTION_DEPLOY_PLAN.md seccion 4 es DERIVA: NO-GO hasta revisarla. Excepcion conocida: filas del
-- 2026-10-22 con otra version/nombre si el MCP registro 100300/100400/100500 con su propia marca de tiempo (plan, F1.1).
begin read only;
select count(*) as versiones, max(version) as ultima from supabase_migrations.schema_migrations;
select version, name from supabase_migrations.schema_migrations where version >= '20261012000000' order by version;

-- Versiones del repo que importan a este despliegue: registrada (si/no) y estado esperado.
select v as version, exists (select 1 from supabase_migrations.schema_migrations m where m.version = v) as registrada, esperado
from (values
  ('20261020100000', 'pendiente o aplicada a mano (DEBT-156b); el Ktor V1 la NECESITA'),
  ('20261021100000', 'pendiente o aplicada a mano (DEBT-156b)'),
  ('20261023100000', 'APLICADA 2026-10-22 fuera del historial (SEC-V1-01): se salta; registrar solo con D-10'),
  ('20261023100100', 'NUNCA: RB-16 hecho a mano; no se registra'),
  ('20261023100200', 'pendiente (CAND-04: despues de 20261022102600)'),
  ('20261023100300', 'APLICADA 2026-10-22 fuera del historial (RB-18): se salta'),
  ('20261023100400', 'APLICADA 2026-10-22 fuera del historial: se salta'),
  ('20261023100500', 'APLICADA 2026-10-22 fuera del historial (RB-17 Control): se salta'),
  ('20261023100600', 'pendiente')) t(v, esperado)
order by 1;

-- Huella fisica (por si se aplicaron sin registrar la version):
select 'solicitudes_acceso_admin.correo_verificado_en (20261020100000)' as objeto,
       exists (select 1 from pg_attribute where attrelid = to_regclass('public.solicitudes_acceso_admin') and attname = 'correo_verificado_en' and not attisdropped) as existe
union all
select 'trigger auditoria_log_append_only_row (20261021100000)',
       exists (select 1 from pg_trigger where tgrelid = 'public.auditoria_log'::regclass and tgname = 'auditoria_log_append_only_row')
union all
-- RB-16 hecho a mano: ninguna politica de ESCRITURA de storage.objects puede usar es_admin()/can_manage_content() (esperado: existe = false)
select 'storage.objects: politica de escritura con es_admin/can_manage_content (RB-16, debe ser false)',
       exists (select 1 from pg_policy p where p.polrelid = to_regclass('storage.objects') and p.polcmd in ('a', 'w', 'd', '*')
                 and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') ~ '(es_admin|can_manage_content)\('
                      or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') ~ '(es_admin|can_manage_content)\('));
commit;
