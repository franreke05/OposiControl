-- =============================================================================
-- V1 -- SEC-V1-05: an out-of-order provider REFUND is never lost and blocks any later credit of the order. Corrective; 20261022100400 is NOT edited.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022100400 (orders) and 20261022102300 (admin_payment_order_resolve).
--
-- DEFECT (Control audit SEC-V1-05, 86f0da9): app_refund_payment_order inserted the REFUNDED event into eventos_pago and, when the order was not `pagada`,
-- answered NOOP (event consumed forever); app_credit_payment_order never looked for a prior refund, so REFUNDED(E1) on a pending order -> PAID(E2)
-- credited the OT of a payment the provider had already returned, and the retry of E1 was a REPLAY.
--
-- WHAT IT DOES (append-only ledger, one transaction per event, same lock order: wallet advisory lock, then the order row)
--   * A REFUNDED event for an order that is not paid (pendiente | expirada | fallida | cancelada | en_revision) is HELD: order -> en_revision (transitions
--     already allowed by the state machine), event row resultado = 'REFUND_HELD' (the refund-pending flag, durable, written with the hold), audit
--     'reembolso_retenido', answer EN_REVISION (an existing W-01 result: 200, the provider stops retrying because the refund IS recorded). Nothing is
--     reversed and the refund policy (P2) is unchanged; a refund of a refunded order is still REPLAY; a refund of a paid order is still P2.
--   * app_credit_payment_order: an order with a held refund never credits (audit 'pago_tras_reembolso', order to / stays in review, EN_REVISION).
--   * admin_payment_order_resolve (Control "pedidos en revision"): 'acreditar' and 'confirmar' on an order with a held refund -> ORDER_REFUND_PENDING;
--     'rechazar' (never credited) and 'reembolsar' (credited, P2, PAYMENTS_REFUNDS_ENABLED) are unchanged.
--   * private.payment_order_refund_held(uuid) + partial index ix_eventos_pago_refund_held. No new table, no client grant, no OpenAPI change.
-- NOT CHANGED: exam re-purchase rule, refund policy, PAYMENTS_REFUNDS_ENABLED semantics (with the switch off Ktor still answers 503 and writes nothing).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the three functions return to their 20261022100400 / 20261022102300 bodies.
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.admin_payment_order_resolve(p_actor uuid, p_orden uuid, p_decision text, p_nota text, p_reembolsos_habilitados boolean default false)
-- returns jsonb
-- language plpgsql
-- volatile
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_o public.compras_usuario%rowtype;
--   v_user uuid;
--   v_nota text;
--   v_credited boolean;
--   v_outcome text;
--   v_estado_prev text;
--   v_rf record;
-- begin
--   perform private.require_admin_actor(p_actor);
--   if p_orden is null or p_decision is null or p_decision not in ('acreditar', 'confirmar', 'rechazar', 'reembolsar') then
--     raise exception using errcode = 'P0001', message = 'ORDER_DECISION_INVALID';
--   end if;
--   v_nota := private.admin_reason(p_nota, 3, 300);
--   select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.cantidad_tickets is not null;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
--   end if;
--   -- ONE lock order everywhere: wallet advisory lock first, then the order row
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--   select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;
--   v_credited := exists (select 1 from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets');
--   v_estado_prev := v_o.estado;
--
--   if v_o.estado <> 'en_revision' then
--     if (p_decision in ('acreditar', 'confirmar') and v_o.estado = 'pagada' and v_credited)
--        or (p_decision = 'rechazar' and v_o.estado = 'fallida' and not v_credited)
--        or (p_decision = 'reembolsar' and v_o.estado = 'reembolsada') then
--       return jsonb_build_object('outcome', 'REPLAY', 'estado', v_o.estado, 'ordenId', v_o.id);
--     end if;
--     raise exception using errcode = 'P0001', message = 'ORDER_NOT_IN_REVIEW';
--   end if;
--
--   perform set_config('app.order_resolve', v_o.id::text, true);
--   if p_decision = 'acreditar' then
--     if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
--     insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
--     values (v_o.usuario_id, 'pack_tickets', v_o.cantidad_tickets, 'Compra de OposiTickets (revision)', 'orden', v_o.id, v_o.id, 'orden:' || v_o.id::text,
--             jsonb_build_object('op', 'credit_order', 'proveedor', v_o.proveedor, 'resolucion_admin', true));
--     update public.compras_usuario c set estado = 'pagada', pagada_en = now() where c.id = v_o.id;
--     v_outcome := 'CREDITED';
--   elsif p_decision = 'confirmar' then
--     if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
--     update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
--     v_outcome := 'CONFIRMED';
--   elsif p_decision = 'rechazar' then
--     if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
--     update public.compras_usuario c set estado = 'fallida' where c.id = v_o.id;
--     v_outcome := 'REJECTED';
--   else
--     if not coalesce(p_reembolsos_habilitados, false) then raise exception using errcode = 'P0001', message = 'REFUNDS_DISABLED'; end if;
--     if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
--     update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
--     select * into v_rf from public.app_refund_payment_order(v_o.proveedor, 'admin-refund:' || v_o.id::text, v_o.id);
--     v_outcome := v_rf.outcome;
--   end if;
--   perform set_config('app.order_resolve', '', true);
--   insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
--   values (p_actor, 'pedido_resuelto', 'compras_usuario', v_o.id, jsonb_build_object('estado', v_estado_prev, 'acreditada', v_credited),
--           jsonb_build_object('decision', p_decision, 'resultado', v_outcome, 'nota', v_nota, 'cantidad_tickets', v_o.cantidad_tickets));
--   return jsonb_build_object('outcome', v_outcome, 'estado', (select c.estado from public.compras_usuario c where c.id = v_o.id), 'ordenId', v_o.id,
--                             'casoReembolso', exists (select 1 from public.refund_case rc where rc.compra_id = v_o.id));
-- end;
-- $$;
-- create or replace function public.app_credit_payment_order(
--   p_proveedor text, p_evento_id text, p_orden uuid, p_transaccion text, p_importe integer, p_moneda text, p_producto text)
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
--   v_rows integer;
--   v_new boolean;
--   v_late boolean;
--   v_match boolean;
--   v_outcome text;
-- begin
--   if p_proveedor is null or p_evento_id is null or length(btrim(p_evento_id)) = 0 or p_transaccion is null or length(btrim(p_transaccion)) = 0 or p_orden is null then
--     raise exception using errcode = 'P0001', message = 'EVENT_INVALID';
--   end if;
--   -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row), same as redeem/create/get: find the owner without locking,
--   -- take the wallet lock, then lock and re-read the order.
--   select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.proveedor = p_proveedor and c.cantidad_tickets is not null;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
--   select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;
--
--   insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id)
--   on conflict (proveedor, evento_id) do nothing;
--   get diagnostics v_rows = row_count;  -- 1 -> first time this event is seen
--   v_new := v_rows = 1;
--   if not v_new then
--     return query select * from private.payment_order_result('REPLAY', v_o.id);
--     return;
--   end if;
--
--   if v_o.estado = 'pendiente' and v_o.expira_en <= now() then
--     update public.compras_usuario c set estado = 'expirada' where c.id = v_o.id;
--     v_o.estado := 'expirada';
--   end if;
--   v_late := v_o.estado = 'expirada';
--   v_match := p_importe is not null and p_importe = v_o.precio_centimos and upper(coalesce(p_moneda, '')) = upper(v_o.moneda) and p_producto = v_o.producto_codigo;
--
--   if v_o.estado = 'pagada' then
--     v_outcome := 'REPLAY';                               -- another event for an order that is already credited: no second credit
--   elsif v_o.estado in ('pendiente', 'expirada') and v_match
--         and not exists (select 1 from public.compras_usuario c where c.proveedor = v_o.proveedor and c.identificador_transaccion = p_transaccion and c.id <> v_o.id) then
--     insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
--     values (v_o.usuario_id, 'pack_tickets', v_o.cantidad_tickets, 'Compra de OposiTickets', 'orden', v_o.id, v_o.id, 'orden:' || v_o.id::text,
--             jsonb_build_object('op', 'credit_order', 'proveedor', p_proveedor, 'pago_tardio', v_late));
--     update public.compras_usuario c set estado = 'pagada', identificador_transaccion = p_transaccion, pagada_en = now() where c.id = v_o.id;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, case when v_late then 'pago_tardio_acreditado' else 'pago_acreditado' end, 'compras_usuario', v_o.id,
--             jsonb_build_object('proveedor', p_proveedor, 'cantidad_tickets', v_o.cantidad_tickets));
--     v_outcome := case when v_late then 'CREDITED_LATE' else 'CREDITED' end;
--   elsif v_o.estado <> 'en_revision' then
--     -- mismatch (amount, currency, product, duplicate provider transaction) or a paid event for a cancelled/failed/refunded order: NO credit
--     update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'pago_en_revision', 'compras_usuario', v_o.id,
--             jsonb_build_object('proveedor', p_proveedor, 'estado_previo', v_o.estado, 'datos_coinciden', v_match));
--     v_outcome := 'EN_REVISION';
--   else
--     v_outcome := 'EN_REVISION';
--   end if;
--
--   update public.eventos_pago e set resultado = v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
--   return query select * from private.payment_order_result(v_outcome, v_o.id);
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
--   -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row), same as redeem/create/get: find the owner without locking,
--   -- take the wallet lock, then lock and re-read the order.
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
--     return query select * from private.payment_order_result('REPLAY', v_o.id);
--     return;
--   end if;
--
--   if v_o.estado <> 'pagada' then
--     -- nothing was credited for pendiente / fallida / cancelada / expirada; a second refund of reembolsada is a no-op
--     v_outcome := case when v_o.estado = 'reembolsada' then 'REPLAY' else 'NOOP' end;
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
-- drop function if exists private.payment_order_refund_held(uuid);
-- drop index if exists public.ix_eventos_pago_refund_held;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)'), ('public.app_refund_payment_order(text,text,uuid)'),
--                ('public.admin_payment_order_resolve(uuid,uuid,text,text,boolean)')) f(n)
-- union all
-- select 'private_helper_open', (case when has_function_privilege('anon', 'private.payment_order_refund_held(uuid)', 'execute')
--                                       or has_function_privilege('authenticated', 'private.payment_order_refund_held(uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'refund_hold_missing:' || f.n,
--        (case when (select p.prosrc from pg_proc p where p.oid = f.n::regprocedure) like '%' || f.marker || '%' then 0 else 1 end)::bigint
--   from (values ('public.app_refund_payment_order(text,text,uuid)', 'REFUND_HELD'),
--                ('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)', 'payment_order_refund_held'),
--                ('public.admin_payment_order_resolve(uuid,uuid,text,text,boolean)', 'ORDER_REFUND_PENDING')) f(n, marker)
-- union all
-- select 'refund_held_index_missing', (case when exists (select 1 from pg_indexes where indexname = 'ix_eventos_pago_refund_held') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_refund_payment_order(text,text,uuid)') is null
     or to_regprocedure('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)') is null
     or to_regprocedure('public.admin_payment_order_resolve(uuid,uuid,text,text,boolean)') is null
     or to_regclass('public.eventos_pago') is null then
    raise exception using errcode = 'P0001', message = 'V1_REFUND_ORDER_FIX_PREFLIGHT', hint = 'apply 20261022100400 and 20261022102300 first';
  end if;
end
$$;

-- 1. The refund-pending flag: a REFUNDED event that found no credited, paid order keeps its row in eventos_pago with resultado = 'REFUND_HELD'.
--    The event table is already the dedup authority (written in the SAME transaction as every effect) and is never deleted, so the flag is durable,
--    atomic with the hold and readable by Control (service_role select). No new table, no client grant.
create index if not exists ix_eventos_pago_refund_held on public.eventos_pago (compra_id) where resultado = 'REFUND_HELD';

create or replace function private.payment_order_refund_held(p_orden uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select exists (select 1 from public.eventos_pago e where e.compra_id = p_orden and e.resultado = 'REFUND_HELD');
$$;
revoke all on function private.payment_order_refund_held(uuid) from public, anon, authenticated;

-- 2. Credit: identical to 20261022100400 except the SEC-V1-05 branch (an order with a held refund never credits; it goes to / stays in review).
create or replace function public.app_credit_payment_order(
  p_proveedor text, p_evento_id text, p_orden uuid, p_transaccion text, p_importe integer, p_moneda text, p_producto text)
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
  v_rows integer;
  v_new boolean;
  v_late boolean;
  v_match boolean;
  v_outcome text;
begin
  if p_proveedor is null or p_evento_id is null or length(btrim(p_evento_id)) = 0 or p_transaccion is null or length(btrim(p_transaccion)) = 0 or p_orden is null then
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
  get diagnostics v_rows = row_count;  -- 1 -> first time this event is seen
  v_new := v_rows = 1;
  if not v_new then
    return query select * from private.payment_order_result('REPLAY', v_o.id);
    return;
  end if;

  if v_o.estado = 'pendiente' and v_o.expira_en <= now() then
    update public.compras_usuario c set estado = 'expirada' where c.id = v_o.id;
    v_o.estado := 'expirada';
  end if;
  v_late := v_o.estado = 'expirada';
  v_match := p_importe is not null and p_importe = v_o.precio_centimos and upper(coalesce(p_moneda, '')) = upper(v_o.moneda) and p_producto = v_o.producto_codigo;

  if v_o.estado = 'pagada' then
    v_outcome := 'REPLAY';                               -- another event for an order that is already credited: no second credit
  elsif private.payment_order_refund_held(v_o.id) then
    -- SEC-V1-05: the provider already refunded this order (the refund arrived first and was held). NO credit, ever, from a webhook: review.
    if v_o.estado <> 'en_revision' then
      update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
    end if;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'pago_tras_reembolso', 'compras_usuario', v_o.id,
            jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'datos_coinciden', v_match));
    v_outcome := 'EN_REVISION';
  elsif v_o.estado in ('pendiente', 'expirada') and v_match
        and not exists (select 1 from public.compras_usuario c where c.proveedor = v_o.proveedor and c.identificador_transaccion = p_transaccion and c.id <> v_o.id) then
    insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
    values (v_o.usuario_id, 'pack_tickets', v_o.cantidad_tickets, 'Compra de OposiTickets', 'orden', v_o.id, v_o.id, 'orden:' || v_o.id::text,
            jsonb_build_object('op', 'credit_order', 'proveedor', p_proveedor, 'pago_tardio', v_late));
    update public.compras_usuario c set estado = 'pagada', identificador_transaccion = p_transaccion, pagada_en = now() where c.id = v_o.id;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, case when v_late then 'pago_tardio_acreditado' else 'pago_acreditado' end, 'compras_usuario', v_o.id,
            jsonb_build_object('proveedor', p_proveedor, 'cantidad_tickets', v_o.cantidad_tickets));
    v_outcome := case when v_late then 'CREDITED_LATE' else 'CREDITED' end;
  elsif v_o.estado <> 'en_revision' then
    -- mismatch (amount, currency, product, duplicate provider transaction) or a paid event for a cancelled/failed/refunded order: NO credit
    update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
    values (v_o.usuario_id, 'pago_en_revision', 'compras_usuario', v_o.id,
            jsonb_build_object('proveedor', p_proveedor, 'estado_previo', v_o.estado, 'datos_coinciden', v_match));
    v_outcome := 'EN_REVISION';
  else
    v_outcome := 'EN_REVISION';
  end if;

  update public.eventos_pago e set resultado = v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
  return query select * from private.payment_order_result(v_outcome, v_o.id);
end;
$$;

-- 3. Refund: identical to 20261022100400 (policy P2, unchanged) except that a refund for an order that is NOT paid is no longer consumed as NOOP:
--    it is HELD. The order goes to review (pendiente | expirada | fallida | cancelada -> en_revision, already allowed by the state machine; en_revision
--    stays), the event row is marked REFUND_HELD (the flag every credit path checks), the hold is audited, and the answer is EN_REVISION (an existing
--    W-01 result: the provider gets 200 and stops retrying because the refund IS recorded). Nothing is reversed: the order was never credited, or it
--    was credited and is in review, where only Control decides (admin_payment_order_resolve 'reembolsar' applies P2; 'rechazar' closes it).
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

-- 4. Control "pedidos en revision": identical to 20261022102300 except that an order with a held provider refund can be neither credited ('acreditar')
--    nor kept credited ('confirmar'): ORDER_REFUND_PENDING. 'rechazar' (never credited) and 'reembolsar' (credited, policy P2, switch on) stay available.
create or replace function public.admin_payment_order_resolve(p_actor uuid, p_orden uuid, p_decision text, p_nota text, p_reembolsos_habilitados boolean default false)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_o public.compras_usuario%rowtype;
  v_user uuid;
  v_nota text;
  v_credited boolean;
  v_outcome text;
  v_estado_prev text;
  v_rf record;
begin
  perform private.require_admin_actor(p_actor);
  if p_orden is null or p_decision is null or p_decision not in ('acreditar', 'confirmar', 'rechazar', 'reembolsar') then
    raise exception using errcode = 'P0001', message = 'ORDER_DECISION_INVALID';
  end if;
  v_nota := private.admin_reason(p_nota, 3, 300);
  select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.cantidad_tickets is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
  end if;
  -- ONE lock order everywhere: wallet advisory lock first, then the order row
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
  select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;
  v_credited := exists (select 1 from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets');
  v_estado_prev := v_o.estado;

  if v_o.estado <> 'en_revision' then
    if (p_decision in ('acreditar', 'confirmar') and v_o.estado = 'pagada' and v_credited)
       or (p_decision = 'rechazar' and v_o.estado = 'fallida' and not v_credited)
       or (p_decision = 'reembolsar' and v_o.estado = 'reembolsada') then
      return jsonb_build_object('outcome', 'REPLAY', 'estado', v_o.estado, 'ordenId', v_o.id);
    end if;
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_IN_REVIEW';
  end if;
  -- SEC-V1-05: the provider refunded this order (held refund event): it must not end credited through Control either
  if p_decision in ('acreditar', 'confirmar') and private.payment_order_refund_held(v_o.id) then
    raise exception using errcode = 'P0001', message = 'ORDER_REFUND_PENDING';
  end if;

  perform set_config('app.order_resolve', v_o.id::text, true);
  if p_decision = 'acreditar' then
    if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
    insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
    values (v_o.usuario_id, 'pack_tickets', v_o.cantidad_tickets, 'Compra de OposiTickets (revision)', 'orden', v_o.id, v_o.id, 'orden:' || v_o.id::text,
            jsonb_build_object('op', 'credit_order', 'proveedor', v_o.proveedor, 'resolucion_admin', true));
    update public.compras_usuario c set estado = 'pagada', pagada_en = now() where c.id = v_o.id;
    v_outcome := 'CREDITED';
  elsif p_decision = 'confirmar' then
    if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
    update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
    v_outcome := 'CONFIRMED';
  elsif p_decision = 'rechazar' then
    if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
    update public.compras_usuario c set estado = 'fallida' where c.id = v_o.id;
    v_outcome := 'REJECTED';
  else
    if not coalesce(p_reembolsos_habilitados, false) then raise exception using errcode = 'P0001', message = 'REFUNDS_DISABLED'; end if;
    if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
    update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
    select * into v_rf from public.app_refund_payment_order(v_o.proveedor, 'admin-refund:' || v_o.id::text, v_o.id);
    v_outcome := v_rf.outcome;
  end if;
  perform set_config('app.order_resolve', '', true);
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'pedido_resuelto', 'compras_usuario', v_o.id, jsonb_build_object('estado', v_estado_prev, 'acreditada', v_credited),
          jsonb_build_object('decision', p_decision, 'resultado', v_outcome, 'nota', v_nota, 'cantidad_tickets', v_o.cantidad_tickets));
  return jsonb_build_object('outcome', v_outcome, 'estado', (select c.estado from public.compras_usuario c where c.id = v_o.id), 'ordenId', v_o.id,
                            'casoReembolso', exists (select 1 from public.refund_case rc where rc.compra_id = v_o.id));
end;
$$;

revoke all on function public.app_credit_payment_order(text, text, uuid, text, integer, text, text) from public, anon, authenticated;
revoke all on function public.app_refund_payment_order(text, text, uuid) from public, anon, authenticated;
revoke all on function public.admin_payment_order_resolve(uuid, uuid, text, text, boolean) from public, anon, authenticated;
grant execute on function public.app_credit_payment_order(text, text, uuid, text, integer, text, text) to service_role;
grant execute on function public.app_refund_payment_order(text, text, uuid) to service_role;
grant execute on function public.admin_payment_order_resolve(uuid, uuid, text, text, boolean) to service_role;

commit;
