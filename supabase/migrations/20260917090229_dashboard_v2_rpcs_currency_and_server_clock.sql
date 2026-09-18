-- Dashboard V2 · correcciones de la revisión independiente (C, R-1 y R-4) sobre
-- 20260917084214_dashboard_v2_economy_and_active_users_rpcs (2026-09-17).
--
-- R-1 · Moneda. `compras_usuario.moneda` no tiene CHECK y el filtro `= 'EUR'`
-- distinguía mayúsculas: una compra pagada en `eur` o en otra moneda desaparecía del
-- total sin avisar. Con la regla «NULL antes que una suma corta», si existe una sola
-- compra pagada cuya moneda no sea EUR (sin distinguir mayúsculas) los ingresos no se
-- pueden expresar en EUR y salen NULL. `paid_purchases` cuenta TODAS las pagadas.
-- Las reembolsadas no cuentan: la cifra es «ingresos de compras en estado pagada».
--
-- R-4 · Reloj. `eventos_app.occurred_en` lo pone el cliente sin acotar: un reloj
-- adelantado mantenía a un usuario «activo» indefinidamente. La ventana usa ahora
-- `received_en` (NOT NULL, default now(), hora del servidor).
--
-- Firmas idénticas: `create or replace` conserva propietario y ACL (sólo service_role).
--
-- Rollback: re-aplicar el cuerpo de 20260917084214 (mismas firmas).

do $pre$
begin
  if to_regprocedure('public.admin_dashboard_economy_totals()') is null
     or to_regprocedure('public.admin_dashboard_active_users(integer)') is null then
    raise exception 'PREFLIGHT_FAILED: faltan las RPC de 20260917084214';
  end if;
end
$pre$;

create or replace function public.admin_dashboard_economy_totals()
returns table (gross_revenue_cents bigint, paid_purchases integer, tickets_in_circulation bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    (select case
              when count(*) filter (where c.precio_centimos is null) > 0 then null
              when count(*) filter (where upper(btrim(c.moneda)) <> 'EUR') > 0 then null
              else coalesce(sum(c.precio_centimos), 0)::bigint
            end
       from public.compras_usuario c
      where c.estado = 'pagada'),
    (select count(*)::integer
       from public.compras_usuario c
      where c.estado = 'pagada'),
    (select coalesce(sum(w.saldo_tickets), 0)::bigint
       from public.carteras_usuario w);
$$;

comment on function public.admin_dashboard_economy_totals() is
  'Dashboard V2: ingresos EUR de compras en estado pagada (NULL si alguna pagada no tiene precio o no es EUR), numero de compras pagadas y OposiTickets en circulacion (suma de saldos). Solo service_role.';

create or replace function public.admin_dashboard_active_users(p_days integer default 30)
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
         and ea.received_en >= now() - make_interval(days => least(greatest(coalesce(p_days, 30), 1), 366))
    ) u;
$$;

comment on function public.admin_dashboard_active_users(integer) is
  'Dashboard V2: usuarios distintos con un intento de examen iniciado o un evento de app recibido por el servidor en los ultimos N dias (1..366). Solo service_role.';
