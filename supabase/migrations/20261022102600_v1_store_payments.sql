-- =============================================================================
-- V1 -- store payments (Google Play + App Store): account token, store inbox, credit/refund/subscription RPCs, ECO-04, ECO-11.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022100400, 20261022100900 and 20261022102400.
-- Contract: docs/payments/STORE_PAYMENTS_CONTRACT.md. Control audit checklist: PAY-R1..R6.
--
-- WHAT IT DOES (append-only ledger; ONE lock order everywhere: wallet advisory lock first, then the row)
--   * tienda_cuentas: one opaque random UUID per user (Play obfuscatedAccountId / Apple appAccountToken). Server only.
--   * tienda_bandeja: idempotent inbox of every verified store notification, written BEFORE the webhook answers 2xx (ECO-02). Key (proveedor,
--     evento_id) = Pub/Sub messageId / Apple notificationUUID / 'void:<...>'. `huella` = sha256 of the raw body. Server only.
--   * compras_usuario: + entorno (produccion | sandbox), tienda_producto_id, tienda_finalizada_en (Play consume/acknowledge done).
--     suscripciones: + entorno, tienda_producto_id, tienda_finalizada_en, version_evento (newest store event applied; older ones are STALE) and
--     UNIQUE (proveedor, identificador_externo): one store subscription is bound to one user.
--   * app_store_credit_purchase: a verified store purchase becomes an order keyed by the store key (UNIQUE (proveedor, identificador_transaccion) of
--     20261022100400), credited ONCE through app_credit_payment_order with the FROZEN catalogue amount (credit by SKU, PAY-R1 proposal, owner D1).
--     A refund that arrived before the purchase (inbox REFUND_ORPHAN_HELD) is applied first, so it is held (SEC-V1-05) and nothing is credited.
--   * app_store_refund_purchase / app_store_refund_reversed / app_store_upsert_subscription / app_store_mark_finalized / app_store_pending_finalize /
--     app_store_inbox_put / app_store_inbox_mark / app_store_inbox_pending / app_store_account_token / app_store_entitlements: service_role only.
--   * ECO-04: app_credit_payment_order and app_refund_payment_order: an event id already bound to ANOTHER order sends this order to review
--     (audit evento_de_otro_pedido) instead of a silent REPLAY. Otherwise identical to 20261022102400.
--   * ECO-11: service_role loses INSERT / UPDATE / DELETE / TRUNCATE on compras_usuario and suscripciones (only SECURITY DEFINER RPCs write them).
-- NOT CHANGED: refund policy P2, SEC-V1-05 hold, exam re-purchase rule, admin_payment_order_resolve, the provider CHECK (google_play and app_store
-- already allowed), account deletion cascade (ECO-12 / owner-legal D6).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_store_entitlements(uuid);
-- drop function if exists public.app_store_pending_finalize(integer);
-- drop function if exists public.app_store_mark_finalized(text, text, text);
-- drop function if exists public.app_store_upsert_subscription(uuid, uuid, text, text, text, text, text, text, timestamptz, timestamptz, timestamptz, text, bigint, text);
-- drop function if exists public.app_store_refund_reversed(text, text, text);
-- drop function if exists public.app_store_refund_purchase(text, text, text);
-- drop function if exists public.app_store_credit_purchase(uuid, uuid, text, text, text, text, text, jsonb);
-- drop function if exists public.app_store_inbox_pending(integer);
-- drop function if exists public.app_store_inbox_mark(text, text, text, text, text);
-- drop function if exists public.app_store_inbox_put(text, text, text, text, text, text, jsonb);
-- drop function if exists public.app_store_account_token(uuid);
-- drop function if exists private.store_resolve_user(uuid, uuid);
-- drop table if exists public.tienda_bandeja;
-- drop table if exists public.tienda_cuentas;
-- drop index if exists public.ux_suscripciones_proveedor_externo;
-- alter table public.suscripciones drop constraint if exists suscripciones_entorno_check;
-- alter table public.suscripciones drop column if exists entorno;
-- alter table public.suscripciones drop column if exists tienda_producto_id;
-- alter table public.suscripciones drop column if exists tienda_finalizada_en;
-- alter table public.suscripciones drop column if exists version_evento;
-- alter table public.compras_usuario drop constraint if exists compras_usuario_entorno_check;
-- alter table public.compras_usuario drop column if exists entorno;
-- alter table public.compras_usuario drop column if exists tienda_producto_id;
-- alter table public.compras_usuario drop column if exists tienda_finalizada_en;
-- grant insert, update, delete, truncate on table public.compras_usuario to service_role;
-- grant insert, update, delete, truncate on table public.suscripciones to service_role;
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
--   elsif private.payment_order_refund_held(v_o.id) then
--     -- SEC-V1-05: the provider already refunded this order (the refund arrived first and was held). NO credit, ever, from a webhook: review.
--     if v_o.estado <> 'en_revision' then
--       update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
--     end if;
--     insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
--     values (v_o.usuario_id, 'pago_tras_reembolso', 'compras_usuario', v_o.id,
--             jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'datos_coinciden', v_match));
--     v_outcome := 'EN_REVISION';
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
--
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
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_store_account_token(uuid)'), ('public.app_store_inbox_put(text,text,text,text,text,text,jsonb)'),
--                ('public.app_store_inbox_mark(text,text,text,text,text)'), ('public.app_store_inbox_pending(integer)'),
--                ('public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)'), ('public.app_store_refund_purchase(text,text,text)'),
--                ('public.app_store_refund_reversed(text,text,text)'),
--                ('public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)'),
--                ('public.app_store_mark_finalized(text,text,text)'), ('public.app_store_pending_finalize(integer)'), ('public.app_store_entitlements(uuid)'),
--                ('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)'), ('public.app_refund_payment_order(text,text,uuid)')) f(n)
-- union all
-- select 'private_helper_open', (case when has_function_privilege('anon', 'private.store_resolve_user(uuid,uuid)', 'execute')
--                                       or has_function_privilege('authenticated', 'private.store_resolve_user(uuid,uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'table_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select')
--                or has_table_privilege('anon', t.n, 'insert') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('service_role', t.n, 'insert') or has_table_privilege('service_role', t.n, 'update') or has_table_privilege('service_role', t.n, 'delete')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.tienda_cuentas'), ('public.tienda_bandeja')) t(n)
-- union all
-- select 'eco11_service_role_writes:' || t.n,
--        (case when has_table_privilege('service_role', t.n, 'insert') or has_table_privilege('service_role', t.n, 'update')
--                or has_table_privilege('service_role', t.n, 'delete') or has_table_privilege('service_role', t.n, 'truncate')
--                or not has_table_privilege('service_role', t.n, 'select') then 1 else 0 end)::bigint
--   from (values ('public.compras_usuario'), ('public.suscripciones')) t(n)
-- union all
-- select 'eco04_missing:' || f.n,
--        (case when (select p.prosrc from pg_proc p where p.oid = f.n::regprocedure) like '%evento_de_otro_pedido%' then 0 else 1 end)::bigint
--   from (values ('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)'), ('public.app_refund_payment_order(text,text,uuid)')) f(n)
-- union all
-- select 'store_unique_guards_missing',
--        (case when exists (select 1 from pg_indexes where indexname = 'ux_suscripciones_proveedor_externo')
--               and exists (select 1 from pg_indexes where indexname = 'ux_compras_usuario_proveedor_transaccion') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)') is null
     or to_regprocedure('public.app_refund_payment_order(text,text,uuid)') is null
     or to_regprocedure('private.payment_order_refund_held(uuid)') is null
     or to_regclass('public.suscripciones') is null or to_regclass('public.estado_premium_usuario') is null
     or to_regclass('public.eventos_pago') is null or to_regclass('public.carteras_usuario') is null then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_PREFLIGHT', hint = 'apply 20261022100400, 20261022100900 and 20261022102400 first';
  end if;
  if exists (select 1 from public.suscripciones s where s.identificador_externo is not null group by s.proveedor, s.identificador_externo having count(*) > 1) then
    raise exception using errcode = 'P0001', message = 'V1_STORE_PAYMENTS_PREFLIGHT',
      hint = 'two subscriptions share (proveedor, identificador_externo); resolve with the owner first (nothing was changed)';
  end if;
end
$$;

-- 1. Columns and guards on the existing order / subscription tables
alter table public.compras_usuario add column if not exists entorno text;
alter table public.compras_usuario add column if not exists tienda_producto_id text;
alter table public.compras_usuario add column if not exists tienda_finalizada_en timestamptz;
alter table public.compras_usuario drop constraint if exists compras_usuario_entorno_check;
alter table public.compras_usuario add constraint compras_usuario_entorno_check check (entorno is null or entorno in ('produccion', 'sandbox'));
alter table public.suscripciones add column if not exists entorno text;
alter table public.suscripciones add column if not exists tienda_producto_id text;
alter table public.suscripciones add column if not exists tienda_finalizada_en timestamptz;
alter table public.suscripciones add column if not exists version_evento bigint;
alter table public.suscripciones drop constraint if exists suscripciones_entorno_check;
alter table public.suscripciones add constraint suscripciones_entorno_check check (entorno is null or entorno in ('produccion', 'sandbox'));
create unique index if not exists ux_suscripciones_proveedor_externo
  on public.suscripciones (proveedor, identificador_externo) where identificador_externo is not null;

-- 2. ECO-11: only SECURITY DEFINER RPCs write orders and subscriptions (reads stay for Control analytics and the Bot reads).
revoke insert, update, delete, truncate on table public.compras_usuario from service_role;
revoke insert, update, delete, truncate on table public.suscripciones from service_role;

-- 3. Server-only tables (RLS on, no client grant, service_role reads)
create table if not exists public.tienda_cuentas (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  token uuid not null default gen_random_uuid(),
  creado_en timestamptz not null default now(),
  constraint tienda_cuentas_token_key unique (token)
);
alter table public.tienda_cuentas enable row level security;
revoke all on table public.tienda_cuentas from public, anon, authenticated, service_role;
grant select on table public.tienda_cuentas to service_role;

create table if not exists public.tienda_bandeja (
  proveedor text not null check (proveedor in ('google_play', 'app_store')),
  evento_id text not null check (length(evento_id) between 1 and 200),
  tipo text not null check (tipo in ('COMPRA', 'REEMBOLSO', 'REEMBOLSO_REVERTIDO', 'SUSCRIPCION', 'CONSUMO', 'PRUEBA', 'IGNORADO')),
  clave_compra text,
  entorno text check (entorno is null or entorno in ('produccion', 'sandbox')),
  huella text not null check (huella ~ '^[0-9a-f]{64}$'),
  datos jsonb not null default '{}'::jsonb,
  estado text not null default 'pendiente' check (estado in ('pendiente', 'procesado', 'descartado')),
  intentos integer not null default 0 check (intentos >= 0),
  resultado text,
  ultimo_error text,
  recibido_en timestamptz not null default now(),
  procesado_en timestamptz,
  constraint tienda_bandeja_pkey primary key (proveedor, evento_id)
);
create index if not exists ix_tienda_bandeja_pendiente on public.tienda_bandeja (recibido_en) where estado = 'pendiente';
create index if not exists ix_tienda_bandeja_reembolso_huerfano on public.tienda_bandeja (proveedor, clave_compra) where resultado = 'REFUND_ORPHAN_HELD';
alter table public.tienda_bandeja enable row level security;
revoke all on table public.tienda_bandeja from public, anon, authenticated, service_role;
grant select on table public.tienda_bandeja to service_role;

-- 4. Who a store fact belongs to. The account token echoed by the store wins; with a JWT user as well, both must agree.
create or replace function private.store_resolve_user(p_usuario uuid, p_cuenta_token uuid)
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  v uuid;
begin
  if p_cuenta_token is null then
    return p_usuario;
  end if;
  select t.usuario_id into v from public.tienda_cuentas t where t.token = p_cuenta_token;
  if v is null or (p_usuario is not null and v <> p_usuario) then
    raise exception using errcode = 'P0001', message = 'STORE_ACCOUNT_MISMATCH';
  end if;
  return v;
end;
$$;
revoke all on function private.store_resolve_user(uuid, uuid) from public, anon, authenticated;

create or replace function public.app_store_account_token(p_usuario uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  insert into public.tienda_cuentas (usuario_id) values (p_usuario) on conflict (usuario_id) do nothing;
  return (select t.token from public.tienda_cuentas t where t.usuario_id = p_usuario);
end;
$$;

-- 5. Inbox
create or replace function public.app_store_inbox_put(p_proveedor text, p_evento_id text, p_tipo text, p_clave text, p_entorno text, p_huella text, p_datos jsonb)
returns table(nuevo boolean, estado text, resultado text, misma_huella boolean)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_rows integer;
  v_b public.tienda_bandeja%rowtype;
begin
  if p_proveedor is null or p_evento_id is null or p_tipo is null or p_huella is null then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  insert into public.tienda_bandeja (proveedor, evento_id, tipo, clave_compra, entorno, huella, datos)
  values (p_proveedor, p_evento_id, p_tipo, p_clave, p_entorno, p_huella, coalesce(p_datos, '{}'::jsonb))
  on conflict (proveedor, evento_id) do nothing;
  get diagnostics v_rows = row_count;
  select b.* into v_b from public.tienda_bandeja b where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
  return query select v_rows = 1, v_b.estado, v_b.resultado, v_b.huella = p_huella;
end;
$$;

create or replace function public.app_store_inbox_mark(p_proveedor text, p_evento_id text, p_estado text, p_resultado text, p_error text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_estado not in ('pendiente', 'procesado', 'descartado') then
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  -- a processed row never goes back to pending (a late failure of a retry cannot undo a recorded result)
  update public.tienda_bandeja b
     set estado = case when b.estado = 'procesado' then b.estado else p_estado end,
         resultado = coalesce(p_resultado, b.resultado),
         ultimo_error = left(p_error, 300),
         intentos = b.intentos + 1,
         procesado_en = case when p_estado <> 'pendiente' then coalesce(b.procesado_en, now()) else b.procesado_en end
   where b.proveedor = p_proveedor and b.evento_id = p_evento_id;
end;
$$;

create or replace function public.app_store_inbox_pending(p_limit integer)
returns table(proveedor text, evento_id text, tipo text, clave_compra text, entorno text, datos jsonb, intentos integer, recibido_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select b.proveedor, b.evento_id, b.tipo, b.clave_compra, b.entorno, b.datos, b.intentos, b.recibido_en
    from public.tienda_bandeja b
   where b.estado = 'pendiente' and b.intentos < 200
   order by b.recibido_en
   limit least(greatest(coalesce(p_limit, 50), 1), 200);
$$;

-- 6. Credit a VERIFIED store purchase of a pack (one order per store key, credited once)
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

  return query select * from public.app_credit_payment_order(p_proveedor, 'compra:' || encode(sha256(convert_to(p_clave, 'utf8')), 'hex'), v_o.id,
                                                             p_clave, v_o.precio_centimos, v_o.moneda, v_o.producto_codigo);
end;
$$;

-- 7. Refund / void of a store purchase. No order yet: recorded as an orphan on its inbox row and applied when the purchase arrives.
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

-- 8. Apple REFUND_REVERSED on a pack (owner D3): never an automatic re-credit; the refunded order goes back to review for Control.
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
  select c.usuario_id into v_user from public.compras_usuario c
   where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave and c.cantidad_tickets is not null;
  if not found then
    return 'NOOP';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
  select c.* into v_o from public.compras_usuario c where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave for update;
  insert into public.eventos_pago (proveedor, evento_id, compra_id) values (p_proveedor, p_evento_id, v_o.id) on conflict (proveedor, evento_id) do nothing;
  get diagnostics v_rows = row_count;
  if v_rows = 0 then
    return 'REPLAY';
  end if;
  if v_o.estado = 'reembolsada' then
    update public.compras_usuario c set estado = 'en_revision' where c.id = v_o.id;
    v_outcome := 'EN_REVISION';
  else
    v_outcome := 'NOOP';
  end if;
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (v_o.usuario_id, 'reembolso_revertido', 'compras_usuario', v_o.id,
          jsonb_build_object('proveedor', p_proveedor, 'evento_id', p_evento_id, 'estado_previo', v_o.estado, 'resultado', v_outcome));
  update public.eventos_pago e set resultado = 'REFUND_REVERSED_' || v_outcome where e.proveedor = p_proveedor and e.evento_id = p_evento_id;
  return v_outcome;
end;
$$;

-- 9. Subscription state from a VERIFIED store read. The newest store event wins (version_evento); the projection trigger updates premium state.
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

-- 10. Play finish (consume / acknowledge) bookkeeping and the reconciliation list
create or replace function public.app_store_mark_finalized(p_proveedor text, p_tipo text, p_clave text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_tipo = 'compra' then
    update public.compras_usuario c set tienda_finalizada_en = coalesce(c.tienda_finalizada_en, now())
     where c.proveedor = p_proveedor and c.identificador_transaccion = p_clave;
  elsif p_tipo = 'suscripcion' then
    update public.suscripciones s set tienda_finalizada_en = coalesce(s.tienda_finalizada_en, now())
     where s.proveedor = p_proveedor and s.identificador_externo = p_clave;
  else
    raise exception using errcode = 'P0001', message = 'STORE_REQUEST_INVALID';
  end if;
  return found;
end;
$$;

create or replace function public.app_store_pending_finalize(p_limit integer)
returns table(tipo text, proveedor text, clave text, tienda_producto_id text, creado_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select x.tipo, x.proveedor, x.clave, x.tienda_producto_id, x.creado_en from (
    select 'compra'::text as tipo, c.proveedor, c.identificador_transaccion as clave, c.tienda_producto_id, c.creado_en
      from public.compras_usuario c
     where c.proveedor = 'google_play' and c.estado = 'pagada' and c.tienda_finalizada_en is null and c.tienda_producto_id is not null
       and c.identificador_transaccion is not null
    union all
    select 'suscripcion'::text, s.proveedor, s.identificador_externo, s.tienda_producto_id, s.creado_en
      from public.suscripciones s
     where s.proveedor = 'google_play' and s.tienda_finalizada_en is null and s.tienda_producto_id is not null and s.identificador_externo is not null
       and s.estado in ('activa', 'periodo_prueba', 'cancelada')
  ) x
  order by x.creado_en
  limit least(greatest(coalesce(p_limit, 50), 1), 200);
$$;

-- 11. What the client may read about itself (S-05)
create or replace function public.app_store_entitlements(p_usuario uuid)
returns table(saldo integer, es_premium boolean, estado_suscripcion text, producto_codigo text, proveedor text, fin_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0),
         coalesce(e.es_premium, false), coalesce(e.estado_suscripcion, 'sin_suscripcion'), e.producto_codigo, e.proveedor, e.fin_en
    from (select 1) one
    left join public.estado_premium_usuario e on e.usuario_id = p_usuario;
$$;

-- 12. ECO-04 on the two provider-event functions (bodies of 20261022102400 + the reuse branch)
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

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.app_store_account_token(uuid)', 'public.app_store_inbox_put(text,text,text,text,text,text,jsonb)',
    'public.app_store_inbox_mark(text,text,text,text,text)', 'public.app_store_inbox_pending(integer)',
    'public.app_store_credit_purchase(uuid,uuid,text,text,text,text,text,jsonb)', 'public.app_store_refund_purchase(text,text,text)',
    'public.app_store_refund_reversed(text,text,text)',
    'public.app_store_upsert_subscription(uuid,uuid,text,text,text,text,text,text,timestamptz,timestamptz,timestamptz,text,bigint,text)',
    'public.app_store_mark_finalized(text,text,text)', 'public.app_store_pending_finalize(integer)', 'public.app_store_entitlements(uuid)']
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end
$$;

commit;
