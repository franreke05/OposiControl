-- V1 deploy · F1.2 · Preflight de CATALOGO (SOLO LECTURA: pg_catalog y privilegios; ninguna fila de negocio).
-- Lo ejecuta el PROPIETARIO en el editor SQL de MAIN. Guardar la salida completa.
-- Tablas: las de 20261023100600 (suscripciones, beneficios_plan, capacidades_especialidad) y las de RB-17 / 20261023100500
-- (opositeca_items, etiquetas_opositeca, invitaciones_referido, preguntas_parseadas_staging) + lotes_actualizacion_ranking
-- (cerrada por 20261022101200/101700). Por tabla y rol (anon, authenticated): INSERT / UPDATE / DELETE efectivos y RLS.
-- Valores esperados ANTES del despliegue (forma de MAIN de la captura 2026-10-14, reproducidos en el ensayo local
-- docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/): ver tabla 6.2 del plan. Despues: todo false salvo
-- authenticated INSERT en invitaciones_referido (politica invitaciones_referido_insert_propias, se mantiene a proposito).
begin read only;
with t(tabla) as (values ('suscripciones'), ('beneficios_plan'), ('capacidades_especialidad'), ('opositeca_items'),
                         ('etiquetas_opositeca'), ('invitaciones_referido'), ('preguntas_parseadas_staging'), ('lotes_actualizacion_ranking')),
r(rol) as (values ('anon'), ('authenticated'))
select t.tabla, r.rol,
       to_regclass('public.' || t.tabla) is not null as existe,
       case when to_regclass('public.' || t.tabla) is null then null else has_table_privilege(r.rol, 'public.' || t.tabla, 'INSERT') end as ins,
       case when to_regclass('public.' || t.tabla) is null then null else has_table_privilege(r.rol, 'public.' || t.tabla, 'UPDATE') end as upd,
       case when to_regclass('public.' || t.tabla) is null then null else has_table_privilege(r.rol, 'public.' || t.tabla, 'DELETE') end as del,
       (select c.relrowsecurity from pg_class c where c.oid = to_regclass('public.' || t.tabla)) as rls,
       (select count(*) from pg_policy p where p.polrelid = to_regclass('public.' || t.tabla) and p.polcmd in ('*', 'a', 'w', 'd') and p.polpermissive) as politicas_escritura_permisivas,
       (select string_agg(p.polname, ',' order by p.polname) from pg_policy p where p.polrelid = to_regclass('public.' || t.tabla) and p.polname like '%admin_all') as admin_all
from t cross join r
order by 1, 2;

-- RB-16 / 20261023100100: el rol que aplica migraciones (el del editor SQL o del psql del propietario) debe ser dueno de
-- storage.objects o miembro de su dueno; si no, la migracion se DETIENE en su PREFLIGHT sin cambiar nada (no bloquea al resto).
select current_user as rol_que_aplica,
       (select r.rolname from pg_class c join pg_roles r on r.oid = c.relowner where c.oid = to_regclass('storage.objects')) as dueno_storage_objects,
       pg_has_role(current_user, (select c.relowner from pg_class c where c.oid = to_regclass('storage.objects')), 'MEMBER') as puede_cambiar_politicas_storage;

-- Politicas de storage.objects que usan es_admin()/can_manage_content() (esperado ANTES: 14 de escritura; DESPUES de 100100: 0).
select count(*) filter (where p.polcmd in ('a', 'w', 'd', '*')) as storage_escritura_admin,
       count(*) filter (where p.polcmd = 'r') as storage_lectura_admin
from pg_policy p
where p.polrelid = to_regclass('storage.objects')
  and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') ~ '(es_admin|can_manage_content)\('
       or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') ~ '(es_admin|can_manage_content)\(');

-- Edge Functions: no se ven desde SQL. El propietario las lista en el panel (Edge Functions) o con `supabase functions list`;
-- esperado 0 (GO-1 2026-10-19). Si hay alguna que escriba estas tablas con el JWT del usuario: NO-GO hasta revisarla.
commit;
