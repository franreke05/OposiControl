-- =============================================================================
-- V1 -- store payments: concurrency and inbox lifecycle after the OposiControl re-audit of 94a867c + 9e4ecd0 (2026-10-19). Corrective;
-- 20261022102600 and 20261022102800 are NOT edited.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022102800.
--
-- WHAT IT DOES (lock order everywhere: store-key advisory lock, then the wallet advisory lock, then the row)
--   * NEW-1 / NEW-2: app_store_credit_purchase, app_store_refund_purchase and app_store_refund_reversed take ONE advisory lock per
--     (provider, store key) before they look for the order or the orphans, so a refund / reversal that arrives while the purchase is being credited
--     waits for it and is applied to the order (never left as an orphan of a paid order). Safety net for anything left behind earlier:
--     app_store_reconcile_orphans (job) re-links orphan rows whose order now exists (refund orphans only when refunds are on).
--   * NEW-3: app_store_inbox_put claims the row it creates (or a waiting row it finds) with a token + 5-minute lease, like
--     app_store_inbox_pending; app_store_inbox_renew extends the lease of the holder; app_store_inbox_mark only applies for the holder of the claim
--     and never touches a row that is already processed (its result is never overwritten).
--   * NEW-4: dead-letter state 'muerto' (after N attempts, N given by the caller, 0 = never; app_store_inbox_requeue puts it back), stats with
--     dead letters and orphans (huerfano_reembolso / huerfano_reversion), app_store_inbox_purge(days >= 30) deletes old processed rows ONLY when
--     called (retention is an owner / legal input; nothing is deleted by default) and never an orphan or held row.
--   * NEW-6: the REVERSAL_EARLY marker is consumed (REVERSAL_CONSUMED) by the one refund it sends to review.
-- NOT CHANGED: admin_payment_order_resolve (Control), refund policy P2, SEC-V1-05 hold, ECO-04, F10, re-purchase rule.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the functions return to their 20261022102800 / 20261022102600 bodies.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_reconcile_orphans(integer, boolean);
-- drop function if exists public.app_store_inbox_purge(integer);
-- drop function if exists public.app_store_inbox_requeue(text, text);
-- drop function if exists public.app_store_inbox_renew(text, text, text);
-- drop function if exists public.app_store_inbox_mark(text, text, text, text, text, text, integer);
-- drop function if exists public.app_store_inbox_pending(integer, boolean);
-- drop function if exists public.app_store_inbox_put(text, text, text, text, text, text, jsonb);
-- update public.tienda_bandeja set estado = 'pendiente' where estado = 'muerto';
-- alter table public.tienda_bandeja drop constraint if exists tienda_bandeja_estado_check;
-- alter table public.tienda_bandeja add constraint tienda_bandeja_estado_check check (estado in ('pendiente', 'procesado', 'descartado', 'retenido'));
-- alter table public.tienda_bandeja drop column if exists reclamado_por;
-- create or replace function public.app_store_inbox_put(p_proveedor text, p_evento_id text, p_tipo text, p_clave text, p_entorno text, p_huella text, p_datos jsonb)
-- returns table(nuevo boolean, estado text, resultado text, misma_huella boolean)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_rows integer;
--   v_b public.tienda_bandeja%rowtype;
-- begin
--   if p_proveedor is null or p_evento_id is null or p_tipo is null or p_huella is null then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   insert into public.tienda_bandeja (proveedor, evento_id, tipo, clave_compra, entorno, huella, datos)
--   values (p_proveedor, p_evento_id, p_tipo, p_clave, p_entorno, p_huella, coalesce(p_datos, '{}'::jsonb))
--   on conflict (proveedor, evento_id) do nothing;
--   get diagnostics v_rows = row_count;
--   select b.* into v_b from public.tienda_bandeja b where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
--   return query select v_rows = 1, v_b.estado, v_b.resultado, v_b.huella = p_huella;
-- end;
-- $$;
-- create function public.app_store_inbox_pending(p_limit integer, p_incluir_retenidos boolean)
-- returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz)
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
--   update public.tienda_bandeja b set reclamado_hasta = now() + interval '5 minutes'
--     from candidatos k
--    where b.proveedor = k.p and b.evento_id = k.e
--   returning b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en;
-- end;
-- $$;
-- create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text)
-- returns void
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- begin
--   if p_estado not in ('pendiente', 'procesado', 'descartado', 'retenido') then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   -- a processed row never goes back; 'retenido' (refunds off) is not an attempt; 'pendiente' (a real failure) backs off 1 min .. 6 h, never dropped
--   update public.tienda_bandeja b
--      set estado = case when b.estado = 'procesado' then b.estado else p_estado end,
--          resultado = coalesce(p_resultado, b.resultado),
--          ultimo_error = left(p_error, 300),
--          intentos = b.intentos + case when p_estado = 'retenido' then 0 else 1 end,
--          proximo_intento_en = case when p_estado = 'pendiente' and b.estado <> 'procesado'
--                                    then now() + least(interval '6 hours', interval '1 minute' * power(2, least(b.intentos, 12)))
--                                    else null end,
--          reclamado_hasta = null,
--          procesado_en = case when p_estado in ('procesado', 'descartado') then coalesce(b.procesado_en, now()) else b.procesado_en end
--    where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
-- end;
-- $$;
-- create or replace function public.app_store_inbox_stats()
-- returns table(estado text, n bigint, mas_antiguo timestamptz, max_intentos integer)
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select b.estado, count(*), min(b.recibido_en), max(b.intentos)
--     from public.tienda_bandeja b
--    where b.estado in ('pendiente', 'retenido')
--    group by b.estado;
-- $$;
-- create or replace function public.app_store_credit_purchase(
--   p_usuario uuid, p_cuenta_token uuid, p_proveedor text, p_entorno text, p_producto text, p_tienda_producto text, p_clave text, p_auditoria jsonb)
-- returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
--               moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_user uuid;
--   v_owner uuid;
--   v_prod public.productos_comerciales%rowtype;
--   v_o public.compras_usuario%rowtype;
--   v_rf record;
-- begin
--   if p_proveedor is null or p_proveedor not in ('google_play', 'app_store') or p_clave is null or length(btrim(p_clave)) = 0 or length(p_clave) > 4096
--      or p_entorno is null or p_entorno not in ('produccion', 'sandbox') or p_producto is null then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   -- credit by SKU (owner D1): quantity and price come from the catalogue; the store already charged, so neither `activo` nor the daily cap applies
--   select p.* into v_prod from public.productos_comerciales p
--    where p.codigo = p_producto and p.tipo_producto = 'pack_tickets' and p.cantidad_tickets > 0 and p.precio_centimos > 0;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'STORE_PRODUCT_UNKNOWN';
--   end if;
--   -- 20261022102800 (F10): a JWT user without the store account token never binds a purchase (no implicit trust in p_usuario).
--   if p_usuario is not null and p_cuenta_token is null then
--     raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
--   end if;
--   v_user := private.store_resolve_user(p_usuario, p_cuenta_token);
--   select c.usuario_id into v_owner from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave;
--   if found then
--     if v_user is not null and v_owner <> v_user then
--       raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';
--     end if;
--     v_user := v_owner;
--   end if;
--   if v_user is null then
--     raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
--   end if;
--   if not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = v_user) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--
--   insert into public.compras_usuario (usuario_id, proveedor, producto_codigo, tipo_producto, estado, precio_centimos, moneda, cantidad_tickets, expira_en,
--                                       identificador_transaccion, entorno, tienda_producto_id, payload_validacion)
--   values (v_user, p_proveedor, v_prod.codigo, 'opositickets', 'pendiente', v_prod.precio_centimos, v_prod.moneda, v_prod.cantidad_tickets,
--           now() + interval '30 minutes', p_clave, p_entorno, p_tienda_producto, jsonb_build_object('tienda', coalesce(p_auditoria, '{}'::jsonb)))
--   on conflict (proveedor, identificador_transaccion) where identificador_transaccion is not null do nothing;
--   select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
--   if v_o.usuario_id <> v_user then
--     raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';   -- another user won a concurrent insert
--   end if;
--   if v_o.cantidad_tickets is null or v_o.producto_codigo <> v_prod.codigo then
--     raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_INVALID';
--   end if;
--
--   -- refunds that arrived BEFORE the purchase existed: apply them first (SEC-V1-05 hold), so the credit below credits nothing
--   for v_rf in
--     select b.evento_id from public.tienda_bandeja b
--      where b.proveedor = p_proveedor and b.clave_compra = p_clave and b.tipo = 'REEMBOLSO' and b.resultado = 'REFUND_ORPHAN_HELD'
--      order by b.recibido_en
--   loop
--     perform 1 from public.app_refund_payment_order(p_proveedor, v_rf.evento_id, v_o.id);
--     update public.tienda_bandeja b set resultado = 'REFUND_HELD' where b.proveedor = p_proveedor and b.evento_id = v_rf.evento_id;
--   end loop;
--
--   -- 20261022102800 (F2/F12): reversals that arrived BEFORE the purchase existed are attached to the order (REVERSAL_EARLY), so a later refund
--   -- of this order goes to review instead of debiting.
--   for v_rf in
--     select b.evento_id from public.tienda_bandeja b
--      where b.proveedor = p_proveedor and b.clave_compra = p_clave and b.tipo = 'REEMBOLSO_REVERTIDO' and b.resultado = 'REVERSAL_ORPHAN_HELD'
--      order by b.recibido_en
--   loop
--     insert into public.eventos_pago (proveedor, evento_id, compra_id, resultado) values (p_proveedor, v_rf.evento_id, v_o.id, 'REVERSAL_EARLY')
--     on conflict (proveedor, evento_id) do nothing;
--     update public.tienda_bandeja b set resultado = 'REVERSAL_HELD' where b.proveedor = p_proveedor and b.evento_id = v_rf.evento_id;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'reembolso_revertido_anticipado', 'compras_usuario', v_o.id, jsonb_build_object('proveedor', p_proveedor, 'evento_id', v_rf.evento_id));
--   end loop;
--
--   return query select * from public.app_credit_payment_order(p_proveedor, 'compra:' || encode(sha256(convert_to(p_clave, 'utf8')), 'hex'), v_o.id,
--                                                              p_clave, v_o.precio_centimos, v_o.moneda, v_o.producto_codigo);
-- end;
-- $$;
-- create or replace function public.app_store_refund_purchase(p_proveedor text, p_evento_id text, p_clave text)
-- returns table(outcome text, orden_id uuid, estado text, saldo integer)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_id uuid;
-- begin
--   if p_proveedor is null or p_evento_id is null or p_clave is null or length(btrim(p_clave)) = 0 then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   select c.id into v_id from public.compras_usuario c
--    where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
--   if not found then
--     update public.tienda_bandeja b set resultado = 'REFUND_ORPHAN_HELD', clave_compra = p_clave
--      where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.tipo = 'REEMBOLSO';
--     if not found then
--       raise exception using errcode = 'P0001', message = 'STORE_EVENT_NOT_IN_INBOX';
--     end if;
--     return query select 'REFUND_ORPHAN_HELD'::text, null::uuid, null::text, null::integer;
--     return;
--   end if;
--   return query select r.outcome, r.orden_id, r.estado, r.saldo from public.app_refund_payment_order(p_proveedor, p_evento_id, v_id) r;
-- end;
-- $$;
-- create or replace function public.app_store_refund_reversed(p_proveedor text, p_evento_id text, p_clave text)
-- returns text
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_user uuid;
--   v_o public.compras_usuario%rowtype;
--   v_rows integer;
--   v_outcome text;
-- begin
--   if p_proveedor is null or p_evento_id is null or p_clave is null or length(btrim(p_clave)) = 0 then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   select c.usuario_id into v_user from public.compras_usuario c
--    where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
--   if not found then
--     -- unknown key: held on its inbox row and attached to the order when the purchase arrives (app_store_credit_purchase)
--     update public.tienda_bandeja b set resultado = 'REVERSAL_ORPHAN_HELD', clave_compra = p_clave
--      where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.tipo = 'REEMBOLSO_REVERTIDO';
--     if not found then
--       raise exception using errcode = 'P0001', message = 'STORE_EVENT_NOT_IN_INBOX';
--     end if;
--     return 'REVERSAL_ORPHAN_HELD';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--   select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
--   insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id) on conflict (proveedor, evento_id) do nothing;
--   get diagnostics v_rows = row_count;
--   if v_rows = 0 then
--     return 'REPLAY';
--   end if;
--   if v_o.estado = 'reembolsada' then
--     update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;   -- D3: Control decides, never an automatic re-credit
--     v_outcome := 'EN_REVISION';
--     update public.eventos_pago e set resultado = 'REFUND_REVERSED_EN_REVISION' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--   else
--     -- not refunded (yet): the reversal is RECORDED; a later refund of this order goes to review instead of debiting
--     v_outcome := 'REVERSAL_RECORDED';
--     update public.eventos_pago e set resultado = 'REVERSAL_EARLY' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--   end if;
--   insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--   values (v_o.usuario_id, 'reembolso_revertido', 'compras_usuario', v_o.id,
--           jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'resultado', v_outcome));
--   return v_outcome;
-- end;
-- $$;
-- create or replace function public.app_refund_payment_order(p_proveedor text, p_evento_id text, p_orden uuid)
-- returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
--               moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_o public.compras_usuario%rowtype;
--   v_user uuid;
--   v_credit public.transacciones_cartera%rowtype;
--   v_rows integer;
--   v_new boolean;
--   v_unspent integer;
--   v_spent integer;
--   v_outcome text;
--   v_now timestamptz;
--   v_cut record;
--   v_access jsonb := '[]'::jsonb;
--   v_before timestamptz;
--   v_after timestamptz;
-- begin
--   if p_proveedor is null or p_evento_id is null or length(btrim(p_evento_id)) = 0 or p_orden is null then
--     raise exception using errcode = 'P0001', message = 'EVENT_INVALID';
--   end if;
--   -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row).
--   select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.proveedor = p_proveedor and c.cantidad_tickets is not null;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--   select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;
--
--   insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id)
--   on conflict (proveedor, evento_id) do nothing;
--   get diagnostics v_rows = row_count;
--   v_new := v_rows = 1;
--   if not v_new then
--     -- ECO-04 (20261022102600): the event id is already bound to ANOTHER order (or to an order that no longer exists). Not a silent REPLAY: this
--     -- order goes to review and the reuse is audited; nothing is credited or reversed.
--     if exists (select 1 from public.eventos_pago e where e.proveedor = p_proveedor and e.evento_id = p_evento_id and e.compra_id is distinct from v_o.id) then
--       if v_o.estado <> 'en_revision' then
--         update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--       end if;
--       insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--       values (v_o.usuario_id, 'evento_de_otro_pedido', 'compras_usuario', v_o.id,
--               jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado,
--                                  'pedido_del_evento', (select e.compra_id from public.eventos_pago e where e.proveedor = p_proveedor and e.evento_id = p_evento_id)));
--       return query select * from private.payment_order_result('EN_REVISION', v_o.id);
--       return;
--     end if;
--     return query select * from private.payment_order_result('REPLAY', v_o.id);
--     return;
--   end if;
--
--   if v_o.estado = 'reembolsada' then
--     v_outcome := 'REPLAY';                               -- a second refund of a refunded order is a no-op
--   elsif v_o.estado <> 'pagada' then
--     -- SEC-V1-05: out-of-order refund (Google RTDN / Apple deliver out of order). HOLD it; never NOOP, never reverse here.
--     if v_o.estado <> 'en_revision' then
--       update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--     end if;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'reembolso_retenido', 'compras_usuario', v_o.id,
--             jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado,
--                                'acreditada', exists (select 1 from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets')));
--     update public.eventos_pago e set resultado = 'REFUND_HELD' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--     return query select * from private.payment_order_result('EN_REVISION', v_o.id);
--     return;
--   elsif private.payment_order_reversal_early(v_o.id) then
--     -- 20261022102800 (F2): the store already REVERSED this refund (the reversal arrived first). Never debit: review, audited.
--     update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'reembolso_tras_reversion', 'compras_usuario', v_o.id, jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id));
--     update public.eventos_pago e set resultado = 'REFUND_AFTER_REVERSAL' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--     return query select * from private.payment_order_result('EN_REVISION', v_o.id);
--     return;
--   else
--     select t.* into v_credit from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets';
--     v_unspent := private.ot_credit_remaining(v_credit.id);
--     v_spent := v_credit.cantidad - v_unspent;
--     v_now := clock_timestamp();  -- DB clock, after the wallet lock
--     -- P2: shorten the still-valid purchase access financed by this credit (one pass per traced exam debit, FIFO already decided by ot_consumos).
--     for v_cut in
--       select d.id as debit_id, d.referencia_id as examen_id, k.cantidad as consumed, -d.cantidad as price, coalesce(nullif(d.metadatos ->> 'dias', '')::integer, e.dias_acceso_compra) as dias
--         from public.ot_consumos k
--         join public.transacciones_cartera d on d.id = k.debit_tx_id
--         join public.examenes e on e.id = d.referencia_id
--        where k.credit_tx_id = v_credit.id and d.tipo = 'compra_examen' and d.cantidad < 0 and e.dias_acceso_compra > 0
--        order by d.seq
--     loop
--       select g.valido_hasta into v_before from public.contenido_desbloqueado_usuario g
--        where g.usuario_id = v_o.usuario_id and g.examen_id = v_cut.examen_id and g.motivo = 'compra' and g.valido_hasta > v_now for update;
--       if found then
--         v_after := greatest(v_now, v_before - ((v_cut.dias::numeric * v_cut.consumed::numeric / v_cut.price::numeric) * interval '1 day'));
--         update public.contenido_desbloqueado_usuario g set valido_hasta = v_after
--          where g.usuario_id = v_o.usuario_id and g.examen_id = v_cut.examen_id and g.motivo = 'compra' and v_after < g.valido_hasta;
--         v_access := v_access || jsonb_build_array(jsonb_build_object('acceso_acortado', v_cut.examen_id, 'debito', v_cut.debit_id,
--                       'ot_financiados', v_cut.consumed, 'precio', v_cut.price, 'dias_compra', v_cut.dias, 'valido_hasta_antes', v_before, 'valido_hasta_despues', v_after));
--       else
--         v_access := v_access || jsonb_build_array(jsonb_build_object('acceso_expirado_o_ausente', v_cut.examen_id, 'debito', v_cut.debit_id, 'ot_financiados', v_cut.consumed));
--       end if;
--     end loop;
--     if v_unspent > 0 then
--       insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
--       values (v_o.usuario_id, 'devolucion', -v_unspent, 'Reembolso de OposiTickets', 'orden', v_credit.id, v_o.id, 'reembolso:' || v_o.id::text,
--               jsonb_build_object('op', 'refund_reversal', 'proveedor', p_proveedor));
--     end if;
--     update public.compras_usuario c set estado = 'reembolsada' where c.id = v_o.id;
--     if v_spent > 0 then
--       insert into public.refund_case (compra_id, usuario_id, ot_revertidos, ot_gastados, detalle, estado, politica, resuelto_en)
--       select v_o.id, v_o.usuario_id, v_unspent, v_spent,
--              v_access || coalesce((select jsonb_agg(jsonb_build_object('debito', d.id, 'ot_desde_este_credito', k.cantidad, 'importe_debito', -d.cantidad,
--                                                           'tipo', d.tipo, 'referencia_tipo', d.referencia_tipo, 'referencia_id', d.referencia_id,
--                                                           'creado_en', d.creado_en) order by d.seq)
--                          from public.ot_consumos k join public.transacciones_cartera d on d.id = k.debit_tx_id
--                         where k.credit_tx_id = v_credit.id and d.tipo <> 'devolucion'), '[]'::jsonb), 'resuelto', 'P2', v_now;
--     end if;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'pago_reembolsado', 'compras_usuario', v_o.id,
--             jsonb_build_object('proveedor', p_proveedor, 'revertidos', v_unspent, 'gastados', v_spent));
--     v_outcome := case when v_spent > 0 then 'REFUNDED_P2' else 'REFUNDED' end;
--   end if;
--
--   update public.eventos_pago e set resultado = v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--   return query select * from private.payment_order_result(v_outcome, v_o.id);
-- end;
-- $$;
-- revoke all on function public.app_store_inbox_put(text, text, text, text, text, text, jsonb) from public, anon, authenticated;
-- grant execute on function public.app_store_inbox_put(text, text, text, text, text, text, jsonb) to service_role;
-- revoke all on function public.app_store_inbox_pending(integer, boolean) from public, anon, authenticated;
-- grant execute on function public.app_store_inbox_pending(integer, boolean) to service_role;
-- revoke all on function public.app_store_inbox_mark(text, text, text, text, text) from public, anon, authenticated;
-- grant execute on function public.app_store_inbox_mark(text, text, text, text, text) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_inbox_put(text,text,text,text,text,text,jsonb)'), ('public.app_store_inbox_pending(integer,boolean)'),
--                ('public.app_store_inbox_mark(text,text,text,text,text,text,integer)'), ('public.app_store_inbox_renew(text,text,text)'),
--                ('public.app_store_inbox_requeue(text,text)'), ('public.app_store_inbox_purge(integer)'), ('public.app_store_inbox_stats()'),
--                ('public.app_store_reconcile_orphans(integer,boolean)'), ('public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)'),
--                ('public.app_store_refund_purchase(text,text,text)'), ('public.app_store_refund_reversed(text,text,text)'),
--                ('public.app_refund_payment_order(text,text,uuid)')) f(n)
-- union all
-- select 'old_mark_signature_left', (case when to_regprocedure('public.app_store_inbox_mark(text,text,text,text,text)') is null then 0 else 1 end)::bigint
-- union all
-- select 'store_key_lock_missing:' || f.n,
--        (case when (select p.prosrc from pg_proc p where p.oid = f.n::regprocedure) like '%store-key:%' then 0 else 1 end)::bigint
--   from (values ('public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)'), ('public.app_store_refund_purchase(text,text,text)'),
--                ('public.app_store_refund_reversed(text,text,text)')) f(n)
-- union all
-- select 'reversal_consume_missing', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_refund_payment_order(text,text,uuid)'::regprocedure) like '%REVERSAL_CONSUMED%' then 0 else 1 end)::bigint
-- union all
-- select 'dead_letter_state_missing', (case when exists (select 1 from pg_constraint where conname = 'tienda_bandeja_estado_check' and pg_get_constraintdef(oid) like '%muerto%') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_store_inbox_pending(integer,boolean)') is null or to_regprocedure('private.payment_order_reversal_early(uuid)') is null
     or to_regprocedure('public.app_store_inbox_mark(text,text,text,text,text)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_CONCURRENCY_PREFLIGHT', hint = 'apply 20261022102800 first';
  end if;
end
$$;

-- 1. Inbox: claim token, dead-letter state
alter table public.tienda_bandeja add column if not exists reclamado_por text;
alter table public.tienda_bandeja drop constraint if exists tienda_bandeja_estado_check;
alter table public.tienda_bandeja add constraint tienda_bandeja_estado_check check (estado in ('pendiente', 'procesado', 'descartado', 'retenido', 'muerto'));

drop function if exists public.app_store_inbox_put(text, text, text, text, text, text, jsonb);
create function public.app_store_inbox_put(p_proveedor text, p_evento_id text, p_tipo text, p_clave text, p_entorno text, p_huella text, p_datos jsonb)
returns table(nuevo boolean, estado text, resultado text, misma_huella boolean, reclamo text)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_rows integer;
  v_b public.tienda_bandeja%rowtype;
  v_token text := gen_random_uuid()::text;
begin
  if p_proveedor is null or p_evento_id is null or p_tipo is null or p_huella is null then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  insert into public.tienda_bandeja (proveedor, evento_id, tipo, clave_compra, entorno, huella, datos, reclamado_por, reclamado_hasta)
  values (p_proveedor, p_evento_id, p_tipo, p_clave, p_entorno, p_huella, coalesce(p_datos, '{}'::jsonb), v_token, now() + interval '5 minutes')
  on conflict (proveedor, evento_id) do nothing;
  get diagnostics v_rows = row_count;
  if v_rows = 0 then
    -- a redelivery claims a row that is waiting for work, unless another worker holds it (then the caller must not process it)
    update public.tienda_bandeja b set reclamado_por = v_token, reclamado_hasta = now() + interval '5 minutes'
     where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.estado in ('pendiente', 'retenido')
       and (b.reclamado_hasta is null or b.reclamado_hasta < now());
    if not found then
      v_token := null;
    end if;
  end if;
  select b.* into v_b from public.tienda_bandeja b where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
  return query select v_rows = 1, v_b.estado, v_b.resultado, v_b.huella = p_huella, v_token;
end;
$$;

drop function if exists public.app_store_inbox_pending(integer, boolean);
create function public.app_store_inbox_pending(p_limit integer, p_incluir_retenidos boolean)
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
            or (coalesce(p_incluir_retenidos, false) and b.estado = 'retenido'))
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

create function public.app_store_inbox_renew(p_proveedor text, p_evento_id text, p_reclamo text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.tienda_bandeja b set reclamado_hasta = now() + interval '5 minutes'
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.reclamado_por = p_reclamo and b.estado in ('pendiente', 'retenido');
  return found;
end;
$$;

drop function if exists public.app_store_inbox_mark(text, text, text, text, text);
create function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text, p_reclamo text, p_max_intentos integer)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_estado not in ('pendiente', 'procesado', 'descartado', 'retenido') then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- only the holder of the claim marks, and only a row still waiting for work: a processed (or discarded / dead) row and its result never change.
  -- 'retenido' (refunds off) is not an attempt; 'pendiente' (a real failure) backs off 1 min .. 6 h, and becomes 'muerto' after p_max_intentos
  -- attempts when the caller asks for a dead letter (0 / null = never).
  update public.tienda_bandeja b
     set estado = case when p_estado = 'pendiente' and coalesce(p_max_intentos, 0) > 0 and b.intentos + 1 >= p_max_intentos then 'muerto' else p_estado end,
         resultado = coalesce(p_resultado, b.resultado),
         ultimo_error = left(p_error, 300),
         intentos = b.intentos + case when p_estado = 'retenido' then 0 else 1 end,
         proximo_intento_en = case when p_estado = 'pendiente' then now() + least(interval '6 hours', interval '1 minute' * power(2, least(b.intentos, 12))) else null end,
         reclamado_por = null,
         reclamado_hasta = null,
         procesado_en = case when p_estado in ('procesado', 'descartado') then coalesce(b.procesado_en, now()) else b.procesado_en end
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.estado in ('pendiente', 'retenido')
     and b.reclamado_por is not distinct from p_reclamo;
  return found;
end;
$$;

create function public.app_store_inbox_requeue(p_proveedor text, p_evento_id text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.tienda_bandeja b set estado = 'pendiente', intentos = 0, proximo_intento_en = null, reclamado_por = null, reclamado_hasta = null
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.estado = 'muerto';
  return found;
end;
$$;

-- Retention: an owner / legal input. Called by the job ONLY when PAYMENTS_STORE_INBOX_RETENTION_DAYS is set; at least 30 days; orphans and held rows
-- (they still decide money) are never purged.
create function public.app_store_inbox_purge(p_dias integer)
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
     and coalesce(b.resultado, '') not in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD', 'REFUND_HELD', 'REVERSAL_HELD');
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;

create or replace function public.app_store_inbox_stats()
returns table(estado text, n bigint, mas_antiguo timestamptz, max_intentos integer)
language sql
stable
security definer
set search_path = ''
as $$
  select b.estado, count(*), min(b.recibido_en), max(b.intentos)
    from public.tienda_bandeja b
   where b.estado in ('pendiente', 'retenido', 'muerto')
   group by b.estado
  union all
  select case b.resultado when 'REFUND_ORPHAN_HELD' then 'huerfano_reembolso' else 'huerfano_reversion' end, count(*), min(b.recibido_en), max(b.intentos)
    from public.tienda_bandeja b
   where b.resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD')
   group by 1;
$$;

-- 2. Per-key lock (NEW-1 / NEW-2) and the consumed reversal marker (NEW-6)
create or replace function public.app_store_credit_purchase(
  p_usuario uuid, p_cuenta_token uuid, p_proveedor text, p_entorno text, p_producto text, p_tienda_producto text, p_clave text, p_auditoria jsonb)
returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
              moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_user uuid;
  v_owner uuid;
  v_prod public.productos_comerciales%rowtype;
  v_o public.compras_usuario%rowtype;
  v_rf record;
begin
  if p_proveedor is null or p_proveedor not in ('google_play', 'app_store') or p_clave is null or length(btrim(p_clave)) = 0 or length(p_clave) > 4096
     or p_entorno is null or p_entorno not in ('produccion', 'sandbox') or p_producto is null then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- credit by SKU (owner D1): quantity and price come from the catalogue; the store already charged, so neither `activo` nor the daily cap applies
  select p.* into v_prod from public.productos_comerciales p
   where p.codigo = p_producto and p.tipo_producto = 'pack_tickets' and p.cantidad_tickets > 0 and p.precio_centimos > 0;
  if not found then
    raise exception using errcode = 'P0001', message = 'STORE_PRODUCT_UNKNOWN';
  end if;
  -- 20261022102900 (NEW-1/NEW-2): ONE lock per store key, taken BEFORE the wallet lock (key -> wallet everywhere). The credit, the refund and the
  -- reversal of the same purchase are serialised, so none of them can commit an orphan the other already looked for.
  perform pg_advisory_xact_lock(hashtextextended('store-key:' || p_proveedor || ':' || p_clave, 0));
  -- 20261022102800 (F10): a JWT user without the store account token never binds a purchase (no implicit trust in p_usuario).
  if p_usuario is not null and p_cuenta_token is null then
    raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
  end if;
  v_user := private.store_resolve_user(p_usuario, p_cuenta_token);
  select c.usuario_id into v_owner from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave;
  if found then
    if v_user is not null and v_owner <> v_user then
      raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';
    end if;
    v_user := v_owner;
  end if;
  if v_user is null then
    raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
  end if;
  if not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = v_user) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));

  insert into public.compras_usuario (usuario_id, proveedor, producto_codigo, tipo_producto, estado, precio_centimos, moneda, cantidad_tickets, expira_en,
                                      identificador_transaccion, entorno, tienda_producto_id, payload_validacion)
  values (v_user, p_proveedor, v_prod.codigo, 'opositickets', 'pendiente', v_prod.precio_centimos, v_prod.moneda, v_prod.cantidad_tickets,
          now() + interval '30 minutes', p_clave, p_entorno, p_tienda_producto, jsonb_build_object('tienda', coalesce(p_auditoria, '{}'::jsonb)))
  on conflict (proveedor, identificador_transaccion) where identificador_transaccion is not null do nothing;
  select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
  if v_o.usuario_id <> v_user then
    raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_BOUND_TO_OTHER';   -- another user won a concurrent insert
  end if;
  if v_o.cantidad_tickets is null or v_o.producto_codigo <> v_prod.codigo then
    raise exception using errcode = 'P0001', message = 'STORE_PURCHASE_INVALID';
  end if;

  -- refunds that arrived BEFORE the purchase existed: apply them first (SEC-V1-05 hold), so the credit below credits nothing
  for v_rf in
    select b.evento_id from public.tienda_bandeja b
     where b.proveedor = p_proveedor and b.clave_compra = p_clave and b.tipo = 'REEMBOLSO' and b.resultado = 'REFUND_ORPHAN_HELD'
     order by b.recibido_en
  loop
    perform 1 from public.app_refund_payment_order(p_proveedor, v_rf.evento_id, v_o.id);
    update public.tienda_bandeja b set resultado = 'REFUND_HELD' where b.proveedor = p_proveedor and b.evento_id = v_rf.evento_id;
  end loop;

  -- 20261022102800 (F2/F12): reversals that arrived BEFORE the purchase existed are attached to the order (REVERSAL_EARLY), so a later refund
  -- of this order goes to review instead of debiting.
  for v_rf in
    select b.evento_id from public.tienda_bandeja b
     where b.proveedor = p_proveedor and b.clave_compra = p_clave and b.tipo = 'REEMBOLSO_REVERTIDO' and b.resultado = 'REVERSAL_ORPHAN_HELD'
     order by b.recibido_en
  loop
    insert into public.eventos_pago (proveedor, evento_id, compra_id, resultado) values (p_proveedor, v_rf.evento_id, v_o.id, 'REVERSAL_EARLY')
    on conflict (proveedor, evento_id) do nothing;
    update public.tienda_bandeja b set resultado = 'REVERSAL_HELD' where b.proveedor = p_proveedor and b.evento_id = v_rf.evento_id;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'reembolso_revertido_anticipado', 'compras_usuario', v_o.id, jsonb_build_object('proveedor', p_proveedor, 'evento_id', v_rf.evento_id));
  end loop;

  return query select * from public.app_credit_payment_order(p_proveedor, 'compra:' || encode(sha256(convert_to(p_clave, 'utf8')), 'hex'), v_o.id,
                                                             p_clave, v_o.precio_centimos, v_o.moneda, v_o.producto_codigo);
