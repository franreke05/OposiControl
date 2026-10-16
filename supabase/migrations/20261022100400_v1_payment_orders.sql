-- =============================================================================
-- V1 -- payment orders, credit once, refund chain (OposiTickets economy, step 2 of 3). Design: E1-V1-MINIDESIGN.md sections 1, 3, 5.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022100300.
--
-- The payment ORDER is the existing table `compras_usuario` (provider, product, state, price, currency, provider transaction id; the ledger row
-- of its credit points back to it through `transacciones_cartera.compra_id`). No parallel table.
--
-- WHAT IT DOES
--   * compras_usuario: frozen OT quantity (`cantidad_tickets`), per-user `idempotency_key` + request fingerprint (`huella_solicitud`), `expira_en`
--     (TTL 30 min, decided by the DB clock), `pagada_en`; UNIQUE (proveedor, identificador_transaccion); states widened with `expirada` and `en_revision`;
--     a trigger enforces the state machine (forward only, terminal states immutable) and freezes amount/currency/product/quantity/user/provider.
--       pendiente -> pagada | fallida | cancelada | expirada | en_revision ; expirada -> pagada (LATE, verified-provider function only) | en_revision
--       pagada -> reembolsada | en_revision ; fallida | cancelada | reembolsada -> en_revision ; en_revision -> (admin function, later)
--   * eventos_pago: (proveedor, evento_id) primary key, written in the SAME transaction as the credit (webhook / receipt dedup).
--   * refund_case: what a refund could not reverse automatically (spent OT) and the traced debits; the policy is NEEDS_OWNER_DECISION and the
--     function that applies it is NOT part of this migration (a refund never silently revokes and never silently keeps).
--   * Functions (SECURITY DEFINER, search_path '', EXECUTE service_role only): app_create_payment_order, app_get_payment_order,
--     app_credit_payment_order, app_refund_payment_order. Every one takes the wallet lock first; replay by (usuario_id, idempotency_key) or by event id.
--   * Prices, quantities and currency are read from `productos_comerciales`; nothing is invented here. A product without `cantidad_tickets` and
--     `precio_centimos` > 0 is not orderable (BLOCKED_BY_PRODUCT_DECISION until the owner sets them).
--
-- The `proveedor` CHECK is NOT widened here: the sandbox provider exists only in the LOCAL overlay (ops/local-exam/sql/97_v1_sandbox_provider.sql).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_refund_payment_order(text, text, uuid);
-- drop function if exists public.app_credit_payment_order(text, text, uuid, text, integer, text, text);
-- drop function if exists public.app_get_payment_order(uuid, uuid);
-- drop function if exists public.app_create_payment_order(uuid, text, text, text);
-- drop function if exists private.payment_order_result(text, uuid);
-- drop table if exists public.refund_case;
-- drop table if exists public.eventos_pago;
-- drop trigger if exists trg_compras_usuario_order_machine on public.compras_usuario;
-- drop function if exists private.tg_compras_usuario_order_machine();
-- drop index if exists public.ux_compras_usuario_usuario_idempotency;
-- drop index if exists public.ux_compras_usuario_proveedor_transaccion;
-- alter table public.compras_usuario drop constraint if exists compras_usuario_estado_check;
-- alter table public.compras_usuario add constraint compras_usuario_estado_check check ((estado = any (array['pendiente'::text, 'pagada'::text, 'cancelada'::text, 'reembolsada'::text, 'fallida'::text])));
-- alter table public.compras_usuario drop constraint if exists compras_usuario_cantidad_tickets_check;
-- alter table public.compras_usuario drop column if exists cantidad_tickets;
-- alter table public.compras_usuario drop column if exists idempotency_key;
-- alter table public.compras_usuario drop column if exists huella_solicitud;
-- alter table public.compras_usuario drop column if exists expira_en;
-- alter table public.compras_usuario drop column if exists pagada_en;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_create_payment_order(uuid,text,text,text)'), ('public.app_get_payment_order(uuid,uuid)'),
--                ('public.app_credit_payment_order(text,text,uuid,text,integer,text,text)'), ('public.app_refund_payment_order(text,text,uuid)')) f(n)
-- union all
-- select 'table_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select')
--                or has_table_privilege('anon', t.n, 'insert') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('service_role', t.n, 'insert') or has_table_privilege('service_role', t.n, 'update') or has_table_privilege('service_role', t.n, 'delete')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.eventos_pago'), ('public.refund_case')) t(n)
-- union all
-- select 'sandbox_in_production_check',
--        (case when exists (select 1 from pg_constraint where conname = 'compras_usuario_proveedor_check' and pg_get_constraintdef(oid) ilike '%sandbox%') then 1 else 0 end)::bigint
-- union all
-- select 'order_state_machine_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_compras_usuario_order_machine' and not tgisinternal) then 0 else 1 end)::bigint
-- union all
-- select 'order_unique_guards_missing',
--        (case when exists (select 1 from pg_indexes where indexname = 'ux_compras_usuario_proveedor_transaccion')
--               and exists (select 1 from pg_indexes where indexname = 'ux_compras_usuario_usuario_idempotency') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regclass('public.compras_usuario') is null or to_regclass('public.transacciones_cartera') is null
     or to_regprocedure('private.ot_credit_remaining(uuid)') is null or to_regclass('public.ot_consumos') is null
     or to_regclass('public.productos_comerciales') is null or to_regclass('public.auditoria_log') is null then
    raise exception using errcode = 'P0001', message = 'V1_ORDERS_PREFLIGHT',
      hint = 'apply 20261022100300 first; compras_usuario, productos_comerciales and auditoria_log must exist';
  end if;
  if exists (select 1 from public.compras_usuario c where c.identificador_transaccion is not null group by c.proveedor, c.identificador_transaccion having count(*) > 1) then
    raise exception using errcode = 'P0001', message = 'V1_ORDERS_PREFLIGHT',
      hint = 'two orders share (proveedor, identificador_transaccion); resolve with the owner first (nothing was changed)';
  end if;
end
$$;

-- 1. compras_usuario as the PaymentOrder
alter table public.compras_usuario add column if not exists cantidad_tickets integer;
alter table public.compras_usuario add column if not exists idempotency_key text;
alter table public.compras_usuario add column if not exists huella_solicitud text;
alter table public.compras_usuario add column if not exists expira_en timestamptz;
alter table public.compras_usuario add column if not exists pagada_en timestamptz;
alter table public.compras_usuario drop constraint if exists compras_usuario_cantidad_tickets_check;
alter table public.compras_usuario add constraint compras_usuario_cantidad_tickets_check check (cantidad_tickets is null or cantidad_tickets > 0);
alter table public.compras_usuario drop constraint if exists compras_usuario_estado_check;
alter table public.compras_usuario add constraint compras_usuario_estado_check
  check ((estado = any (array['pendiente'::text, 'pagada'::text, 'cancelada'::text, 'reembolsada'::text, 'fallida'::text, 'expirada'::text, 'en_revision'::text])));
create unique index if not exists ux_compras_usuario_usuario_idempotency
  on public.compras_usuario (usuario_id, idempotency_key) where idempotency_key is not null;
create unique index if not exists ux_compras_usuario_proveedor_transaccion
  on public.compras_usuario (proveedor, identificador_transaccion) where identificador_transaccion is not null;

-- State machine + frozen fields. Legacy rows (no frozen quantity) keep their old freedom except for the state graph.
create or replace function private.tg_compras_usuario_order_machine()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.estado is distinct from old.estado and not (
       (old.estado = 'pendiente'   and new.estado in ('pagada', 'fallida', 'cancelada', 'expirada', 'en_revision'))
    or (old.estado = 'expirada'    and new.estado in ('pagada', 'en_revision'))
    or (old.estado = 'pagada'      and new.estado in ('reembolsada', 'en_revision'))
    or (old.estado in ('fallida', 'cancelada', 'reembolsada') and new.estado = 'en_revision')
  ) then
    raise exception using errcode = '55000', message = 'ORDER_STATE_TRANSITION_FORBIDDEN', hint = old.estado || ' -> ' || new.estado;
  end if;
  if old.cantidad_tickets is not null and (
       new.cantidad_tickets is distinct from old.cantidad_tickets or new.precio_centimos is distinct from old.precio_centimos
    or new.moneda is distinct from old.moneda or new.producto_codigo is distinct from old.producto_codigo
    or new.usuario_id is distinct from old.usuario_id or new.proveedor is distinct from old.proveedor
    or new.idempotency_key is distinct from old.idempotency_key or new.huella_solicitud is distinct from old.huella_solicitud
    or new.expira_en is distinct from old.expira_en
  ) then
    raise exception using errcode = '55000', message = 'ORDER_FROZEN';
  end if;
  if old.identificador_transaccion is not null and new.identificador_transaccion is distinct from old.identificador_transaccion then
    raise exception using errcode = '55000', message = 'ORDER_FROZEN';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_compras_usuario_order_machine() from public, anon, authenticated;
drop trigger if exists trg_compras_usuario_order_machine on public.compras_usuario;
create trigger trg_compras_usuario_order_machine before update on public.compras_usuario
  for each row execute function private.tg_compras_usuario_order_machine();

-- 2. Event dedup and refund cases (backend only: RLS on, no client grant, service_role reads)
create table if not exists public.eventos_pago (
  proveedor text not null,
  evento_id text not null,
  compra_id uuid references public.compras_usuario(id) on delete set null,
  resultado text,
  creado_en timestamptz not null default now(),
  constraint eventos_pago_pkey primary key (proveedor, evento_id)
);
alter table public.eventos_pago enable row level security;
revoke all on table public.eventos_pago from public, anon, authenticated, service_role;
grant select on table public.eventos_pago to service_role;

create table if not exists public.refund_case (
  id uuid primary key default gen_random_uuid(),
  compra_id uuid not null unique references public.compras_usuario(id),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  ot_revertidos integer not null check (ot_revertidos >= 0),
  ot_gastados integer not null check (ot_gastados > 0),
  detalle jsonb not null default '[]'::jsonb,
  estado text not null default 'abierto' check (estado in ('abierto', 'resuelto')),
  politica text,
  creado_en timestamptz not null default now(),
  resuelto_en timestamptz
);
alter table public.refund_case enable row level security;
revoke all on table public.refund_case from public, anon, authenticated, service_role;
grant select on table public.refund_case to service_role;

-- 3. Shared result shape (one row describing the order after the operation).
create or replace function private.payment_order_result(p_outcome text, p_orden uuid)
returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
              moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
language sql
stable
set search_path = ''
as $$
  select p_outcome, c.id, c.estado, c.producto_codigo, c.cantidad_tickets, c.precio_centimos, c.moneda, c.proveedor, c.expira_en, c.pagada_en,
         (select t.id from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets'),
         coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = c.usuario_id), 0)
    from public.compras_usuario c where c.id = p_orden;
