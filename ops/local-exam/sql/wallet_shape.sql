set search_path = pg_catalog;
select c.relname || ' COLS ' || (select string_agg(format('%s %s %s%s', a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, coalesce(' ' || pg_get_expr(d.adbin, d.adrelid), '')), ', ' order by a.attnum)
  from pg_attribute a left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped)
from pg_class c join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
where c.relname in ('carteras_usuario','creditos_contenido_usuario','transacciones_cartera','compras_usuario','activaciones_servicio_usuario','servicios','estado_premium_usuario','productos_comerciales','planes_suscripcion','suscripciones')
union all
select replace(co.conrelid::regclass::text, 'public.', '') || ' CON ' || co.conname || ' ' || pg_get_constraintdef(co.oid) from pg_constraint co
where replace(co.conrelid::regclass::text, 'public.', '') in ('carteras_usuario','creditos_contenido_usuario','transacciones_cartera','compras_usuario','activaciones_servicio_usuario','servicios','estado_premium_usuario','productos_comerciales','planes_suscripcion','suscripciones')
union all
select replace(p.polrelid::regclass::text, 'public.', '') || ' POL ' || p.polname || ' ' || coalesce(pg_get_expr(p.polqual, p.polrelid), '') from pg_policy p
where replace(p.polrelid::regclass::text, 'public.', '') in ('carteras_usuario','creditos_contenido_usuario','transacciones_cartera','compras_usuario','activaciones_servicio_usuario','servicios','estado_premium_usuario','productos_comerciales','planes_suscripcion','suscripciones')
union all
select table_name || ' GRANT ' || grantee || ' ' || string_agg(privilege_type, ',' order by privilege_type) from information_schema.role_table_grants
where table_schema = 'public' and grantee in ('anon','authenticated','service_role')
  and table_name in ('carteras_usuario','creditos_contenido_usuario','transacciones_cartera','compras_usuario','activaciones_servicio_usuario','servicios','estado_premium_usuario','productos_comerciales','planes_suscripcion','suscripciones')
group by table_name, grantee
union all
select 'INDEX ' || indexdef from pg_indexes where schemaname = 'public' and tablename in ('carteras_usuario','creditos_contenido_usuario','transacciones_cartera','compras_usuario','activaciones_servicio_usuario','servicios','estado_premium_usuario','productos_comerciales','planes_suscripcion','suscripciones')
order by 1;
