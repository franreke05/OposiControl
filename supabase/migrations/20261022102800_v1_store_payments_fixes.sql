-- =============================================================================
-- V1 -- store payments fixes after the OposiControl audit of 8859ee4 (2026-10-19). Corrective; 20261022102600 is NOT edited.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022102600.
--
-- WHAT IT DOES (same lock order everywhere: wallet advisory lock, then the row)
--   * F2 / F12: a REFUND_REVERSED is never a silent NOOP. On a refunded order it still sends the order to review (D3). On an order that is NOT
--     refunded yet (the reversal arrived first, or the refund is still deferred, or the order is held) it is RECORDED on its event row
--     (eventos_pago.resultado = 'REVERSAL_EARLY', audit 'reembolso_revertido'); on an unknown key it is held on its inbox row
--     (REVERSAL_ORPHAN_HELD) and attached to the order when the purchase arrives. app_refund_payment_order: a PAID order with an early reversal
--     is sent to review (audit 'reembolso_tras_reversion', event REFUND_AFTER_REVERSAL) instead of being debited. Policy P2 otherwise unchanged.
--   * F4: inbox state 'retenido' (refunds switched off): it never counts as an attempt and is never dropped; it is listed again only when the
--     caller says refunds are on. Real failures ('pendiente') back off exponentially (1 min .. 6 h) and are never dropped (no attempt cap). The
--     pending list claims rows with FOR UPDATE SKIP LOCKED and a 5-minute lease (two replicas never process the same row) and orders by the next
--     attempt time, so new rows are not starved by old failing ones. app_store_inbox_stats() feeds the backlog metric / alert.
--   * F10: app_store_credit_purchase and app_store_upsert_subscription refuse a JWT user without the store account token (STORE_ACCOUNT_MISMATCH).
-- NOT CHANGED: admin_payment_order_resolve (Control), refund policy P2, SEC-V1-05 hold, ECO-04, re-purchase rule, account deletion cascade (D6).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the functions return to their 20261022102600 bodies.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_inbox_stats();
-- drop function if exists public.app_store_inbox_pending(integer, boolean);
-- drop function if exists private.payment_order_reversal_early(uuid);
-- drop index if exists public.ix_tienda_bandeja_proximo;
-- update public.tienda_bandeja set estado = 'pendiente' where estado = 'retenido';
-- alter table public.tienda_bandeja drop constraint if exists tienda_bandeja_estado_check;
-- alter table public.tienda_bandeja add constraint tienda_bandeja_estado_check check (estado in ('pendiente', 'procesado', 'descartado'));
-- alter table public.tienda_bandeja drop column if exists proximo_intento_en;
-- alter table public.tienda_bandeja drop column if exists reclamado_hasta;
-- create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text)
-- returns void
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- begin
--   if p_estado not in ('pendiente', 'procesado', 'descartado') then
--     raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
--   end if;
--   -- a processed row never goes back to pending (a late failure of a retry cannot undo a recorded result)
--   update public.tienda_bandeja b
--      set estado = case when b.estado = 'procesado' then b.estado else p_estado end,
--          resultado = coalesce(p_resultado, b.resultado),
--          ultimo_error = left(p_error, 300),
--          intentos = b.intentos + 1,
--          procesado_en = case when p_estado <> 'pendiente' then coalesce(b.procesado_en, now()) else b.procesado_en end
--    where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
-- end;
-- $$;
-- create or replace function public.app_store_inbox_pending(p_limit integer)
-- returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz)
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en
--     from public.tienda_bandeja b
--    where b.estado = 'pendiente' and b.intentos < 200
--    order by b.recibido_en
--    limit least(greatest(coalesce(p_limit, 50), 1), 200);
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
--   select c.usuario_id into v_user from public.compras_usuario c
--    where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
--   if not found then
--     return 'NOOP';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--   select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
--   insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id) on conflict (proveedor, evento_id) do nothing;
--   get diagnostics v_rows = row_count;
--   if v_rows = 0 then
--     return 'REPLAY';
--   end if;
--   if v_o.estado = 'reembolsada' then
--     update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--     v_outcome := 'EN_REVISION';
--   else
--     v_outcome := 'NOOP';
--   end if;
--   insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--   values (v_o.usuario_id, 'reembolso_revertido', 'compras_usuario', v_o.id,
--           jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'resultado', v_outcome));
--   update public.eventos_pago e set resultado = 'REFUND_REVERSED_' || v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
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
--   return query select * from public.app_credit_payment_order(p_proveedor, 'compra:' || encode(sha256(convert_to(p_clave, 'utf8')), 'hex'), v_o.id,
--                                                              p_clave, v_o.precio_centimos, v_o.moneda, v_o.producto_codigo);
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
-- revoke all on function public.app_store_inbox_pending(integer) from public, anon, authenticated;
-- grant execute on function public.app_store_inbox_pending(integer) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_inbox_pending(integer,boolean)'), ('public.app_store_inbox_stats()'), ('public.app_store_inbox_mark(text,text,text,text,text)'),
--                ('public.app_store_refund_reversed(text,text,text)'), ('public.app_refund_payment_order(text,text,uuid)'),
--                ('public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)'),
--                ('public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)')) f(n)
-- union all
-- select 'old_pending_signature_left', (case when to_regprocedure('public.app_store_inbox_pending(integer)') is null then 0 else 1 end)::bigint
-- union all
-- select 'private_helper_open', (case when has_function_privilege('anon', 'private.payment_order_reversal_early(uuid)', 'execute')
--                                       or has_function_privilege('authenticated', 'private.payment_order_reversal_early(uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'fix_marker_missing:' || f.n,
--        (case when (select p.prosrc from pg_proc p where p.oid = f.n::regprocedure) like '%' || f.marker || '%' then 0 else 1 end)::bigint
--   from (values ('public.app_refund_payment_order(text,text,uuid)', 'payment_order_reversal_early'),
--                ('public.app_store_refund_reversed(text,text,text)', 'REVERSAL_ORPHAN_HELD'),
--                ('public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)', 'p_cuenta_token is null'),
--                ('public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)', 'p_cuenta_token is null'),
--                ('public.app_store_inbox_pending(integer,boolean)', 'skip locked')) f(n, marker)
-- union all
-- select 'retenido_state_missing', (case when exists (select 1 from pg_constraint where conname = 'tienda_bandeja_estado_check' and pg_get_constraintdef(oid) like '%retenido%') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regclass('public.tienda_bandeja') is null or to_regprocedure('public.app_store_inbox_pending(integer)') is null
     or to_regprocedure('public.app_store_refund_reversed(text,text,text)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_FIXES_PREFLIGHT', hint = 'apply 20261022102600 first';
  end if;
end
$$;

-- 1. Inbox: retained state, backoff and lease
alter table public.tienda_bandeja add column if not exists proximo_intento_en timestamptz;
alter table public.tienda_bandeja add column if not exists reclamado_hasta timestamptz;
alter table public.tienda_bandeja drop constraint if exists tienda_bandeja_estado_check;
alter table public.tienda_bandeja add constraint tienda_bandeja_estado_check check (estado in ('pendiente', 'procesado', 'descartado', 'retenido'));
create index if not exists ix_tienda_bandeja_proximo on public.tienda_bandeja (coalesce(proximo_intento_en, recibido_en)) where estado in ('pendiente', 'retenido');

create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_estado not in ('pendiente', 'procesado', 'descartado', 'retenido') then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- a processed row never goes back; 'retenido' (refunds off) is not an attempt; 'pendiente' (a real failure) backs off 1 min .. 6 h, never dropped
  update public.tienda_bandeja b
     set estado = case when b.estado = 'procesado' then b.estado else p_estado end,
         resultado = coalesce(p_resultado, b.resultado),
         ultimo_error = left(p_error, 300),
         intentos = b.intentos + case when p_estado = 'retenido' then 0 else 1 end,
         proximo_intento_en = case when p_estado = 'pendiente' and b.estado <> 'procesado'
                                   then now() + least(interval '6 hours', interval '1 minute' * power(2, least(b.intentos, 12)))
                                   else null end,
         reclamado_hasta = null,
         procesado_en = case when p_estado in ('procesado', 'descartado') then coalesce(b.procesado_en, now()) else b.procesado_en end
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
end;
$$;

drop function if exists public.app_store_inbox_pending(integer);
create function public.app_store_inbox_pending(p_limit integer, p_incluir_retenidos boolean)
returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz)
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
  update public.tienda_bandeja b set reclamado_hasta = now() + interval '5 minutes'
    from candidatos k
   where b.proveedor = k.p and b.evento_id = k.e
  returning b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en;
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
   where b.estado in ('pendiente', 'retenido')
   group by b.estado;
$$;

-- 2. Early reversals (F2 / F12)
create or replace function private.payment_order_reversal_early(p_orden uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select exists (select 1 from public.eventos_pago e where e.compra_id = p_orden and e.resultado = 'REVERSAL_EARLY');
$$;
revoke all on function private.payment_order_reversal_early(uuid) from public, anon, authenticated;

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

-- 3. Refund: 20261022102600 body + the early-reversal branch (F2)
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

-- 4. Credit and subscription: 20261022102600 bodies + F10 (+ orphan reversals on credit)
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

  if v_outcome <> 'STALE' then
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

revoke all on function public.app_store_inbox_pending(integer, boolean) from public, anon, authenticated;
revoke all on function public.app_store_inbox_stats() from public, anon, authenticated;
grant execute on function public.app_store_inbox_pending(integer, boolean) to service_role;
grant execute on function public.app_store_inbox_stats() to service_role;

commit;
