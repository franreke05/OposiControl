-- =============================================================================
-- V1 -- store payments, OposiControl round-4 re-audit of 6987c1b + ae2028a + e9e9b93 (2026-10-20, N4-2, N4-5, N4-6). Corrective;
-- 20261022102600, 20261022102800, 20261022102900 and 20261022103000 are NOT edited. N4-1, N4-3 and N4-4 are Kotlin only.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022103000.
--
-- WHAT IT DOES (lock order unchanged everywhere: store-key advisory lock, then the inbox / order row, then the wallet advisory lock)
--   * N4-2: a notification deferred by an owner decision (G1 LIVE_CREDIT_DISABLED, D8 SUBSCRIPTIONS_DISABLED) is 'retenido' like a refund with
--     refunds off: no attempt is counted, it never becomes 'muerto'. app_store_inbox_pending(integer, text[]) re-asks a retained row only when the
--     caller names its reason (the job names the reasons whose switch is ON). app_store_inbox_stats reports those holds as 'retenido_decision', so
--     neither StoreInboxPendingStuck nor StoreInboxRefundsRetained fire for them. Rows already left 'pendiente' / 'muerto' by those reasons are
--     moved to 'retenido' once.
--   * N4-5: partial index for the stuck-inbox scan of app_store_inbox_stats (estado in pendiente / retenido / muerto).
--   * N4-6: app_store_reconcile_orphan_step re-links ONE orphan per call (one transaction: at most one store-key lock and one wallet lock), with a
--     keyset cursor so a busy or failing row never blocks the next one. The job loops it (StorePaymentsService.maintenance). The 200-row batch
--     app_store_reconcile_orphans(integer, boolean) is left as it is (not called by the server any more).
-- NOT CHANGED: app_store_inbox_mark, app_store_inbox_put, the credit / refund / reversal functions, every Control signature.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): new objects dropped; app_store_inbox_stats returns to its 20261022102900 body; the rows held
-- by a decision go back to 'pendiente' (due now), as before this migration.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_reconcile_orphan_step(boolean, timestamptz, text, text);
-- drop function if exists public.app_store_inbox_pending(integer, text[]);
-- drop index if exists public.ix_tienda_bandeja_atasco;
-- update public.tienda_bandeja b set estado = 'pendiente', proximo_intento_en = now()
--  where b.estado = 'retenido' and b.ultimo_error in ('LIVE_CREDIT_DISABLED', 'SUBSCRIPTIONS_DISABLED');
-- create or replace function public.app_store_inbox_stats()
-- returns table(estado text, n bigint, mas_antiguo timestamptz, max_intentos integer)
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select b.estado, count(*), min(b.recibido_en), max(b.intentos)
--     from public.tienda_bandeja b
--    where b.estado in ('pendiente', 'retenido', 'muerto')
--    group by b.estado
--   union all
--   select case b.resultado when 'REFUND_ORPHAN_HELD' then 'huerfano_reembolso' else 'huerfano_reversion' end, count(*), min(b.recibido_en), max(b.intentos)
--     from public.tienda_bandeja b
--    where b.resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD')
--    group by 1;
-- $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_inbox_pending(integer,text[])'), ('public.app_store_inbox_stats()'),
--                ('public.app_store_reconcile_orphan_step(boolean,timestamptz,text,text)')) f(n)
-- union all
-- select 'stuck_index_missing', (case when to_regclass('public.ix_tienda_bandeja_atasco') is null then 1 else 0 end)::bigint
-- union all
-- select 'stats_decision_split_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_inbox_stats()'::regprocedure) like '%retenido_decision%' then 0 else 1 end)::bigint
-- union all
-- select 'step_key_first_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_reconcile_orphan_step(boolean,timestamptz,text,text)'::regprocedure) like '%pg_try_advisory_xact_lock%' then 0 else 1 end)::bigint
-- union all
-- select 'decision_holds_counted_as_attempts', count(*)::bigint
--   from public.tienda_bandeja b where b.estado in ('pendiente', 'muerto') and b.ultimo_error in ('LIVE_CREDIT_DISABLED', 'SUBSCRIPTIONS_DISABLED');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_store_reconcile_orphans(integer,boolean)') is null or to_regprocedure('public.app_store_inbox_stats()') is null
     or to_regclass('public.tienda_sondeo') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_ROUND4_PREFLIGHT', hint = 'apply 20261022103000 first';
  end if;
end
$$;

-- 1. N4-5: the stuck-inbox scan reads a partial index, not the whole (mostly processed) inbox.
create index if not exists ix_tienda_bandeja_atasco on public.tienda_bandeja (estado, recibido_en)
  where estado in ('pendiente', 'retenido', 'muerto');

-- 2. N4-2: holds by an owner decision are 'retenido' (no attempt, never dead). Rows that the previous code left waiting as failures move once.
update public.tienda_bandeja b set estado = 'retenido', proximo_intento_en = null
 where b.estado in ('pendiente', 'muerto') and b.ultimo_error in ('LIVE_CREDIT_DISABLED', 'SUBSCRIPTIONS_DISABLED');

