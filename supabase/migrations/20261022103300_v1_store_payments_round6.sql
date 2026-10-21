-- =============================================================================
-- V1 -- store payments, OposiControl round-6 re-audit of 999a50b + c17a804 (2026-10-20, N6-2). Corrective; 20261022103200 is NOT edited.
-- N6-1 (gauge from the database state) is Kotlin only.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022103200.
--
-- WHAT IT DOES
--   * N6-2: the stored Notification History paginationToken may be up to 65536 characters (was 4096). Apple does not document a maximum; a
--     longer token than the database accepts would be refused on every run and Notification History would read the same 100 pages again and
--     again. The server also raises the gauge store_inbox_history_incomplete BEFORE saving the token, so a refusal never silences
--     StoreAppleHistoryIncomplete.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 4096 limit of 20261022103200 comes back (a stored token longer than 4096 is dropped
-- first: the window is then read again from the cursor, nothing is skipped).
-- ROLLBACK-BEGIN
-- begin;
-- update public.tienda_sondeo set pagina_token = null, ventana_inicio_ms = null, ventana_fin_ms = null where length(pagina_token) > 4096;
-- alter table public.tienda_sondeo drop constraint if exists tienda_sondeo_pagina_check;
-- alter table public.tienda_sondeo add constraint tienda_sondeo_pagina_check check (
--   (pagina_token is null and ventana_inicio_ms is null and ventana_fin_ms is null)
--   or (pagina_token is not null and length(pagina_token) between 1 and 4096 and ventana_inicio_ms >= 0 and ventana_fin_ms >= ventana_inicio_ms));
-- create or replace function public.app_store_poll_page(p_proveedor text, p_fuente text, p_token text, p_inicio_ms bigint, p_fin_ms bigint)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- begin
--   if p_proveedor is null or p_fuente is null or p_token is null or length(p_token) not between 1 and 4096
--      or p_inicio_ms is null or p_inicio_ms < 0 or p_fin_ms is null or p_fin_ms < p_inicio_ms then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   insert into public.tienda_sondeo as t (proveedor, fuente, cursor_ms, ultimo_sondeo_en, pagina_token, ventana_inicio_ms, ventana_fin_ms)
--   values (p_proveedor, p_fuente, p_inicio_ms, now(), p_token, p_inicio_ms, p_fin_ms)
--   on conflict (proveedor, fuente) do update
--     set ultimo_sondeo_en = now(), pagina_token = excluded.pagina_token, ventana_inicio_ms = excluded.ventana_inicio_ms, ventana_fin_ms = excluded.ventana_fin_ms;
--   return true;
-- end;
-- $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:public.app_store_poll_page(text,text,text,bigint,bigint)' as check_name,
--        (case when has_function_privilege('anon', 'public.app_store_poll_page(text,text,text,bigint,bigint)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_store_poll_page(text,text,text,bigint,bigint)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_store_poll_page(text,text,text,bigint,bigint)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'poll_page_token_limit_4096', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_poll_page(text,text,text,bigint,bigint)'::regprocedure) like '%65536%' then 0 else 1 end)::bigint
-- union all
-- select 'poll_page_check_limit_4096', (case when exists (select 1 from pg_constraint where conname = 'tienda_sondeo_pagina_check' and pg_get_constraintdef(oid) like '%65536%') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_store_poll_page(text,text,text,bigint,bigint)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_ROUND6_PREFLIGHT', hint = 'apply 20261022103200 first';
  end if;
end
$$;

alter table public.tienda_sondeo drop constraint if exists tienda_sondeo_pagina_check;
alter table public.tienda_sondeo add constraint tienda_sondeo_pagina_check check (
  (pagina_token is null and ventana_inicio_ms is null and ventana_fin_ms is null)
  or (pagina_token is not null and length(pagina_token) between 1 and 65536 and ventana_inicio_ms >= 0 and ventana_fin_ms >= ventana_inicio_ms));

create or replace function public.app_store_poll_page(p_proveedor text, p_fuente text, p_token text, p_inicio_ms bigint, p_fin_ms bigint)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_proveedor is null or p_fuente is null or p_token is null or length(p_token) not between 1 and 65536
     or p_inicio_ms is null or p_inicio_ms < 0 or p_fin_ms is null or p_fin_ms < p_inicio_ms then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  insert into public.tienda_sondeo as t (proveedor, fuente, cursor_ms, ultimo_sondeo_en, pagina_token, ventana_inicio_ms, ventana_fin_ms)
  values (p_proveedor, p_fuente, p_inicio_ms, now(), p_token, p_inicio_ms, p_fin_ms)
  on conflict (proveedor, fuente) do update
    set ultimo_sondeo_en = now(), pagina_token = excluded.pagina_token, ventana_inicio_ms = excluded.ventana_inicio_ms, ventana_fin_ms = excluded.ventana_fin_ms;
  return true;
end;
$$;

commit;