$$;
revoke all on function private.payment_order_result(text, uuid) from public, anon, authenticated;

-- 4. Create an order (idempotent by (usuario_id, idempotency_key); amounts come from the catalogue and are frozen).
create or replace function public.app_create_payment_order(p_usuario uuid, p_producto text, p_proveedor text, p_idem_key text)
returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
              moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_key text := btrim(p_idem_key);
  v_fp text;
  v_prod public.productos_comerciales%rowtype;
  v_o public.compras_usuario%rowtype;
begin
  -- (Slice1 rule: identity tables are not read here; the profile row exists for every account)
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if v_key is null or length(v_key) = 0 or length(v_key) > 128 then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REQUIRED';
  end if;
  if p_producto is null or length(btrim(p_producto)) = 0 or p_proveedor is null or length(btrim(p_proveedor)) = 0 then
    raise exception using errcode = 'P0001', message = 'ORDER_REQUEST_INVALID';
  end if;
  v_fp := encode(sha256(convert_to('order|' || p_producto || '|' || p_proveedor, 'utf8')), 'hex');

  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));

  select c.* into v_o from public.compras_usuario c where c.usuario_id = p_usuario and c.idempotency_key = v_key;
  if found then
    if v_o.huella_solicitud is distinct from v_fp then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REUSED';
    end if;
    update public.compras_usuario c set estado = 'expirada' where c.id = v_o.id and c.estado = 'pendiente' and c.expira_en <= now();
    return query select * from private.payment_order_result('REPLAY', v_o.id);
    return;
  end if;

  select p.* into v_prod from public.productos_comerciales p
   where p.codigo = p_producto and p.activo and p.visible and p.tipo_producto = 'pack_tickets'
     and p.cantidad_tickets is not null and p.cantidad_tickets > 0 and p.precio_centimos is not null and p.precio_centimos > 0;
  if not found then
    raise exception using errcode = 'P0001', message = 'PRODUCT_NOT_AVAILABLE';
  end if;

  -- E2-V1-E02: at most 5 open (pending, not yet expired) orders per user; the 6th is refused, nothing is written (technical bound, not a product value).
  if (select count(*) from public.compras_usuario c where c.usuario_id = p_usuario and c.cantidad_tickets is not null and c.estado = 'pendiente' and c.expira_en > now()) >= 5 then
    raise exception using errcode = 'P0001', message = 'ORDER_PENDING_LIMIT';
  end if;

  -- C-P2 (E2): daily cap on OT bought per user, ON by default (config tope_ot_comprados_dia, finite; Control editable), Madrid day on the DB clock; open (unexpired) and paid orders count.
  if (select coalesce(sum(c.cantidad_tickets), 0) from public.compras_usuario c
       where c.usuario_id = p_usuario and c.cantidad_tickets is not null and c.creado_en >= (date_trunc('day', now() at time zone coalesce((select k.valor_text from public.configuracion_v1 k where k.clave = 'zona_horaria'), 'Europe/Madrid')) at time zone coalesce((select k.valor_text from public.configuracion_v1 k where k.clave = 'zona_horaria'), 'Europe/Madrid'))
         and (c.estado = 'pagada' or (c.estado = 'pendiente' and c.expira_en > now()))) + v_prod.cantidad_tickets > private.config_int('tope_ot_comprados_dia', 5200) then
    raise exception using errcode = 'P0001', message = 'PURCHASE_CAP_REACHED';
  end if;

  insert into public.compras_usuario (usuario_id, proveedor, producto_codigo, tipo_producto, estado, precio_centimos, moneda,
                                      cantidad_tickets, idempotency_key, huella_solicitud, expira_en)
  values (p_usuario, p_proveedor, v_prod.codigo, 'opositickets', 'pendiente', v_prod.precio_centimos, v_prod.moneda,
          v_prod.cantidad_tickets, v_key, v_fp, now() + interval '30 minutes')
  returning * into v_o;

  return query select * from private.payment_order_result('CREATED', v_o.id);
