-- SEC-V1-01 · CHECKPOINT + COMPATIBILIDAD, SOLO LECTURA (catálogo). Ejecutar inmediatamente antes de aplicar y GUARDAR el resultado
-- completo fuera del repo: es la foto exacta del estado que el bloque ROLLBACK de la migración restaura (ACL, políticas, es_admin).
-- 1) ACL de las 13 tablas + perfiles_usuario
select 'acl' as kind, c.relname as object, c.relacl::text as definition
from pg_class c
where c.relnamespace = 'public'::regnamespace
  and c.relname in ('examenes','preguntas','opciones_pregunta','soluciones_pregunta','preguntas_examen','recursos_pregunta','especialidades',
                    'comunidades_autonomas','etiquetas','etiquetas_pregunta','lotes_importacion_preguntas','noticia','tipo_noticia','perfiles_usuario')
union all
-- 2) Todas las políticas de esas tablas (texto exacto)
select 'policy', c.relname || '.' || p.polname,
       p.polcmd::text || ' permissive=' || p.polpermissive || ' roles=' || p.polroles::regrole[]::text
       || ' using=' || coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ' check=' || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
from pg_policy p join pg_class c on c.oid = p.polrelid
where c.relnamespace = 'public'::regnamespace
  and c.relname in ('examenes','preguntas','opciones_pregunta','soluciones_pregunta','preguntas_examen','recursos_pregunta','especialidades',
                    'comunidades_autonomas','etiquetas','etiquetas_pregunta','lotes_importacion_preguntas','noticia','tipo_noticia','perfiles_usuario')
union all
-- 3) Definición completa y ACL de es_admin()
select 'function', 'public.es_admin()', pg_get_functiondef('public.es_admin()'::regprocedure) || E'\n-- acl: ' || coalesce(p.proacl::text, '')
from pg_proc p where p.oid = 'public.es_admin()'::regprocedure
union all
-- 4) COMPATIBILIDAD: toda política de CUALQUIER tabla (incluido storage) que dependa de es_admin(): su comportamiento cambia para admins
--    no activos (dejan de ver/escribir). Revisar que ninguna sea un flujo legítimo de un admin bloqueado.
select 'depends_on_es_admin:policy', n.nspname || '.' || c.relname || '.' || p.polname, p.polcmd::text
from pg_policy p join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace
where coalesce(pg_get_expr(p.polqual, p.polrelid), '') ~ 'es_admin\(' or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') ~ 'es_admin\('
union all
--    Funciones (cualquier esquema) que llaman a es_admin(): si las invoca un cliente con su JWT, también cambian.
select 'depends_on_es_admin:function', n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
       'security_definer=' || p.prosecdef || ' acl=' || coalesce(p.proacl::text, 'default')
from pg_proc p join pg_namespace n on n.oid = p.pronamespace
where p.prosrc ~ 'es_admin\(' and p.oid <> 'public.es_admin()'::regprocedure and n.nspname not in ('pg_catalog','information_schema')
order by 1, 2;