-- 3. N4-2: the job names the hold reasons whose switch is ON (REFUNDS_DISABLED, LIVE_CREDIT_DISABLED, SUBSCRIPTIONS_DISABLED); a retained row is
--    claimed again only for one of them. A retained row without a reason is a refund hold (the only kind before this migration).
create function public.app_store_inbox_pending(p_limit integer, p_retenidos text[])
returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz, reclamo text)
language plpgsql
volatile
security definer
set search_path = ''
as $$
#variable_conflict use_column
begin
  return query
  with candidatos as (
    select b.proveedor as p, b.evento_id as e
      from public.tienda_bandeja b
     where ((b.estado = 'pendiente' and coalesce(b.proximo_intento_en, b.recibido_en) <= now())
            or (b.estado = 'retenido' and coalesce(b.ultimo_error, 'REFUNDS_DISABLED') = any (coalesce(p_retenidos, '{}'::text[]))))
       and (b.reclamado_hasta is null or b.reclamado_hasta < now())
     order by coalesce(b.proximo_intento_en, b.recibido_en)
     limit least(greatest(coalesce(p_limit, 50), 1), 200)
     for update skip locked
  )
  update public.tienda_bandeja b set reclamado_por = gen_random_uuid()::text, reclamado_hasta = now() + interval '5 minutes'
    from candidatos k
   where b.proveedor = k.p and b.evento_id = k.e
  returning b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en, b.reclamado_por;
end;
$$;

-- 4. N4-2 + N4-5: decision holds are their own series ('retenido_decision'); the stuck scan uses ix_tienda_bandeja_atasco.
create or replace function public.app_store_inbox_stats()
returns table(estado text, n bigint, mas_antiguo timestamptz, max_intentos integer)
language sql
stable
security definer
set search_path = ''
as $$
  select case when b.estado = 'retenido' and b.ultimo_error in ('LIVE_CREDIT_DISABLED', 'SUBSCRIPTIONS_DISABLED') then 'retenido_decision' else b.estado end,
         count(*), min(b.recibido_en), max(b.intentos)
    from public.tienda_bandeja b
   where b.estado in ('pendiente', 'retenido', 'muerto')
   group by 1
  union all
  select case b.resultado when 'REFUND_ORPHAN_HELD' then 'huerfano_reembolso' else 'huerfano_reversion' end, count(*), min(b.recibido_en), max(b.intentos)
    from public.tienda_bandeja b
   where b.resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD')
   group by 1;
$$;

-- 5. N4-6: ONE orphan per call, after the keyset cursor (recibido_en, proveedor, evento_id) of the previous call. Same order as the live path and as
--    20261022103000: store key (try-lock: a key held by a live call is skipped, never waited for), then the inbox row FOR UPDATE SKIP LOCKED, then
--    the wallet (inside the refund / reversal function). The transaction of the call holds at most one key lock and one wallet lock.
--    resultado: the outcome of the re-link, BUSY (key or row held by a live call), SKIPPED (no longer an orphan) or ERROR (left for the next run).
--    No row = no candidate after the cursor.
create function public.app_store_reconcile_orphan_step(p_reembolsos_habilitados boolean, p_tras_recibido timestamptz, p_tras_proveedor text, p_tras_evento text)
returns table(proveedor text, evento_id text, recibido_en timestamptz, resultado text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c record;
  v_r record;
  v_out text := 'BUSY';
begin
  select b.proveedor, b.evento_id, b.recibido_en, b.clave_compra into v_c
    from public.tienda_bandeja b
   where (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
     and b.clave_compra is not null
     and exists (select 1 from public.compras_usuario c
                  where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra and c.cantidad_tickets is not null)
     and (p_tras_recibido is null
          or (b.recibido_en, b.proveedor, b.evento_id) > (p_tras_recibido, coalesce(p_tras_proveedor, ''), coalesce(p_tras_evento, '')))
   order by b.recibido_en, b.proveedor, b.evento_id
   limit 1;
  if not found then
    return;
  end if;
  begin
    if pg_try_advisory_xact_lock(hashtextextended('store-key:' || v_c.proveedor || ':' || v_c.clave_compra, 0)) then
      select b.proveedor, b.evento_id, b.resultado, b.clave_compra into v_r
        from public.tienda_bandeja b
       where b.proveedor = v_c.proveedor and b.evento_id = v_c.evento_id
         and (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
       for update of b skip locked;
      if not found then
        v_out := 'SKIPPED';
      else
        if v_r.resultado = 'REVERSAL_ORPHAN_HELD' then
          v_out := public.app_store_refund_reversed(v_r.proveedor, v_r.evento_id, v_r.clave_compra);
        else
          select r.outcome into v_out from public.app_store_refund_purchase(v_r.proveedor, v_r.evento_id, v_r.clave_compra) r;
        end if;
        update public.tienda_bandeja b set resultado = v_out where b.proveedor = v_r.proveedor and b.evento_id = v_r.evento_id;
      end if;
    end if;
  exception when others then
    raise warning 'app_store_reconcile_orphan_step: % / % left for the next run (%)', v_c.proveedor, v_c.evento_id, sqlstate;
    v_out := 'ERROR';
  end;
  return query select v_c.proveedor, v_c.evento_id, v_c.recibido_en, v_out;
end;
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.app_store_inbox_pending(integer,text[])', 'public.app_store_inbox_stats()',
    'public.app_store_reconcile_orphan_step(boolean,timestamptz,text,text)']
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$$;

commit;
