-- =============================================================================
-- V1 -- store payments, OposiControl round-5 re-audit of c30292c + 764e334 (2026-10-20, N5-1, N5-2). Corrective; 20261022102600 .. 20261022103100
-- are NOT edited. N5-3 and H-04 (ATTEMPT_EXPIRED -> 409) are Kotlin only.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022103100.
--
-- WHAT IT DOES
--   * N5-1: Get Notification History with more than 100 pages. tienda_sondeo keeps the paginationToken of the next page and the window it belongs
--     to (pagina_token, ventana_inicio_ms, ventana_fin_ms: all set or all null). app_store_poll_page stores them WITHOUT moving the cursor (it only
--     records the poll time); app_store_poll_advance moves the cursor and clears them; app_store_poll_get returns them. Chosen over "advance the
--     cursor to the signed date of the last notification read" because that one is only safe if History is returned in chronological order,
--     which is not verified: with the token nothing can be skipped whatever the order.
--   * N5-2: the old overload app_store_inbox_pending(integer, boolean) is dropped (no caller: the server uses (integer, text[]) since 20261022103100;
--     supabase/baseline/assertions.sql moved to the new one).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): poll functions back to their 20261022103000 bodies, columns dropped, the old overload back
-- with its 20261022102900 body and grants.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_poll_page(text, text, text, bigint, bigint);
-- drop function if exists public.app_store_poll_get(text, text);
-- create function public.app_store_poll_get(p_proveedor text, p_fuente text)
-- returns table(cursor_ms bigint, ultimo_sondeo_en timestamptz)
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select t.cursor_ms, t.ultimo_sondeo_en from public.tienda_sondeo t where t.proveedor = p_proveedor and t.fuente = p_fuente;
-- $$;
-- revoke all on function public.app_store_poll_get(text, text) from public, anon, authenticated;
-- grant execute on function public.app_store_poll_get(text, text) to service_role;
-- create or replace function public.app_store_poll_advance(p_proveedor text, p_fuente text, p_cursor_ms bigint)
-- returns bigint
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v bigint;
-- begin
--   if p_proveedor is null or p_fuente is null or p_cursor_ms is null or p_cursor_ms < 0 then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   insert into public.tienda_sondeo as t (proveedor, fuente, cursor_ms, ultimo_sondeo_en) values (p_proveedor, p_fuente, p_cursor_ms, now())
--   on conflict (proveedor, fuente) do update set cursor_ms = greatest(t.cursor_ms, excluded.cursor_ms), ultimo_sondeo_en = now()
--   returning t.cursor_ms into v;
--   return v;
-- end;
-- $$;
-- alter table public.tienda_sondeo drop constraint if exists tienda_sondeo_pagina_check;
-- alter table public.tienda_sondeo drop column if exists pagina_token, drop column if exists ventana_inicio_ms, drop column if exists ventana_fin_ms;
-- create function public.app_store_inbox_pending(p_limit integer, p_incluir_retenidos boolean)
-- returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz, reclamo text)
-- language plpgsql
-- volatile
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- begin
--   return query
--   with candidatos as (
--     select b.proveedor as p, b.evento_id as e
--       from public.tienda_bandeja b
--      where ((b.estado = 'pendiente' and coalesce(b.proximo_intento_en, b.recibido_en) <= now())
--             or (coalesce(p_incluir_retenidos, false) and b.estado = 'retenido'))
--        and (b.reclamado_hasta is null or b.reclamado_hasta < now())
--      order by coalesce(b.proximo_intento_en, b.recibido_en)
--      limit least(greatest(coalesce(p_limit, 50), 1), 200)
--      for update skip locked
--   )
--   update public.tienda_bandeja b set reclamado_por = gen_random_uuid()::text, reclamado_hasta = now() + interval '5 minutes'
--     from candidatos k
--    where b.proveedor = k.p and b.evento_id = k.e
--   returning b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en, b.reclamado_por;
-- end;
-- $$;
-- revoke all on function public.app_store_inbox_pending(integer, boolean) from public, anon, authenticated;
-- grant execute on function public.app_store_inbox_pending(integer, boolean) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_poll_get(text,text)'), ('public.app_store_poll_advance(text,text,bigint)'),
--                ('public.app_store_poll_page(text,text,text,bigint,bigint)')) f(n)
-- union all
-- select 'old_pending_overload_left', (case when to_regprocedure('public.app_store_inbox_pending(integer,boolean)') is null then 0 else 1 end)::bigint
-- union all
-- select 'poll_advance_keeps_token', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_poll_advance(text,text,bigint)'::regprocedure) like '%pagina_token = null%' then 0 else 1 end)::bigint
-- union all
-- select 'poll_page_moves_cursor', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_poll_page(text,text,text,bigint,bigint)'::regprocedure) like '%do update set cursor_ms%' then 1 else 0 end)::bigint
-- union all
-- select 'poll_page_check_missing', (case when exists (select 1 from pg_constraint where conname = 'tienda_sondeo_pagina_check') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_store_reconcile_orphan_step(boolean,timestamptz,text,text)') is null
     or to_regprocedure('public.app_store_inbox_pending(integer,text[])') is null or to_regprocedure('public.app_store_poll_advance(text,text,bigint)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_ROUND5_PREFLIGHT', hint = 'apply 20261022103100 first';
  end if;
end
$$;

-- 1. N5-2: the boolean overload re-claimed every retained row (G1/D8 holds included) and made an untyped null ambiguous (42725). No caller left.
drop function if exists public.app_store_inbox_pending(integer, boolean);

-- 2. N5-1: a half-read Notification History keeps its next-page token and its window; the cursor does not move until the window is read entirely.
alter table public.tienda_sondeo add column if not exists pagina_token text;
alter table public.tienda_sondeo add column if not exists ventana_inicio_ms bigint;
alter table public.tienda_sondeo add column if not exists ventana_fin_ms bigint;
alter table public.tienda_sondeo add constraint tienda_sondeo_pagina_check check (
  (pagina_token is null and ventana_inicio_ms is null and ventana_fin_ms is null)
  or (pagina_token is not null and length(pagina_token) between 1 and 4096 and ventana_inicio_ms >= 0 and ventana_fin_ms >= ventana_inicio_ms));

drop function if exists public.app_store_poll_get(text, text);
create function public.app_store_poll_get(p_proveedor text, p_fuente text)
returns table(cursor_ms bigint, ultimo_sondeo_en timestamptz, pagina_token text, ventana_inicio_ms bigint, ventana_fin_ms bigint)
language sql
stable
security definer
set search_path = ''
as $$
  select t.cursor_ms, t.ultimo_sondeo_en, t.pagina_token, t.ventana_inicio_ms, t.ventana_fin_ms
    from public.tienda_sondeo t where t.proveedor = p_proveedor and t.fuente = p_fuente;
$$;

-- the window was read entirely: the cursor moves forward (never back) and any stored page is forgotten
create or replace function public.app_store_poll_advance(p_proveedor text, p_fuente text, p_cursor_ms bigint)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  v bigint;
begin
  if p_proveedor is null or p_fuente is null or p_cursor_ms is null or p_cursor_ms < 0 then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  insert into public.tienda_sondeo as t (proveedor, fuente, cursor_ms, ultimo_sondeo_en) values (p_proveedor, p_fuente, p_cursor_ms, now())
  on conflict (proveedor, fuente) do update
    set cursor_ms = greatest(t.cursor_ms, excluded.cursor_ms), ultimo_sondeo_en = now(), pagina_token = null, ventana_inicio_ms = null, ventana_fin_ms = null
  returning t.cursor_ms into v;
  return v;
end;
$$;

-- half-read window: store the next page and its window; the cursor stays (a first poll starts it at the window start)
create function public.app_store_poll_page(p_proveedor text, p_fuente text, p_token text, p_inicio_ms bigint, p_fin_ms bigint)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_proveedor is null or p_fuente is null or p_token is null or length(p_token) not between 1 and 4096
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

do $$
declare
  f text;
begin
  foreach f in array array['public.app_store_poll_get(text,text)', 'public.app_store_poll_advance(text,text,bigint)',
                           'public.app_store_poll_page(text,text,text,bigint,bigint)']
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$$;

commit;