end;
$$;

create or replace function public.app_store_refund_purchase(p_proveedor text, p_evento_id text, p_clave text)
returns table(outcome text, orden_id uuid, estado text, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_id uuid;
begin
  if p_proveedor is null or p_evento_id is null or p_clave is null or length(btrim(p_clave)) = 0 then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- 20261022102900 (NEW-1/NEW-2): ONE lock per store key, taken BEFORE the wallet lock (key -> wallet everywhere). The credit, the refund and the
  -- reversal of the same purchase are serialised, so none of them can commit an orphan the other already looked for.
  perform pg_advisory_xact_lock(hashtextextended('store-key:' || p_proveedor || ':' || p_clave, 0));
  select c.id into v_id from public.compras_usuario c
   where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
  if not found then
    update public.tienda_bandeja b set resultado = 'REFUND_ORPHAN_HELD', clave_compra = p_clave
     where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.tipo = 'REEMBOLSO';
    if not found then
      raise exception using errcode = 'P0001', message = 'STORE_EVENT_NOT_IN_INBOX';
    end if;
    return query select 'REFUND_ORPHAN_HELD'::text, null::uuid, null::text, null::integer;
    return;
  end if;
  return query select r.outcome, r.orden_id, r.estado, r.saldo from public.app_refund_payment_order(p_proveedor, p_evento_id, v_id) r;
end;
$$;

create or replace function public.app_store_refund_reversed(p_proveedor text, p_evento_id text, p_clave text)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid;
  v_o public.compras_usuario%rowtype;
  v_rows integer;
  v_outcome text;
begin
  if p_proveedor is null or p_evento_id is null or p_clave is null or length(btrim(p_clave)) = 0 then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- 20261022102900 (NEW-1/NEW-2): ONE lock per store key, taken BEFORE the wallet lock (key -> wallet everywhere). The credit, the refund and the
  -- reversal of the same purchase are serialised, so none of them can commit an orphan the other already looked for.
  perform pg_advisory_xact_lock(hashtextextended('store-key:' || p_proveedor || ':' || p_clave, 0));
  select c.usuario_id into v_user from public.compras_usuario c
   where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
  if not found then
    -- unknown key: held on its inbox row and attached to the order when the purchase arrives (app_store_credit_purchase)
    update public.tienda_bandeja b set resultado = 'REVERSAL_ORPHAN_HELD', clave_compra = p_clave
     where b.proveedor = p_proveedor and b.evento_id = p_evento_id and b.tipo = 'REEMBOLSO_REVERTIDO';
    if not found then
      raise exception using errcode = 'P0001', message = 'STORE_EVENT_NOT_IN_INBOX';
    end if;
    return 'REVERSAL_ORPHAN_HELD';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
  select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
  insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id) on conflict (proveedor, evento_id) do nothing;
  get diagnostics v_rows = row_count;
  if v_rows = 0 then
    return 'REPLAY';
  end if;
  if v_o.estado = 'reembolsada' then
    update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;   -- D3: Control decides, never an automatic re-credit
    v_outcome := 'EN_REVISION';
    update public.eventos_pago e set resultado = 'REFUND_REVERSED_EN_REVISION' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
  else
    -- not refunded (yet): the reversal is RECORDED; a later refund of this order goes to review instead of debiting
    v_outcome := 'REVERSAL_RECORDED';
    update public.eventos_pago e set resultado = 'REVERSAL_EARLY' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
  end if;
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (v_o.usuario_id, 'reembolso_revertido', 'compras_usuario', v_o.id,
          jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'resultado', v_outcome));
  return v_outcome;
