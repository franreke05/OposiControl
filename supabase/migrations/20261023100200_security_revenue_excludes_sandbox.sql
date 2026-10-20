-- =============================================================================
-- PAY-F3 -- the Control revenue dashboard counts only PRODUCTION purchases (store sandbox purchases are never revenue)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN. NEW forward-only migration: no earlier file is edited. REQUIRES OposiBot's
-- 20261022102600_v1_store_payments (adds compras_usuario.entorno); on a tree without it the PREFLIGHT stops (fail closed).
--
-- EVIDENCE (OposiControl audit of feature/v1-p3-store-payments @ 8859ee4, scenario S3): with PAYMENTS_SANDBOX_MODE=allowlist a
-- store sandbox purchase becomes `pagada`, and public.admin_dashboard_economy_totals() (20260917090229, applied on MAIN) sums every
-- `pagada` row, so a tester/App Review purchase is shown as real revenue and as a paid purchase.
--
-- WHAT THIS MIGRATION DOES
--   public.admin_dashboard_economy_totals(): same signature, return type, volatility, security and grants (service_role only, as
--   20260917084214). Revenue and paid_purchases now filter coalesce(entorno, 'produccion') = 'produccion': legacy rows written
--   before 102600 (entorno null) keep counting, sandbox rows never do. tickets_in_circulation is unchanged (the ledger is the
--   balance; sandbox OT are real OT in the wallet and are reported as such).
--
-- PREFLIGHT (fail closed, P0001 REVENUE_SANDBOX_PREFLIGHT): compras_usuario.entorno exists and admin_dashboard_economy_totals() exists
--   (MAIN shape; the PG17 gate baseline must carry it, see audit note).
--
-- ROLLBACK (manual; executed verbatim by dbtest): restores the 20260917090229 body.
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.admin_dashboard_economy_totals()
-- returns table (gross_revenue_cents bigint, paid_purchases integer, tickets_in_circulation bigint)
-- language sql
-- stable
-- security invoker
-- set search_path = ''
-- as $$
--   select
--     (select case
--               when count(*) filter (where c.precio_centimos is null) > 0 then null
--               when count(*) filter (where upper(btrim(c.moneda)) <> 'EUR') > 0 then null
--               else coalesce(sum(c.precio_centimos), 0)::bigint
--             end
--        from public.compras_usuario c
--       where c.estado = 'pagada'),
--     (select count(*)::integer
--        from public.compras_usuario c
--       where c.estado = 'pagada'),
--     (select coalesce(sum(w.saldo_tickets), 0)::bigint
--        from public.carteras_usuario w);
-- $$;
-- revoke all on function public.admin_dashboard_economy_totals() from public, anon, authenticated;
-- grant execute on function public.admin_dashboard_economy_totals() to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'revenue_counts_sandbox' as check_name,
--        (case when pg_get_functiondef('public.admin_dashboard_economy_totals()'::regprocedure) ~ 'entorno' then 0 else 1 end)::bigint as failures, null::text as info
-- union all
-- select 'revenue_rpc_client_executable',
--        (case when has_function_privilege('anon', 'public.admin_dashboard_economy_totals()', 'execute')
--                or has_function_privilege('authenticated', 'public.admin_dashboard_economy_totals()', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_dashboard_economy_totals()', 'execute') then 1 else 0 end)::bigint, null;
-- VERIFY-END

begin;

do $$
begin
  if not exists (select 1 from pg_attribute where attrelid = to_regclass('public.compras_usuario') and attname = 'entorno' and not attisdropped) then
    raise exception 'REVENUE_SANDBOX_PREFLIGHT' using detail = 'public.compras_usuario.entorno is required (OposiBot 20261022102600_v1_store_payments)';
  end if;
  -- The ROLLBACK restores the 20260917090229 body, so the function must already exist (it does on MAIN); otherwise a rollback
  -- would leave a function that was never there.
  if to_regprocedure('public.admin_dashboard_economy_totals()') is null then
    raise exception 'REVENUE_SANDBOX_PREFLIGHT' using detail = 'public.admin_dashboard_economy_totals() (20260917090229) is required';
  end if;
end;
$$;

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
      where c.estado = 'pagada' and coalesce(c.entorno, 'produccion') = 'produccion'),
    (select count(*)::integer
       from public.compras_usuario c
      where c.estado = 'pagada' and coalesce(c.entorno, 'produccion') = 'produccion'),
    (select coalesce(sum(w.saldo_tickets), 0)::bigint
       from public.carteras_usuario w);
$$;
revoke all on function public.admin_dashboard_economy_totals() from public, anon, authenticated;
grant execute on function public.admin_dashboard_economy_totals() to service_role;

comment on function public.admin_dashboard_economy_totals() is
  'Dashboard V2: ingresos EUR de compras pagadas DE PRODUCCION (entorno nulo = anterior a 102600 = produccion; sandbox nunca cuenta; NULL si alguna pagada no tiene precio o no es EUR), numero de compras pagadas de produccion y OposiTickets en circulacion (suma de saldos). Solo service_role.';

commit;
