-- SEC-V1-01 · PREFLIGHT DE SOLO LECTURA para MAIN (ndiooyyqtaeysnedywer). Catálogo + recuentos agregados; ninguna escritura,
-- ningún dato personal. Reproduce, como SELECT, cada condición del bloque PREFLIGHT de
-- supabase/migrations/20261023100000_security_content_admin_write_revoke.sql. Resultado esperado: TODAS las filas con ok = true.
-- Si alguna sale false, la migración abortaría (P0001 CONTENT_ADMIN_WRITE_PREFLIGHT) sin cambiar nada: NO aplicar y revisar.
with t(tabla, politica) as (values
  ('examenes','examenes_admin_all'), ('preguntas','preguntas_admin_all'), ('opciones_pregunta','opciones_pregunta_admin_all'),
  ('soluciones_pregunta','soluciones_pregunta_admin_all'), ('preguntas_examen','preguntas_examen_admin_all'),
  ('recursos_pregunta','recursos_pregunta_admin_all'), ('especialidades','especialidades_admin_all'),
  ('comunidades_autonomas','comunidades_admin_all'), ('etiquetas','etiquetas_admin_all'), ('etiquetas_pregunta','etiquetas_pregunta_admin_all'),
  ('lotes_importacion_preguntas','lotes_importacion_admin_all'), ('noticia','noticia_admin_all'), ('tipo_noticia','tipo_noticia_admin_all')),
rel as (select t.*, c.oid, c.relowner from t left join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla),
acl as (select r.tabla, a.grantee, a.grantor, a.privilege_type, a.is_grantable, r.relowner
        from rel r cross join lateral aclexplode((select relacl from pg_class where oid = r.oid)) a
        where a.privilege_type in ('INSERT','UPDATE','DELETE'))
select 'roles_exist' as check_name, (to_regrole('anon') is not null and to_regrole('authenticated') is not null and to_regrole('service_role') is not null) as ok, null::text as info
union all
select 'perfiles_estado_cuenta_column', exists (select 1 from pg_attribute where attrelid = 'public.perfiles_usuario'::regclass and attname = 'estado_cuenta' and not attisdropped), null
union all
select 'table_exists:' || tabla, oid is not null, null from rel
union all
select 'admin_all_is_main_shape:' || r.tabla,
       exists (select 1 from pg_policy p where p.polrelid = r.oid and p.polname = r.politica and p.polpermissive and p.polcmd = '*'
                 and p.polroles = array['authenticated'::regrole::oid]
                 and pg_get_expr(p.polqual, p.polrelid) ~ '^(public\.)?es_admin\(\)$' and pg_get_expr(p.polwithcheck, p.polrelid) ~ '^(public\.)?es_admin\(\)$'), null
from rel r
union all
select 'authenticated_has_exactly_IUD:' || r.tabla,
       (select coalesce(array_agg(privilege_type order by privilege_type), '{}') from acl where acl.tabla = r.tabla and grantee = 'authenticated'::regrole) = array['DELETE','INSERT','UPDATE']::text[], null
from rel r
union all
select 'authenticated_IUD_granted_by_owner_no_grant_option:' || r.tabla,
       not exists (select 1 from acl where acl.tabla = r.tabla and grantee = 'authenticated'::regrole and (grantor <> relowner or is_grantable)), null
from rel r
union all
select 'no_anon_or_public_write:' || r.tabla,
       not exists (select 1 from acl left join pg_roles ro on ro.oid = acl.grantee where acl.tabla = r.tabla and (acl.grantee = 0 or ro.rolname = 'anon')),
       (select string_agg(coalesce(ro.rolname, 'PUBLIC') || ' ' || privilege_type, ', ') from acl left join pg_roles ro on ro.oid = acl.grantee
         where acl.tabla = r.tabla and (acl.grantee = 0 or ro.rolname = 'anon'))
from rel r
union all
select 'es_admin_body_is_main', btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))
         = 'select exists ( select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ru.usuario_id = auth.uid() and ra.codigo in (''superadmin'', ''admin'') );',
       btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))
from pg_proc p where p.oid = to_regprocedure('public.es_admin()')
union all
select 'perfiles_update_propio_is_main',
       pg_get_expr(p.polqual, p.polrelid) = '((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())'
       and pg_get_expr(p.polwithcheck, p.polrelid) = '((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())'
       and p.polpermissive and p.polcmd = 'w' and p.polroles = array['authenticated'::regrole::oid],
       pg_get_expr(p.polqual, p.polrelid) || ' | ' || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
from pg_policy p where p.polrelid = 'public.perfiles_usuario'::regclass and p.polname = 'perfiles_usuario_update_propio'
union all
-- Impacto: un admin cuya cuenta no esté 'activa' deja de ser admin en RLS. Solo recuentos, sin identificadores.
select 'admins_without_active_account (count must be reviewed, not necessarily 0)', true,
       (select count(*)::text from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id
          left join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
         where ra.codigo in ('superadmin','admin') and coalesce(pu.estado_cuenta, '<sin perfil>') <> 'activa')
union all
select 'admins_total', true,
       (select count(distinct ru.usuario_id)::text from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ra.codigo in ('superadmin','admin'))
order by 1;