end;
$$;

create or replace function public.app_refund_payment_order(p_proveedor text, p_evento_id text, p_orden uuid)
returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
              moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_o public.compras_usuario%rowtype;
  v_user uuid;
  v_credit public.transacciones_cartera%rowtype;
  v_rows integer;
  v_new boolean;
  v_unspent integer;
  v_spent integer;
  v_outcome text;
  v_now timestamptz;
  v_cut record;
  v_access jsonb := '[]'::jsonb;
  v_before timestamptz;
  v_after timestamptz;
begin
  if p_proveedor is null or p_evento_id is null or length(btrim(p_evento_id)) = 0 or p_orden is null then
    raise exception using errcode = 'P0001', message = 'EVENT_INVALID';
  end if;
  -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row).
  select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.proveedor = p_proveedor and c.cantidad_tickets is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
  select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;

  insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id)
  on conflict (proveedor, evento_id) do nothing;
  get diagnostics v_rows = row_count;
  v_new := v_rows = 1;
  if not v_new then
    -- ECO-04 (20261022102600): the event id is already bound to ANOTHER order (or to an order that no longer exists). Not a silent REPLAY: this
    -- order goes to review and the reuse is audited; nothing is credited or reversed.
    if exists (select 1 from public.eventos_pago e where e.proveedor = p_proveedor and e.evento_id = p_evento_id and e.compra_id is distinct from v_o.id) then
      if v_o.estado <> 'en_revision' then
        update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
      end if;
      insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
      values (v_o.usuario_id, 'evento_de_otro_pedido', 'compras_usuario', v_o.id,
              jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado,
                                 'pedido_del_evento', (select e.compra_id from public.eventos_pago e where e.proveedor = p_proveedor and e.evento_id = p_evento_id)));
      return query select * from private.payment_order_result('EN_REVISION', v_o.id);
      return;
    end if;
    return query select * from private.payment_order_result('REPLAY', v_o.id);
    return;
  end if;

  if v_o.estado = 'reembolsada' then
    v_outcome := 'REPLAY';                               -- a second refund of a refunded order is a no-op
  elsif v_o.estado <> 'pagada' then
    -- SEC-V1-05: out-of-order refund (Google RTDN / Apple deliver out of order). HOLD it; never NOOP, never reverse here.
    if v_o.estado <> 'en_revision' then
      update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
    end if;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'reembolso_retenido', 'compras_usuario', v_o.id,
            jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado,
                               'acreditada', exists (select 1 from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets')));
    update public.eventos_pago e set resultado = 'REFUND_HELD' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
    return query select * from private.payment_order_result('EN_REVISION', v_o.id);
    return;
  elsif private.payment_order_reversal_early(v_o.id) then
    -- 20261022102800 (F2): the store already REVERSED this refund (the reversal arrived first). Never debit: review, audited.
    update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'reembolso_tras_reversion', 'compras_usuario', v_o.id, jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id));
    update public.eventos_pago e set resultado = 'REFUND_AFTER_REVERSAL' where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
    -- 20261022102900 (NEW-6): the early reversal is CONSUMED by the one refund it reverses; a later refund (e.g. after Control confirmed the order)
    -- is applied normally (policy P2) instead of going to review forever.
    update public.eventos_pago e set resultado = 'REVERSAL_CONSUMED'
     where (e.proveedor, e.evento_id) = (select e2.proveedor, e2.evento_id from public.eventos_pago e2
                                          where e2.compra_id = v_o.id and e2.resultado = 'REVERSAL_EARLY' order by e2.creado_en, e2.evento_id limit 1);
    return query select * from private.payment_order_result('EN_REVISION', v_o.id);
    return;
  else
    select t.* into v_credit from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets';
    v_unspent := private.ot_credit_remaining(v_credit.id);
    v_spent := v_credit.cantidad - v_unspent;
    v_now := clock_timestamp();  -- DB clock, after the wallet lock
    -- P2: shorten the still-valid purchase access financed by this credit (one pass per traced exam debit, FIFO already decided by ot_consumos).
    for v_cut in
      select d.id as debit_id, d.referencia_id as examen_id, k.cantidad as consumed, -d.cantidad as price, coalesce(nullif(d.metadatos ->> 'dias', '')::integer, e.dias_acceso_compra) as dias
        from public.ot_consumos k
        join public.transacciones_cartera d on d.id = k.debit_tx_id
        join public.examenes e on e.id = d.referencia_id
       where k.credit_tx_id = v_credit.id and d.tipo = 'compra_examen' and d.cantidad < 0 and e.dias_acceso_compra > 0
       order by d.seq
    loop
      select g.valido_hasta into v_before from public.contenido_desbloqueado_usuario g
       where g.usuario_id = v_o.usuario_id and g.examen_id = v_cut.examen_id and g.motivo = 'compra' and g.valido_hasta > v_now for update;
      if found then
        v_after := greatest(v_now, v_before - ((v_cut.dias::numeric * v_cut.consumed::numeric / v_cut.price::numeric) * interval '1 day'));
        update public.contenido_desbloqueado_usuario g set valido_hasta = v_after
         where g.usuario_id = v_o.usuario_id and g.examen_id = v_cut.examen_id and g.motivo = 'compra' and v_after < g.valido_hasta;
        v_access := v_access || jsonb_build_array(jsonb_build_object('acceso_acortado', v_cut.examen_id, 'debito', v_cut.debit_id,
                      'ot_financiados', v_cut.consumed, 'precio', v_cut.price, 'dias_compra', v_cut.dias, 'valido_hasta_antes', v_before, 'valido_hasta_despues', v_after));
      else
        v_access := v_access || jsonb_build_array(jsonb_build_object('acceso_expirado_o_ausente', v_cut.examen_id, 'debito', v_cut.debit_id, 'ot_financiados', v_cut.consumed));
      end if;
    end loop;
    if v_unspent > 0 then
      insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
      values (v_o.usuario_id, 'devolucion', -v_unspent, 'Reembolso de OposiTickets', 'orden', v_credit.id, v_o.id, 'reembolso:' || v_o.id::text,
              jsonb_build_object('op', 'refund_reversal', 'proveedor', p_proveedor));
    end if;
    update public.compras_usuario c set estado = 'reembolsada' where c.id = v_o.id;
    if v_spent > 0 then
      insert into public.refund_case (compra_id, usuario_id, ot_revertidos, ot_gastados, detalle, estado, politica, resuelto_en)
      select v_o.id, v_o.usuario_id, v_unspent, v_spent,
             v_access || coalesce((select jsonb_agg(jsonb_build_object('debito', d.id, 'ot_desde_este_credito', k.cantidad, 'importe_debito', -d.cantidad,
                                                          'tipo', d.tipo, 'referencia_tipo', d.referencia_tipo, 'referencia_id', d.referencia_id,
                                                          'creado_en', d.creado_en) order by d.seq)
                         from public.ot_consumos k join public.transacciones_cartera d on d.id = k.debit_tx_id
                        where k.credit_tx_id = v_credit.id and d.tipo <> 'devolucion'), '[]'::jsonb), 'resuelto', 'P2', v_now;
    end if;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'pago_reembolsado', 'compras_usuario', v_o.id,
            jsonb_build_object('proveedor', p_proveedor, 'revertidos', v_unspent, 'gastados', v_spent));
    v_outcome := case when v_spent > 0 then 'REFUNDED_P2' else 'REFUNDED' end;
  end if;

  update public.eventos_pago e set resultado = v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
  return query select * from private.payment_order_result(v_outcome, v_o.id);
