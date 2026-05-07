-- OposiControl - Diagnostico de tablas requeridas
-- Ejecutar en el SQL Editor de Supabase para ver que tablas faltan.

select
  t.tablename as tabla,
  case when t.tablename is not null then 'OK' else 'FALTA' end as estado
from (
  values
    ('especialidad'),
    ('examen'),
    ('pregunta'),
    ('recurso_pregunta'),
    ('noticia'),
    ('tipo_noticia'),
    ('usuario'),
    ('app_role'),
    ('user_role'),
    ('articulo_tienda'),
    ('cartera_tickets'),
    ('transaccion_ticket'),
    ('acceso_examen_usuario'),
    ('support_conversation'),
    ('support_message'),
    ('moderation_action'),
    ('audit_log'),
    ('tag'),
    ('search_index'),
    ('entity_tag')
) as requeridas(nombre)
left join pg_tables t
  on t.schemaname = 'public' and t.tablename = requeridas.nombre
order by estado, tabla;

-- Si alguna tabla aparece sin fila (FALTA), ejecutar:
-- docs/supabase/oposicontrol_full_schema_with_tag_search.sql
-- Luego si faltan las tablas de extensiones (opcion_pregunta, noticia_layer, etc.):
-- docs/supabase/oposicontrol_required_content_extensions.sql
