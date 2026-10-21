-- =============================================================================
-- V1 -- store payments, OposiControl round-3 re-audit of 1cabc65 + ad88bc0 (2026-10-19, N3-1..N3-7) and the subscription lifecycle / poll
-- cursors of the server-side verification. Corrective; 20261022102600, 20261022102800 and 20261022102900 are NOT edited.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022102900.
--
-- WHAT IT DOES (lock order everywhere: store-key advisory lock, then the inbox / order row, then the wallet advisory lock)
--   * N3-1: app_store_reconcile_orphans takes the store-key lock FIRST (try-lock: a key held by a live call is skipped, never waited for), then
--     re-selects the inbox row FOR UPDATE SKIP LOCKED, one subtransaction per row: no 40P01 against a credit retry.
--   * N3-2 / N3-4: app_store_inbox_mark refuses a null claim and never replaces a REFUND_HELD / REVERSAL_HELD result set by the database.
--   * N3-5: partial index for the orphan scans. N3-7: the retention purge never deletes any *_HELD row.
--   * N3-3 (the per-key lock proven by a real two-connection race, not by text) lives in scripts/db-pg17-gate.sh (step 6).
--   * Subscriptions: app_store_upsert_subscription takes the same per-key lock and answers REPLAY (no write) for an exact replay;
--     app_store_subscriptions_due lists the subscriptions the job re-reads from the store.
--   * tienda_sondeo + app_store_poll_get / app_store_poll_advance: persisted, forward-only cursors of the Voided Purchases and Notification History
--     polls (not in memory).
-- NOT CHANGED: admin_payment_order_resolve and every Control signature, refund policy P2, SEC-V1-05, ECO-04, F10.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): new objects dropped; the functions return to their 20261022102900 / 20261022102800 bodies.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_poll_advance(text, text, bigint);
-- drop function if exists public.app_store_poll_get(text, text);
-- drop table if exists public.tienda_sondeo;
-- drop function if exists public.app_store_subscriptions_due(integer);
-- drop index if exists public.ix_tienda_bandeja_huerfano;
-- create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text, p_reclamo text, p_max_intentos integer)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- begin
--   if p_estado not in ('pendiente', 'procesado', 'descartado', 'retenido') then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   -- only the holder of the claim marks, and only a row still waiting for work: a processed (or discarded / dead) row and its result never change.
--   -- 'retenido' (refunds off) is not an attempt; 'pendiente' (a real failure) backs off 1 min .. 6 h, and becomes 'muerto' after p_max_intentos
--   -- attempts when the caller asks for a dead letter (0 / null = never).
--   update public.tienda_bandeja b
--      set estado = case when p_estado = 'pendiente' and coalesce(p_max_intentos, 0) > 0 and b.intentos + 1 >= p_max_intentos then 'muerto' else p_estado end,
--          resultado = coalesce(p_resultado, b.resultado),
--          ultimo_error = left(p_error, 300),
--          intentos = b.intentos + case when p_estado = 'retenido' then 0 else 1 end,
--          proximo_intento_en = case when p_estado = 'pendiente' then now() + least(interval '6 hours', interval '1 minute' * power(2, least(b.intentos, 12))) else null end,
--          reclamado_por = null,
--          reclamado_hasta = null,
--          procesado_en = case when p_estado in ('procesado', 'descartado') then coalesce(b.procesado_en, now()) else b.procesado_en end
--    where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.estado in ('pendiente', 'retenido')
--      and b.reclamado_por is not distinct from p_reclamo;
--   return found;
-- end;
-- $$;
-- create or replace function public.app_store_inbox_purge(p_dias integer)
-- returns integer
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_n integer;
-- begin
--   if p_dias is null or p_dias < 30 then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   delete from public.tienda_bandeja b
--    where b.estado in ('procesado', 'descartado') and b.procesado_en < now() - make_interval(days => p_dias)
--      and coalesce(b.resultado, '') not in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD', 'REFUND_HELD', 'REVERSAL_HELD');
--   get diagnostics v_n = row_count;
--   return v_n;
-- end;
-- $$;
-- create or replace function public.app_store_reconcile_orphans(p_limit integer, p_reembolsos_habilitados boolean)
-- returns integer
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_r record;
--   v_out text;
--   v_n integer := 0;
-- begin
--   for v_r in
--     select b.proveedor, b.evento_id, b.resultado, b.clave_compra
--       from public.tienda_bandeja b
--      where (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
--        and b.clave_compra is not null
--        and exists (select 1 from public.compras_usuario c
--                     where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra and c.cantidad_tickets is not null)
--      order by b.recibido_en
--      limit least(greatest(coalesce(p_limit, 50), 1), 200)
--      for update of b skip locked
--   loop
--     if v_r.resultado = 'REVERSAL_ORPHAN_HELD' then
--       v_out := public.app_store_refund_reversed(v_r.proveedor, v_r.evento_id, v_r.clave_compra);
--     else
--       select r.outcome into v_out from public.app_store_refund_purchase(v_r.proveedor, v_r.evento_id, v_r.clave_compra) r;
--     end if;
--     update public.tienda_bandeja b set resultado = v_out where b.proveedor = v_r.proveedor and b.evento_id = v_r.evento_id;
--     v_n := v_n + 1;
--   end loop;
--   return v_n;
-- end;
-- $$;
-- create or replace function public.app_store_upsert_subscription(
--   p_usuario uuid, p_cuenta_token uuid, p_proveedor text, p_entorno text, p_producto text, p_tienda_producto text, p_id_externo text, p_estado text,
--   p_inicio timestamptz, p_fin timestamptz, p_renovacion timestamptz, p_fin_motivo text, p_version bigint, p_id_externo_previo text)
-- returns table(outcome text, estado text, fin_en timestamptz, es_premium boolean)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_user uuid;
--   v_owner uuid;
--   v_s public.suscripciones%rowtype;
--   v_outcome text;
-- begin
--   if p_proveedor is null or p_proveedor not in ('google_play', 'app_store') or p_id_externo is null or length(btrim(p_id_externo)) = 0
--      or p_entorno is null or p_entorno not in ('produccion', 'sandbox') or p_version is null
--      or p_estado is null or p_estado not in ('activa', 'periodo_prueba', 'pausada', 'cancelada', 'caducada')
--      or (p_fin_motivo is not null and p_fin_motivo not in ('usuario_cancelo', 'expiro', 'reembolso', 'revocada', 'contracargo', 'fallo_cobro')) then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   if not exists (select 1 from public.productos_comerciales p where p.codigo = p_producto and p.tipo_producto = 'suscripcion') then
--     raise exception using errcode = 'P0001', message = 'STORE_PRODUCT_UNKNOWN';
--   end if;
--   -- 20261022102800 (F10): a JWT user without the store account token never binds a subscription.
--   if p_usuario is not null and p_cuenta_token is null then
--     raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
--   end if;
--   v_user := private.store_resolve_user(p_usuario, p_cuenta_token);
--   select s.usuario_id into v_owner from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
--   if found then
--     if v_user is not null and v_owner <> v_user then
--       raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';   -- owner D5: no silent transfer between accounts
--     end if;
--     v_user := v_owner;
--   end if;
--   if v_user is null then
--     raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
--   end if;
--
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--
--   select s.* into v_s from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo for update;
--   if found then
--     if v_s.usuario_id <> v_user then
--       raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';
--     end if;
--     if v_s.version_evento is not null and v_s.version_evento > p_version then
--       v_outcome := 'STALE';
--     else
--       update public.suscripciones s
--          set producto_codigo = p_producto, estado = p_estado, inicio_en = coalesce(p_inicio, s.inicio_en), fin_en = p_fin, renovacion_en = p_renovacion,
--              fin_motivo = p_fin_motivo, version_evento = p_version, entorno = p_entorno, tienda_producto_id = p_tienda_producto, actualizado_en = now()
--        where s.id = v_s.id;
--       v_outcome := 'UPDATED';
--     end if;
--   else
--     insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, fin_en, renovacion_en, identificador_externo, fin_motivo,
--                                       version_evento, entorno, tienda_producto_id)
--     values (v_user, p_proveedor, p_producto, p_estado, coalesce(p_inicio, now()), p_fin, p_renovacion, p_id_externo, p_fin_motivo, p_version, p_entorno,
--             p_tienda_producto);
--     v_outcome := 'CREATED';
--   end if;
--
--   if v_outcome <> 'STALE' then
--     -- Play upgrade / resubscribe: the linked (previous) token of the SAME user stops giving access
--     if p_id_externo_previo is not null then
--       update public.suscripciones s set estado = 'caducada', fin_motivo = 'expiro', fin_en = least(coalesce(s.fin_en, now()), now()), actualizado_en = now()
--        where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo_previo and s.usuario_id = v_user and s.estado <> 'caducada';
--     end if;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     select v_user, 'suscripcion_tienda', 'suscripciones', s.id,
--            jsonb_build_object('proveedor', p_proveedor, 'estado', p_estado, 'fin_motivo', p_fin_motivo, 'fin_en', p_fin, 'entorno', p_entorno, 'resultado', v_outcome)
--       from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
--   end if;
--
--   return query
--     select v_outcome, s.estado, s.fin_en, coalesce((select e.es_premium from public.estado_premium_usuario e where e.usuario_id = v_user), false)
--       from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
-- end;
-- $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_inbox_mark(text,text,text,text,text,text,integer)'), ('public.app_store_inbox_purge(integer)'),
--                ('public.app_store_reconcile_orphans(integer,boolean)'),
--                ('public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)'),
--                ('public.app_store_subscriptions_due(integer)'), ('public.app_store_poll_get(text,text)'), ('public.app_store_poll_advance(text,text,bigint)')) f(n)
-- union all
-- select 'orphan_index_missing', (case when to_regclass('public.ix_tienda_bandeja_huerfano') is null then 1 else 0 end)::bigint
-- union all
-- select 'poll_table_open_to_clients', (case when not c.relrowsecurity or has_table_privilege('anon', c.oid, 'select') or has_table_privilege('authenticated', c.oid, 'select')
--                                              or has_table_privilege('service_role', c.oid, 'insert') then 1 else 0 end)::bigint
--   from pg_class c where c.oid = 'public.tienda_sondeo'::regclass
-- union all
-- select 'mark_held_guard_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_inbox_mark(text,text,text,text,text,text,integer)'::regprocedure) like '%REVERSAL_HELD%' then 0 else 1 end)::bigint
-- union all
-- select 'reconcile_key_first_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_reconcile_orphans(integer,boolean)'::regprocedure) like '%pg_try_advisory_xact_lock%' then 0 else 1 end)::bigint
-- union all
-- select 'subscription_key_lock_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)'::regprocedure) like '%store-key:%' then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_store_inbox_mark(text,text,text,text,text,text,integer)') is null
     or to_regprocedure('public.app_store_reconcile_orphans(integer,boolean)') is null or to_regprocedure('public.app_store_inbox_purge(integer)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_ROUND3_PREFLIGHT', hint = 'apply 20261022102900 first';
  end if;
end
$$;

-- 1. N3-5: the orphan scans (reconcile, stats) read an index, not the whole inbox.
create index if not exists ix_tienda_bandeja_huerfano on public.tienda_bandeja (recibido_en)
  where resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD');

-- 2. N3-2 + N3-4: mark needs the claim (null is refused) and never replaces a *_HELD result the database set while the row was being processed
--    (a credit re-linked the orphan: REFUND_HELD / REVERSAL_HELD stays; the caller's stale *_ORPHAN_HELD or a later REPLAY does not win).
create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text, p_reclamo text, p_max_intentos integer)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_estado is null or p_estado not in ('pendiente', 'procesado', 'descartado', 'retenido') or p_reclamo is null then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  update public.tienda_bandeja b
     set estado = case when p_estado = 'pendiente' and coalesce(p_max_intentos, 0) > 0 and b.intentos + 1 >= p_max_intentos then 'muerto' else p_estado end,
         resultado = case when b.resultado in ('REFUND_HELD', 'REVERSAL_HELD') then b.resultado else coalesce(p_resultado, b.resultado) end,
         ultimo_error = left(p_error, 300),
         intentos = b.intentos + case when p_estado = 'retenido' then 0 else 1 end,
         proximo_intento_en = case when p_estado = 'pendiente' then now() + least(interval '6 hours', interval '1 minute' * power(2, least(b.intentos, 12))) else null end,
         reclamado_por = null,
         reclamado_hasta = null,
         procesado_en = case when p_estado in ('procesado', 'descartado') then coalesce(b.procesado_en, now()) else b.procesado_en end
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.estado in ('pendiente', 'retenido')
     and b.reclamado_por = p_reclamo;
  return found;
end;
$$;

-- 3. N3-7: the retention purge never removes a row whose result still explains money (any *_HELD, orphan or not), whatever its age.
create or replace function public.app_store_inbox_purge(p_dias integer)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_n integer;
begin
  if p_dias is null or p_dias < 30 then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  delete from public.tienda_bandeja b
   where b.estado in ('procesado', 'descartado') and b.procesado_en < now() - make_interval(days => p_dias)
     and coalesce(b.resultado, '') not like '%\_HELD' escape '\';
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;

-- 4. N3-1: the re-scan uses the SAME lock order as the live path (store key, then the inbox row, then the wallet). It never waits for a key: a
--    key held by a live credit / refund is skipped (try-lock) and picked up on the next run, so it can never close a cycle with that call
--    (40P01). Each row runs in its own subtransaction: one failure does not undo the others.
create or replace function public.app_store_reconcile_orphans(p_limit integer, p_reembolsos_habilitados boolean)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c record;
  v_r record;
  v_out text;
  v_n integer := 0;
begin
  for v_c in
    select b.proveedor, b.evento_id, b.clave_compra
      from public.tienda_bandeja b
     where (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
       and b.clave_compra is not null
       and exists (select 1 from public.compras_usuario c
                    where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra and c.cantidad_tickets is not null)
     order by b.recibido_en
     limit least(greatest(coalesce(p_limit, 50), 1), 200)
  loop
    begin
      if not pg_try_advisory_xact_lock(hashtextextended('store-key:' || v_c.proveedor || ':' || v_c.clave_compra, 0)) then
        continue;
      end if;
      select b.proveedor, b.evento_id, b.resultado, b.clave_compra into v_r
        from public.tienda_bandeja b
       where b.proveedor = v_c.proveedor and b.evento_id = v_c.evento_id
         and (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
       for update of b skip locked;
      if not found then
        continue;
      end if;
      if v_r.resultado = 'REVERSAL_ORPHAN_HELD' then
        v_out := public.app_store_refund_reversed(v_r.proveedor, v_r.evento_id, v_r.clave_compra);
      else
        select r.outcome into v_out from public.app_store_refund_purchase(v_r.proveedor, v_r.evento_id, v_r.clave_compra) r;
      end if;
      update public.tienda_bandeja b set resultado = v_out where b.proveedor = v_r.proveedor and b.evento_id = v_r.evento_id;
      v_n := v_n + 1;
    exception when others then
      raise warning 'app_store_reconcile_orphans: % / % left for the next run (%)', v_c.proveedor, v_c.evento_id, sqlstate;
    end;
  end loop;
  return v_n;
end;
$$;

-- 5. Subscriptions: the same per-key lock as packs (two first deliveries of one subscription never race into the unique index) and an exact
--    replay (same version, same state) writes nothing (REPLAY). An older version never overwrites a newer one (STALE), as before.
create or replace function public.app_store_upsert_subscription(
  p_usuario uuid, p_cuenta_token uuid, p_proveedor text, p_entorno text, p_producto text, p_tienda_producto text, p_id_externo text, p_estado text,
  p_inicio timestamptz, p_fin timestamptz, p_renovacion timestamptz, p_fin_motivo text, p_version bigint, p_id_externo_previo text)
returns table(outcome text, estado text, fin_en timestamptz, es_premium boolean)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_user uuid;
  v_owner uuid;
  v_s public.suscripciones%rowtype;
  v_outcome text;
begin
  if p_proveedor is null or p_proveedor not in ('google_play', 'app_store') or p_id_externo is null or length(btrim(p_id_externo)) = 0
     or p_entorno is null or p_entorno not in ('produccion', 'sandbox') or p_version is null
     or p_estado is null or p_estado not in ('activa', 'periodo_prueba', 'pausada', 'cancelada', 'caducada')
     or (p_fin_motivo is not null and p_fin_motivo not in ('usuario_cancelo', 'expiro', 'reembolso', 'revocada', 'contracargo', 'fallo_cobro')) then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  if not exists (select 1 from public.productos_comerciales p where p.codigo = p_producto and p.tipo_producto = 'suscripcion') then
    raise exception using errcode = 'P0001', message = 'STORE_PRODUCT_UNKNOWN';
  end if;
  -- 20261022103000: ONE lock per store key (key -> wallet everywhere), like packs.
  perform pg_advisory_xact_lock(hashtextextended('store-key:' || p_proveedor || ':' || p_id_externo, 0));
  -- 20261022102800 (F10): a JWT user without the store account token never binds a subscription.
  if p_usuario is not null and p_cuenta_token is null then
    raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
  end if;
  v_user := private.store_resolve_user(p_usuario, p_cuenta_token);
  select s.usuario_id into v_owner from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
  if found then
    if v_user is not null and v_owner <> v_user then
      raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';   -- owner D5: no silent transfer between accounts
    end if;
    v_user := v_owner;
  end if;
  if v_user is null then
    raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));

  select s.* into v_s from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo for update;
  if found then
    if v_s.usuario_id <> v_user then
      raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';
    end if;
    if v_s.version_evento is not null and v_s.version_evento > p_version then
      v_outcome := 'STALE';
    elsif v_s.version_evento = p_version and v_s.estado = p_estado and v_s.fin_en is not distinct from p_fin
          and v_s.fin_motivo is not distinct from p_fin_motivo and v_s.producto_codigo = p_producto then
      v_outcome := 'REPLAY';
    else
      update public.suscripciones s
         set producto_codigo = p_producto, estado = p_estado, inicio_en = coalesce(p_inicio, s.inicio_en), fin_en = p_fin, renovacion_en = p_renovacion,
             fin_motivo = p_fin_motivo, version_evento = p_version, entorno = p_entorno, tienda_producto_id = p_tienda_producto, actualizado_en = now()
       where s.id = v_s.id;
      v_outcome := 'UPDATED';
    end if;
  else
    insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, fin_en, renovacion_en, identificador_externo, fin_motivo,
                                      version_evento, entorno, tienda_producto_id)
    values (v_user, p_proveedor, p_producto, p_estado, coalesce(p_inicio, now()), p_fin, p_renovacion, p_id_externo, p_fin_motivo, p_version, p_entorno,
            p_tienda_producto);
    v_outcome := 'CREATED';
  end if;

  if v_outcome not in ('STALE', 'REPLAY') then
    -- Play upgrade / resubscribe: the linked (previous) token of the SAME user stops giving access
    if p_id_externo_previo is not null then
      update public.suscripciones s set estado = 'caducada', fin_motivo = 'expiro', fin_en = least(coalesce(s.fin_en, now()), now()), actualizado_en = now()
       where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo_previo and s.usuario_id = v_user and s.estado <> 'caducada';
    end if;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    select v_user, 'suscripcion_tienda', 'suscripciones', s.id,
           jsonb_build_object('proveedor', p_proveedor, 'estado', p_estado, 'fin_motivo', p_fin_motivo, 'fin_en', p_fin, 'entorno', p_entorno, 'resultado', v_outcome)
      from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
  end if;

  return query
    select v_outcome, s.estado, s.fin_en, coalesce((select e.es_premium from public.estado_premium_usuario e where e.usuario_id = v_user), false)
      from public.suscripciones s where s.proveedor = p_proveedor and s.identificador_externo = p_id_externo;