end;
$$;

-- 3. Safety net: orphans whose order exists now are re-linked (refund orphans only with refunds on). Rows are locked SKIP LOCKED, so two replicas
--    never re-link the same row; each call goes through the same per-key lock as the live path.
create function public.app_store_reconcile_orphans(p_limit integer, p_reembolsos_habilitados boolean)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_r record;
  v_out text;
  v_n integer := 0;
begin
  for v_r in
    select b.proveedor, b.evento_id, b.resultado, b.clave_compra
      from public.tienda_bandeja b
     where (b.resultado = 'REVERSAL_ORPHAN_HELD' or (coalesce(p_reembolsos_habilitados, false) and b.resultado = 'REFUND_ORPHAN_HELD'))
       and b.clave_compra is not null
       and exists (select 1 from public.compras_usuario c
                    where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra and c.cantidad_tickets is not null)
     order by b.recibido_en
     limit least(greatest(coalesce(p_limit, 50), 1), 200)
     for update of b skip locked
  loop
    if v_r.resultado = 'REVERSAL_ORPHAN_HELD' then
      v_out := public.app_store_refund_reversed(v_r.proveedor, v_r.evento_id, v_r.clave_compra);
    else
      select r.outcome into v_out from public.app_store_refund_purchase(v_r.proveedor, v_r.evento_id, v_r.clave_compra) r;
    end if;
    update public.tienda_bandeja b set resultado = v_out where b.proveedor = v_r.proveedor and b.evento_id = v_r.evento_id;
    v_n := v_n + 1;
  end loop;
  return v_n;
end;
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.app_store_inbox_put(text,text,text,text,text,text,jsonb)', 'public.app_store_inbox_pending(integer,boolean)',
    'public.app_store_inbox_mark(text,text,text,text,text,text,integer)', 'public.app_store_inbox_renew(text,text,text)',
    'public.app_store_inbox_requeue(text,text)', 'public.app_store_inbox_purge(integer)', 'public.app_store_reconcile_orphans(integer,boolean)']
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$$;

commit;