end;
$$;

-- 5. Read one order of the caller (404-like for others) with lazy expiry.
create or replace function public.app_get_payment_order(p_usuario uuid, p_orden uuid)
returns table(outcome text, orden_id uuid, estado text, producto_codigo text, cantidad_tickets integer, importe_centimos integer,
              moneda text, proveedor text, expira_en timestamptz, pagada_en timestamptz, transaccion_id uuid, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_o public.compras_usuario%rowtype;
begin
  select c.* into v_o from public.compras_usuario c where c.id = p_orden and c.usuario_id = p_usuario and c.cantidad_tickets is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
  end if;
  update public.compras_usuario c set estado = 'expirada' where c.id = v_o.id and c.estado = 'pendiente' and c.expira_en <= now();
  return query select * from private.payment_order_result('READ', v_o.id);
end;
$$;

-- 6. Credit OT for a VERIFIED payment event. Exactly once: event dedup + one credit row per order + one order per provider transaction.
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
  -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row), same as redeem/create/get: find the owner without locking,
  -- take the wallet lock, then lock and re-read the order.
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

-- 7. Refund of a credited order, POLICY P2 (owner decision 2026-10-16): reverse ONLY the unspent part of THIS credit; for the part that was SPENT on
--    exam access (compra_examen debits traced to this credit by ot_consumos) shorten the still-valid access in proportion (consumed/price of the
--    purchase x the days GRANTED at purchase, kept in the debit metadata as dias; an older debit without it falls back to the exam current dias_acceso_compra); never below now, never lengthen, expired access untouched, no silent negative balance, no revocation.
--    Spent OT on anything else (ajuste, regalo...) has no access to shorten: it stays spent and is recorded in the closed case.
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
  -- E2-V1-E01: ONE lock order everywhere (wallet advisory lock FIRST, then the order row), same as redeem/create/get: find the owner without locking,
  -- take the wallet lock, then lock and re-read the order.
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

  if v_o.estado <> 'pagada' then
    -- nothing was credited for pendiente / fallida / cancelada / expirada; a second refund of reembolsada is a no-op
    v_outcome := case when v_o.estado = 'reembolsada' then 'REPLAY' else 'NOOP' end;
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

revoke all on function public.app_create_payment_order(uuid, text, text, text) from public, anon, authenticated;
revoke all on function public.app_get_payment_order(uuid, uuid) from public, anon, authenticated;
revoke all on function public.app_credit_payment_order(text, text, uuid, text, integer, text, text) from public, anon, authenticated;
revoke all on function public.app_refund_payment_order(text, text, uuid) from public, anon, authenticated;
grant execute on function public.app_create_payment_order(uuid, text, text, text) to service_role;
grant execute on function public.app_get_payment_order(uuid, uuid) to service_role;
grant execute on function public.app_credit_payment_order(text, text, uuid, text, integer, text, text) to service_role;
grant execute on function public.app_refund_payment_order(text, text, uuid) to service_role;

commit;