end;
$$;

-- 6. Subscriptions the job must re-read from the store: still giving (or pending) access and at / past their end, not refreshed in the last hour.
create or replace function public.app_store_subscriptions_due(p_limit integer)
returns table(proveedor text, identificador_externo text, tienda_producto_id text, entorno text, estado text, fin_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select s.proveedor, s.identificador_externo, s.tienda_producto_id, s.entorno, s.estado, s.fin_en
    from public.suscripciones s
   where s.proveedor in ('google_play', 'app_store') and s.identificador_externo is not null
     and s.estado in ('activa', 'periodo_prueba', 'cancelada', 'pausada')
     and (s.fin_en is null or s.fin_en < now() + interval '10 minutes')
     and s.actualizado_en < now() - interval '1 hour'
   order by s.fin_en nulls first
   limit least(greatest(coalesce(p_limit, 50), 1), 200);
$$;

-- 7. Persisted cursors of the store polls (Google Voided Purchases, Apple Notification History): survive restarts and replicas; only move forward.
create table if not exists public.tienda_sondeo (
  proveedor text not null check (proveedor in ('google_play', 'app_store')),
  fuente text not null check (fuente in ('voided_purchases', 'notification_history')),
  cursor_ms bigint not null check (cursor_ms >= 0),
  ultimo_sondeo_en timestamptz not null default now(),
  constraint tienda_sondeo_pkey primary key (proveedor, fuente)
);
alter table public.tienda_sondeo enable row level security;
revoke all on table public.tienda_sondeo from public, anon, authenticated, service_role;
grant select on table public.tienda_sondeo to service_role;

create or replace function public.app_store_poll_get(p_proveedor text, p_fuente text)
returns table(cursor_ms bigint, ultimo_sondeo_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select t.cursor_ms, t.ultimo_sondeo_en from public.tienda_sondeo t where t.proveedor = p_proveedor and t.fuente = p_fuente;
$$;

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
  on conflict (proveedor, fuente) do update set cursor_ms = greatest(t.cursor_ms, excluded.cursor_ms), ultimo_sondeo_en = now()
  returning t.cursor_ms into v;
  return v;
end;
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.app_store_inbox_mark(text,text,text,text,text,text,integer)', 'public.app_store_inbox_purge(integer)',
    'public.app_store_reconcile_orphans(integer,boolean)',
    'public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)',
    'public.app_store_subscriptions_due(integer)', 'public.app_store_poll_get(text,text)', 'public.app_store_poll_advance(text,text,bigint)']
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$$;

commit;
