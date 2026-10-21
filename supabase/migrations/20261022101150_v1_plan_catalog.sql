-- V1 PLAN CATALOG READ (D7-33; coordinator relay of E4-V1-PLAN-SCREEN.md, 2026-10-16). Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Read-only.
-- public.app_plan_catalog(): the ACTIVE and VISIBLE plans with coverage derived from the plan data (nivel -> rafagas/razonados as in the access verdict; immersive quota = benefit inmersivos_incluidos),
-- the 24 h question limit (benefit limite_preguntas_24h, same fail-closed reading as the limiter) and the STANDARD-phase tariffs of the plan's active visible subscription products, one row per period
-- (mensual, anual), annual served as data (the client never computes it). No user data, specialty independent.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_plan_catalog();
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'plan_catalog_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_plan_catalog()', 'execute') or has_function_privilege('authenticated', 'public.app_plan_catalog()', 'execute')
--                or not has_function_privilege('service_role', 'public.app_plan_catalog()', 'execute') then 1 else 0 end)::bigint as failures;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.tarifas_producto_comercial') is null or to_regclass('public.planes_suscripcion') is null or to_regclass('public.beneficios_plan') is null
     or to_regprocedure('private.plan_benefit_int(uuid,text,integer,integer)') is null then
    raise exception using errcode = 'P0001', message = 'V1_PLAN_CATALOG_PREFLIGHT', hint = 'apply 20261022100900 first; tarifas_producto_comercial must exist';
  end if;
end
$$;

create or replace function public.app_plan_catalog()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object('items', coalesce(jsonb_agg(x.item order by x.ord, x.codigo), '[]'::jsonb))
  from (
    select pl.posicion as ord, pl.codigo,
           jsonb_build_object(
             'planCodigo', pl.codigo,
             'nombre', pl.nombre,
             'nivel', pl.nivel,
             'orden', pl.posicion,
             'cobertura', jsonb_build_object(
               'rafagas', pl.nivel >= 1,
               'razonados', pl.nivel >= 2,
               'inmersivos', case when coalesce(q.cupo, 0) > 0 then 'cupo' else 'ninguno' end,
               'cupoInmersivos', case when coalesce(q.cupo, 0) > 0 then q.cupo end),
             'limitePreguntas24h', l.limite,
             'tarifas', coalesce((
               select jsonb_agg(jsonb_build_object(
                        'periodo', t.periodo_facturacion, 'precioCentimos', t.precio_centimos, 'moneda', t.moneda,
                        'productoCodigo', pc.codigo, 'destacado', pc.destacado)
                      order by (t.periodo_facturacion = 'anual'), pc.posicion, t.posicion, pc.codigo)
                 from public.productos_comerciales pc
                 join public.tarifas_producto_comercial t on t.producto_id = pc.id
                where pc.plan_id = pl.id and pc.activo and pc.visible and pc.tipo_producto = 'suscripcion'
                  and t.activo and t.visible and t.fase_precio = 'standard' and t.periodo_facturacion in ('mensual', 'anual')), '[]'::jsonb)) as item
      from public.planes_suscripcion pl
      cross join lateral (select private.plan_benefit_int(pl.id, 'inmersivos_incluidos', 0, private.config_int('max_inmersivos_incluidos', 4)) as cupo) q
      cross join lateral (
        select case
                 when private.plan_benefit_int(pl.id, 'limite_preguntas_24h', 1, private.config_int('max_limite_preguntas_24h', 500)) is null
                      and exists (select 1 from public.beneficios_plan b where b.plan_id = pl.id and b.codigo_beneficio = 'limite_preguntas_24h')
                   then private.config_int('limite_preguntas_24h_si_invalido', 30)
                 else private.plan_benefit_int(pl.id, 'limite_preguntas_24h', 1, private.config_int('max_limite_preguntas_24h', 500))
               end as limite) l
     where pl.activo and pl.visible
  ) x
$$;
revoke all on function public.app_plan_catalog() from public, anon, authenticated;
grant execute on function public.app_plan_catalog() to service_role;

commit;
