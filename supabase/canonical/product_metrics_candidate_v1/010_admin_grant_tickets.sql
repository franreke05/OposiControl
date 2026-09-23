-- PRODUCT METRICS V1 — ATOMIC ADMIN WALLET OPERATION
-- The only backend writer for administrative ticket adjustments.
begin;

create or replace function public.admin_grant_tickets(
  p_usuario_id uuid,
  p_cantidad integer,
  p_descripcion text,
  p_idempotency_key text,
  p_tipo text default 'ajuste'
)
returns table (
  transaction_id uuid,
  wallet_user_id uuid,
  cantidad integer,
  saldo_anterior integer,
  saldo_resultante integer,
  idempotency_key text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_key text := btrim(p_idempotency_key);
  v_existing public.transacciones_cartera%rowtype;
  v_saldo_anterior integer;
  v_saldo_resultante integer;
  v_transaction_id uuid;
begin
  if p_usuario_id is null or not exists (select 1 from auth.users u where u.id = p_usuario_id) then
    raise exception 'WALLET_INVALID_USER';
  end if;
  if p_cantidad is null or p_cantidad = 0 then
    raise exception 'WALLET_AMOUNT_INVALID';
  end if;
  if p_tipo is null or p_tipo not in ('ajuste', 'reporte_validado') then
    raise exception 'WALLET_TYPE_INVALID';
  end if;
  if p_tipo <> 'ajuste' and p_cantidad < 0 then
    raise exception 'WALLET_GRANT_AMOUNT_MUST_BE_POSITIVE';
  end if;
  if p_descripcion is null or length(btrim(p_descripcion)) = 0 then
    raise exception 'WALLET_DESCRIPTION_REQUIRED';
  end if;
  if p_idempotency_key is null or length(v_key) = 0 then
    raise exception 'WALLET_IDEMPOTENCY_KEY_REQUIRED';
  end if;

  -- Same-key calls serialize before lookup, so a retry cannot create a second ledger row.
  perform pg_advisory_xact_lock(hashtextextended('wallet-key:' || v_key, 0));

  select t.* into v_existing
  from public.transacciones_cartera t
  where t.idempotency_key = v_key
  for update;

  if found then
    if v_existing.usuario_id is not distinct from p_usuario_id
      and v_existing.tipo is not distinct from p_tipo
      and v_existing.cantidad is not distinct from p_cantidad
      and v_existing.descripcion is not distinct from btrim(p_descripcion)
      and v_existing.compra_id is null
      and v_existing.referencia_tipo is not distinct from 'admin'
      and v_existing.referencia_id is null then
      return query select v_existing.id, v_existing.usuario_id, v_existing.cantidad,
        v_existing.saldo_anterior, v_existing.saldo_resultante, v_existing.idempotency_key;
      return;
    end if;
    raise exception 'WALLET_IDEMPOTENCY_CONFLICT';
  end if;

  -- The primary key serializes all balance mutations for this user.
  insert into public.carteras_usuario (usuario_id, saldo_tickets, actualizado_en)
  values (p_usuario_id, 0, now())
  on conflict (usuario_id) do nothing;

  select w.saldo_tickets into v_saldo_anterior
  from public.carteras_usuario w
  where w.usuario_id = p_usuario_id
  for update;

  v_saldo_resultante := v_saldo_anterior + p_cantidad;
  if v_saldo_resultante < 0 then
    raise exception 'WALLET_INSUFFICIENT_BALANCE';
  end if;

  update public.carteras_usuario
  set saldo_tickets = v_saldo_resultante,
      actualizado_en = now()
  where usuario_id = p_usuario_id;

  insert into public.transacciones_cartera (
    usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante,
    descripcion, referencia_tipo, idempotency_key, metadatos
  ) values (
    p_usuario_id, p_tipo, p_cantidad, v_saldo_anterior, v_saldo_resultante,
    btrim(p_descripcion), 'admin', v_key, '{}'::jsonb
  ) returning id into v_transaction_id;

  return query select v_transaction_id, p_usuario_id, p_cantidad,
    v_saldo_anterior, v_saldo_resultante, v_key;
end;
$$;

revoke execute on function public.admin_grant_tickets(uuid, integer, text, text, text)
  from public, anon, authenticated;
grant execute on function public.admin_grant_tickets(uuid, integer, text, text, text)
  to service_role;

commit;
