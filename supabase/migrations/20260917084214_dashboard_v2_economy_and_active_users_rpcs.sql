-- Dashboard V2 · lecturas agregadas que PostgREST no expresa (2026-09-17).
--
-- PostgREST cuenta filas pero no suma columnas: por eso «Ingresos» y «OposiTickets
-- en circulación» viajaban como NOT_INSTRUMENTED. «Usuarios activos (30 d)» leía
-- `rachas_usuario`, una tabla sin escritor (0 filas, sin trigger ni función que la
-- rellene): un cero que nunca podía cambiar pintado como dato.
--
-- Las tres son de SOLO LECTURA (`stable`), `security invoker`: el backend las llama
-- con la clave de servidor (`service_role`, BYPASSRLS). Se revoca EXECUTE a
-- public/anon/authenticated porque el default ACL de funciones en `public` lo concede.
--
-- Rollback:
--   drop function if exists public.admin_dashboard_economy_totals();
--   drop function if exists public.admin_dashboard_active_users(integer);

do $pre$
begin
  if to_regprocedure('public.admin_dashboard_economy_totals()') is not null then
    raise exception 'PREFLIGHT_FAILED: public.admin_dashboard_economy_totals() ya existe';
  end if;
  if to_regprocedure('public.admin_dashboard_active_users(integer)') is not null then
    raise exception 'PREFLIGHT_FAILED: public.admin_dashboard_active_users(integer) ya existe';
  end if;
end
$pre$;

-- Ingresos brutos en EUR de compras `pagada`. Si alguna compra pagada no tiene
-- precio, el total NO se conoce: devuelve NULL en lugar de una suma que parece
-- correcta y se queda corta.
create function public.admin_dashboard_economy_totals()
returns table (gross_revenue_cents bigint, paid_purchases integer, tickets_in_circulation bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    (select case when count(*) filter (where c.precio_centimos is null) > 0 then null
                 else coalesce(sum(c.precio_centimos), 0)::bigint end
       from public.compras_usuario c
      where c.estado = 'pagada' and c.moneda = 'EUR'),
    (select count(*)::integer
       from public.compras_usuario c
      where c.estado = 'pagada' and c.moneda = 'EUR'),
    (select coalesce(sum(w.saldo_tickets), 0)::bigint
       from public.carteras_usuario w);
$$;

comment on function public.admin_dashboard_economy_totals() is
  'Dashboard V2: ingresos brutos EUR de compras pagadas (NULL si alguna pagada no tiene precio), numero de compras pagadas y OposiTickets en circulacion (suma de saldos). Solo service_role.';

-- Usuarios distintos con actividad registrada en los ultimos N dias: un intento
-- de examen iniciado o un evento de app. N se acota a 1..366.
create function public.admin_dashboard_active_users(p_days integer default 30)
returns integer
language sql
stable
security invoker
set search_path = ''
as $$
  select count(distinct u.usuario_id)::integer
    from (
      select ie.usuario_id
        from public.intentos_examen ie
       where ie.iniciado_en >= now() - make_interval(days => least(greatest(coalesce(p_days, 30), 1), 366))
      union all
      select ea.usuario_id
        from public.eventos_app ea
       where ea.usuario_id is not null
         and ea.occurred_en >= now() - make_interval(days => least(greatest(coalesce(p_days, 30), 1), 366))
    ) u;
$$;

comment on function public.admin_dashboard_active_users(integer) is
  'Dashboard V2: usuarios distintos con un intento de examen iniciado o un evento de app en los ultimos N dias (1..366). Solo service_role.';

revoke all on function public.admin_dashboard_economy_totals() from public, anon, authenticated;
revoke all on function public.admin_dashboard_active_users(integer) from public, anon, authenticated;
grant execute on function public.admin_dashboard_economy_totals() to service_role;
grant execute on function public.admin_dashboard_active_users(integer) to service_role;
