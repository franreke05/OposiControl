-- =============================================================================
-- V1 -- wallet ledger hardening (OposiTickets economy, step 1 of 3). Joint design E1+E2: E1-V1-MINIDESIGN.md sections 2 and 5.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN (no production in this phase). Forward-only.
--
-- WHAT IT DOES
--   1. Ledger = the ONLY source of truth of a balance. `transacciones_cartera` becomes append-only and self-validating:
--        - new column `seq bigint`: per-WALLET sequence 1..n (UNIQUE (usuario_id, seq)); never a global sequence;
--        - BEFORE INSERT trigger private.tg_ledger_before_insert: takes the per-user lock, computes seq / saldo_anterior /
--          saldo_resultante from the chain itself, REJECTS a row whose supplied values disagree, refuses a negative result;
--        - BEFORE UPDATE/DELETE trigger: the ledger is immutable (corrections are new rows). A row may only go when its user
--          has been deleted from auth.users (account-deletion cascade).
--   2. `carteras_usuario.saldo_tickets` is only a SNAPSHOT derived inside the same transaction:
--        - AFTER INSERT trigger on the ledger upserts it (the ONLY writer);
--        - a guard trigger on carteras_usuario rejects every other write (checks pg_trigger_depth(), no settable flag);
--        - app roles lose INSERT/UPDATE/DELETE/TRUNCATE on both tables.
--   3. `ot_consumos` (append-only) traces which credit paid which debit (FIFO by seq; a refund reversal is allocated to the
--      credit it reverses). A credit's unspent amount is derived, never stored.
--   4. Reconciliation: public.app_wallet_reconcile(p_usuario) / view v_wallet_reconcile return 0 rows when ledger and snapshot agree.
--   5. Idempotency key unique PER USER (replaces the global unique that let user B squat user A's key).
--   6. public.admin_grant_tickets is redefined (same signature/grants): per-user key, no direct snapshot write.
--   6b. Every NEW profile gets an EMPTY wallet row (balance 0) in the same transaction (AFTER INSERT trigger on perfiles_usuario), so the
--       balance of a new user is confirmed by a real row; a missing row (older users) is equivalent to a ledger sum of 0 and D6-01 answers 0.
--   7. LEGACY (E5-V1-F01 fix): `private.v1_ledger_backfill()` runs once at the end of this migration and is idempotent. For every wallet that still has ledger
--      rows without `seq`, those rows get seq 1..n in (creado_en, id) order, their saldo_anterior / saldo_resultante are recomputed as the running sum of `cantidad`
--      (a chain that already closes is unchanged), and ONE opening row (`ajuste`, referencia_tipo 'legacy_opening') = snapshot - sum(existing rows) is appended
--      ONLY when that difference is not zero. A wallet with a snapshot and NO rows gets the opening row = snapshot. A wallet whose old rows already sum to its
--      snapshot gets NO new row (no double count). Mixed state (some rows with seq, some without) fails closed. Legacy debits are traced FIFO (ot_consumos).
--      `app_wallet_reconcile` now covers rows WITHOUT seq and compares the snapshot with the sum of ALL rows, so a double count is reported.
--      The number of wallets affected is a MAIN count I prepared as an aggregate-only script and did NOT run without the owner's approval.
--
-- LOCKING: ALTER TABLE ... ADD COLUMN (nullable) is catalog-only; unique constraints build indexes (SHARE lock on the ledger, small table).
-- lock_timeout = 5s. DATA LOSS: none (rollback drops derived structures only). RLS: unchanged. GRANTS: service_role loses writes on the two tables.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). It restores the previous structure; the opening rows (plain ledger rows) remain.
-- ROLLBACK-BEGIN
-- begin;
-- drop view if exists public.v_wallet_reconcile;
-- drop function if exists private.v1_ledger_backfill(uuid);
-- drop function if exists public.app_wallet_reconcile(uuid);
-- drop trigger if exists trg_ledger_after_insert on public.transacciones_cartera;
-- drop trigger if exists trg_ledger_before_insert on public.transacciones_cartera;
-- drop trigger if exists trg_ledger_immutable on public.transacciones_cartera;
-- drop trigger if exists trg_wallet_snapshot_guard on public.carteras_usuario;
-- drop trigger if exists trg_perfil_crea_cartera on public.perfiles_usuario;
-- drop function if exists private.tg_perfil_crea_cartera();
-- drop trigger if exists trg_ot_consumos_immutable on public.ot_consumos;
-- drop table if exists public.ot_consumos;
-- drop function if exists private.tg_ledger_before_insert();
-- drop function if exists private.tg_ledger_after_insert();
-- drop function if exists private.tg_ledger_immutable();
-- drop function if exists private.tg_wallet_snapshot_guard();
-- drop function if exists private.tg_ot_consumos_immutable();
-- drop function if exists private.ledger_allocate_debit(uuid, uuid, integer, text, uuid);
-- drop function if exists private.ot_credit_remaining(uuid);
-- alter table public.transacciones_cartera drop constraint if exists transacciones_cartera_usuario_seq_key;
-- alter table public.transacciones_cartera drop constraint if exists transacciones_cartera_usuario_idempotency_key;
-- drop index if exists public.ux_transacciones_cartera_compra_tipo;
-- alter table public.transacciones_cartera drop column if exists seq;
-- alter table public.transacciones_cartera add constraint transacciones_cartera_idempotency_key_key unique (idempotency_key);
-- grant insert, update, delete, truncate on table public.transacciones_cartera to service_role;
-- grant insert, update, delete, truncate on table public.carteras_usuario to service_role;
-- create or replace function public.admin_grant_tickets(p_usuario_id uuid, p_cantidad integer, p_descripcion text, p_idempotency_key text, p_tipo text default 'ajuste'::text)
--  returns table(transaction_id uuid, wallet_user_id uuid, cantidad integer, saldo_anterior integer, saldo_resultante integer, idempotency_key text)
--  language plpgsql security definer set search_path to '' as $f$
-- declare
--   v_key text := btrim(p_idempotency_key);
--   v_existing public.transacciones_cartera%rowtype;
--   v_saldo_anterior integer;
--   v_saldo_resultante integer;
--   v_transaction_id uuid;
-- begin
--   if p_usuario_id is null or not exists (select 1 from auth.users u where u.id = p_usuario_id) then raise exception 'WALLET_INVALID_USER'; end if;
--   if p_cantidad is null or p_cantidad = 0 then raise exception 'WALLET_AMOUNT_INVALID'; end if;
--   if p_tipo is null or p_tipo not in ('ajuste', 'reporte_validado') then raise exception 'WALLET_TYPE_INVALID'; end if;
--   if p_tipo <> 'ajuste' and p_cantidad < 0 then raise exception 'WALLET_GRANT_AMOUNT_MUST_BE_POSITIVE'; end if;
--   if p_descripcion is null or length(btrim(p_descripcion)) = 0 then raise exception 'WALLET_DESCRIPTION_REQUIRED'; end if;
--   if p_idempotency_key is null or length(v_key) = 0 then raise exception 'WALLET_IDEMPOTENCY_KEY_REQUIRED'; end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet-key:' || v_key, 0));
--   select t.* into v_existing from public.transacciones_cartera t where t.idempotency_key = v_key for update;
--   if found then
--     if v_existing.usuario_id is not distinct from p_usuario_id and v_existing.tipo is not distinct from p_tipo
--       and v_existing.cantidad is not distinct from p_cantidad and v_existing.descripcion is not distinct from btrim(p_descripcion)
--       and v_existing.compra_id is null and v_existing.referencia_tipo is not distinct from 'admin' and v_existing.referencia_id is null then
--       return query select v_existing.id, v_existing.usuario_id, v_existing.cantidad, v_existing.saldo_anterior, v_existing.saldo_resultante, v_existing.idempotency_key;
--       return;
--     end if;
--     raise exception 'WALLET_IDEMPOTENCY_CONFLICT';
--   end if;
--   insert into public.carteras_usuario (usuario_id, saldo_tickets, actualizado_en) values (p_usuario_id, 0, now()) on conflict (usuario_id) do nothing;
--   select w.saldo_tickets into v_saldo_anterior from public.carteras_usuario w where w.usuario_id = p_usuario_id for update;
--   v_saldo_resultante := v_saldo_anterior + p_cantidad;
--   if v_saldo_resultante < 0 then raise exception 'WALLET_INSUFFICIENT_BALANCE'; end if;
--   update public.carteras_usuario set saldo_tickets = v_saldo_resultante, actualizado_en = now() where usuario_id = p_usuario_id;
--   insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion, referencia_tipo, idempotency_key, metadatos)
--   values (p_usuario_id, p_tipo, p_cantidad, v_saldo_anterior, v_saldo_resultante, btrim(p_descripcion), 'admin', v_key, '{}'::jsonb) returning id into v_transaction_id;
--   return query select v_transaction_id, p_usuario_id, p_cantidad, v_saldo_anterior, v_saldo_resultante, v_key;
-- end;
-- $f$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'ledger_trigger_missing:' || t.n as check_name,
--        (case when exists (select 1 from pg_trigger g where g.tgname = t.n and not g.tgisinternal) then 0 else 1 end)::bigint as failures
--   from (values ('trg_ledger_before_insert'), ('trg_ledger_after_insert'), ('trg_ledger_immutable'), ('trg_wallet_snapshot_guard'), ('trg_ot_consumos_immutable'), ('trg_perfil_crea_cartera')) t(n)
-- union all
-- select 'client_or_service_write_grant:' || g.t || ':' || g.r,
--        (case when has_table_privilege(g.r, g.t, 'insert') or has_table_privilege(g.r, g.t, 'update') or has_table_privilege(g.r, g.t, 'delete') then 1 else 0 end)::bigint
--   from (values ('public.transacciones_cartera', 'anon'), ('public.transacciones_cartera', 'authenticated'), ('public.transacciones_cartera', 'service_role'),
--                ('public.carteras_usuario', 'anon'), ('public.carteras_usuario', 'authenticated'), ('public.carteras_usuario', 'service_role'),
--                ('public.ot_consumos', 'anon'), ('public.ot_consumos', 'authenticated'), ('public.ot_consumos', 'service_role')) g(t, r)
-- union all
-- select 'reconcile_not_clean', count(*)::bigint from public.app_wallet_reconcile(null)
-- union all
-- select 'idempotency_not_per_user',
--        (case when exists (select 1 from pg_constraint where conname = 'transacciones_cartera_usuario_idempotency_key' and contype = 'u')
--               and not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_idempotency_key_key') then 0 else 1 end)::bigint
-- union all
-- select 'reconcile_executable_by_clients',
--        (case when has_function_privilege('anon', 'public.app_wallet_reconcile(uuid)', 'execute') or has_function_privilege('authenticated', 'public.app_wallet_reconcile(uuid)', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed): the objects this migration builds on exist.
do $$
begin
  if to_regclass('public.transacciones_cartera') is null or to_regclass('public.carteras_usuario') is null
     or to_regclass('public.compras_usuario') is null or to_regnamespace('private') is null then
    raise exception using errcode = 'P0001', message = 'V1_LEDGER_PREFLIGHT',
      hint = 'transacciones_cartera, carteras_usuario, compras_usuario and schema private must exist';
  end if;
end
$$;

-- PREFLIGHT 2: the per-order uniqueness below must not collide with existing rows (nothing is changed to make this pass).
do $$
begin
  if exists (select 1 from public.transacciones_cartera t where t.compra_id is not null group by t.compra_id, t.tipo having count(*) > 1) then
    raise exception using errcode = 'P0001', message = 'V1_LEDGER_PREFLIGHT',
      hint = 'more than one ledger row of the same type references one order (compra_id); resolve with the owner first';
  end if;
end
$$;

-- 1. Columns and constraints (nothing is rewritten).
alter table public.transacciones_cartera add column if not exists seq bigint;

-- 2. (legacy rows are sequenced and the opening difference appended by private.v1_ledger_backfill() at the END of this migration)

alter table public.transacciones_cartera drop constraint if exists transacciones_cartera_idempotency_key_key;
alter table public.transacciones_cartera drop constraint if exists transacciones_cartera_usuario_idempotency_key;
alter table public.transacciones_cartera
  add constraint transacciones_cartera_usuario_idempotency_key unique (usuario_id, idempotency_key);
alter table public.transacciones_cartera drop constraint if exists transacciones_cartera_usuario_seq_key;
alter table public.transacciones_cartera
  add constraint transacciones_cartera_usuario_seq_key unique (usuario_id, seq);
-- A credit is created exactly once per order, a refund reversal exactly once per order (both reference the order through compra_id).
create unique index if not exists ux_transacciones_cartera_compra_tipo
  on public.transacciones_cartera (compra_id, tipo) where compra_id is not null;

-- 3. Traceability: which credit paid which debit.
create table if not exists public.ot_consumos (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  debit_tx_id uuid not null references public.transacciones_cartera(id) on delete cascade,
  credit_tx_id uuid not null references public.transacciones_cartera(id) on delete cascade,
  cantidad integer not null check (cantidad > 0),
  creado_en timestamptz not null default now(),
  constraint ot_consumos_debit_credit_key unique (debit_tx_id, credit_tx_id)
);
create index if not exists idx_ot_consumos_credit on public.ot_consumos (credit_tx_id);
create index if not exists idx_ot_consumos_usuario on public.ot_consumos (usuario_id);
alter table public.ot_consumos enable row level security;
revoke all on table public.ot_consumos from public, anon, authenticated, service_role;
grant select on table public.ot_consumos to service_role;

-- 4. Helpers and triggers (private schema; never executable by clients).
create or replace function private.ot_credit_remaining(p_credit uuid)
returns integer
language sql
stable
set search_path = ''
as $$
  select t.cantidad - coalesce((select sum(k.cantidad)::integer from public.ot_consumos k where k.credit_tx_id = t.id), 0)
    from public.transacciones_cartera t
   where t.id = p_credit and t.seq is not null and t.cantidad > 0;
$$;

create or replace function private.ledger_allocate_debit(p_debit uuid, p_user uuid, p_amount integer, p_tipo text, p_ref uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_left integer := p_amount;
  v_take integer;
  v_remaining integer;
  r record;
begin
  if p_tipo = 'devolucion' then
    -- A refund reversal removes OT from the ONE credit it names (referencia_id), never from other purchases or promotions.
    v_remaining := private.ot_credit_remaining(p_ref);
    if p_ref is null or v_remaining is null or v_remaining < p_amount then
      raise exception using errcode = '23514', message = 'REFUND_EXCEEDS_UNSPENT';
    end if;
    insert into public.ot_consumos (usuario_id, debit_tx_id, credit_tx_id, cantidad) values (p_user, p_debit, p_ref, p_amount);
    return;
  end if;
  -- Any other debit consumes the oldest credits first (FIFO by seq; the legacy opening credit has seq 1, so it goes first).
  for r in
    select t.id, t.cantidad - coalesce((select sum(k.cantidad) from public.ot_consumos k where k.credit_tx_id = t.id), 0) as remaining
      from public.transacciones_cartera t
     where t.usuario_id = p_user and t.seq is not null and t.cantidad > 0
     order by t.seq
  loop
    exit when v_left = 0;
    continue when r.remaining <= 0;
    v_take := least(v_left, r.remaining::integer);
    insert into public.ot_consumos (usuario_id, debit_tx_id, credit_tx_id, cantidad) values (p_user, p_debit, r.id, v_take);
    v_left := v_left - v_take;
  end loop;
  if v_left > 0 then
    raise exception using errcode = '23514', message = 'WALLET_ALLOCATION_SHORTFALL';
  end if;
end;
$$;

create or replace function private.tg_ledger_before_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_seq bigint;
  v_prev integer;
  v_snapshot integer;
begin
  -- ONE lock per wallet for everything that touches its chain (the economic functions take the same lock first).
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || new.usuario_id::text, 0));
  select t.seq + 0, t.saldo_resultante into v_seq, v_prev
    from public.transacciones_cartera t
   where t.usuario_id = new.usuario_id and t.seq is not null
   order by t.seq desc limit 1;
  v_seq := coalesce(v_seq, 0) + 1;
  v_prev := coalesce(v_prev, 0);
  select c.saldo_tickets into v_snapshot from public.carteras_usuario c where c.usuario_id = new.usuario_id;
  if coalesce(v_snapshot, 0) <> v_prev then
    raise exception using errcode = '23514', message = 'WALLET_INCONSISTENT';
  end if;
  if new.cantidad is null or new.cantidad = 0 then
    raise exception using errcode = '23514', message = 'WALLET_AMOUNT_INVALID';
  end if;
  if new.seq is not null and new.seq <> v_seq then
    raise exception using errcode = '23514', message = 'WALLET_CHAIN_MISMATCH', hint = 'seq';
  end if;
  if new.saldo_anterior is not null and new.saldo_anterior <> v_prev then
    raise exception using errcode = '23514', message = 'WALLET_CHAIN_MISMATCH', hint = 'saldo_anterior';
  end if;
  if new.saldo_resultante is not null and new.saldo_resultante <> v_prev + new.cantidad then
    raise exception using errcode = '23514', message = 'WALLET_CHAIN_MISMATCH', hint = 'saldo_resultante';
  end if;
  new.seq := v_seq;
  new.saldo_anterior := v_prev;
  new.saldo_resultante := v_prev + new.cantidad;
  if new.saldo_resultante < 0 then
    raise exception using errcode = '23514', message = 'WALLET_INSUFFICIENT_BALANCE';
  end if;
  return new;
end;
$$;

create or replace function private.tg_ledger_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- The ONLY writer of the snapshot. The guard on carteras_usuario lets it through because it runs inside this trigger.
  insert into public.carteras_usuario (usuario_id, saldo_tickets, actualizado_en)
  values (new.usuario_id, new.saldo_resultante, now())
  on conflict (usuario_id) do update set saldo_tickets = excluded.saldo_tickets, actualizado_en = now();
  if new.cantidad < 0 then
    perform private.ledger_allocate_debit(new.id, new.usuario_id, -new.cantidad, new.tipo, new.referencia_id);
  end if;
  return null;
end;
$$;

create or replace function private.tg_ledger_immutable()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- Append-only. The only exception is the account-deletion cascade: the owner row is already gone from auth.users.
  if exists (select 1 from auth.users u where u.id = old.usuario_id) then
    raise exception using errcode = '55000', message = 'LEDGER_IMMUTABLE';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create or replace function private.tg_ot_consumos_immutable()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if exists (select 1 from auth.users u where u.id = old.usuario_id) and exists (select 1 from public.transacciones_cartera t where t.id = old.debit_tx_id) then
    raise exception using errcode = '55000', message = 'LEDGER_IMMUTABLE';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create or replace function private.tg_wallet_snapshot_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- Unforgeable: only a write issued from INSIDE another trigger (the ledger trigger) is nested deeper than this guard itself.
  if pg_trigger_depth() > 1 then
    if tg_op = 'DELETE' then return old; end if;
    return new;
  end if;
  -- An EMPTY wallet (balance 0, no movement yet) may be created: it carries no value and reconciles to 0.
  if tg_op = 'INSERT' and new.saldo_tickets = 0 then
    return new;
  end if;
  -- Account-deletion cascade: the owner is already gone.
  if tg_op = 'DELETE' and not exists (select 1 from auth.users u where u.id = old.usuario_id) then
    return old;
  end if;
  raise exception using errcode = '55000', message = 'WALLET_SNAPSHOT_READ_ONLY';
end;
$$;

revoke all on function private.ot_credit_remaining(uuid) from public, anon, authenticated;
revoke all on function private.ledger_allocate_debit(uuid, uuid, integer, text, uuid) from public, anon, authenticated;
revoke all on function private.tg_ledger_before_insert() from public, anon, authenticated;
revoke all on function private.tg_ledger_after_insert() from public, anon, authenticated;
revoke all on function private.tg_ledger_immutable() from public, anon, authenticated;
revoke all on function private.tg_ot_consumos_immutable() from public, anon, authenticated;
revoke all on function private.tg_wallet_snapshot_guard() from public, anon, authenticated;

drop trigger if exists trg_ledger_before_insert on public.transacciones_cartera;
create trigger trg_ledger_before_insert before insert on public.transacciones_cartera
  for each row execute function private.tg_ledger_before_insert();
drop trigger if exists trg_ledger_after_insert on public.transacciones_cartera;
create trigger trg_ledger_after_insert after insert on public.transacciones_cartera
  for each row execute function private.tg_ledger_after_insert();
drop trigger if exists trg_ledger_immutable on public.transacciones_cartera;
create trigger trg_ledger_immutable before update or delete on public.transacciones_cartera
  for each row execute function private.tg_ledger_immutable();
drop trigger if exists trg_ot_consumos_immutable on public.ot_consumos;
create trigger trg_ot_consumos_immutable before update or delete on public.ot_consumos
  for each row execute function private.tg_ot_consumos_immutable();
drop trigger if exists trg_wallet_snapshot_guard on public.carteras_usuario;
create trigger trg_wallet_snapshot_guard before insert or update or delete on public.carteras_usuario
  for each row execute function private.tg_wallet_snapshot_guard();

-- 4b. A new profile gets an empty wallet in the same transaction. Older users without a row are equivalent to a ledger sum of 0.
create or replace function private.tg_perfil_crea_cartera()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.carteras_usuario (usuario_id, saldo_tickets) values (new.usuario_id, 0) on conflict (usuario_id) do nothing;
  return new;
end;
$$;
revoke all on function private.tg_perfil_crea_cartera() from public, anon, authenticated;
drop trigger if exists trg_perfil_crea_cartera on public.perfiles_usuario;
create trigger trg_perfil_crea_cartera after insert on public.perfiles_usuario
  for each row execute function private.tg_perfil_crea_cartera();

-- 5. Reconciliation (0 rows expected). service_role only; E2 runs it after every race suite and E5 can run it.
create or replace function public.app_wallet_reconcile(p_usuario uuid default null)
returns table(usuario_id uuid, kind text, expected bigint, actual bigint)
language sql
stable
security definer
set search_path = ''
as $$
  -- (a) snapshot vs the sum of the chained ledger rows
  select w.usuario_id, 'SNAPSHOT_NE_LEDGER'::text, coalesce(l.s, 0)::bigint, w.saldo_tickets::bigint
    from (select c.usuario_id, c.saldo_tickets from public.carteras_usuario c where p_usuario is null or c.usuario_id = p_usuario) w
    left join (select t.usuario_id, sum(t.cantidad) s from public.transacciones_cartera t group by t.usuario_id) l on l.usuario_id = w.usuario_id
   where w.saldo_tickets <> coalesce(l.s, 0)
  union all
  -- (a2) chained rows exist but there is no wallet row at all
  select l.usuario_id, 'LEDGER_WITHOUT_SNAPSHOT'::text, l.s::bigint, 0::bigint
    from (select t.usuario_id, sum(t.cantidad) s from public.transacciones_cartera t where (p_usuario is null or t.usuario_id = p_usuario) group by t.usuario_id) l
   where not exists (select 1 from public.carteras_usuario c where c.usuario_id = l.usuario_id)
  union all
  -- (a3) ledger rows WITHOUT seq (legacy rows the backfill has not sequenced)
  select t.usuario_id, 'SEQLESS_ROWS'::text, count(*)::bigint, 0::bigint
    from public.transacciones_cartera t where t.seq is null and (p_usuario is null or t.usuario_id = p_usuario) group by t.usuario_id
  union all
  -- (b) per-wallet seq must be exactly 1..n
  select s.usuario_id, 'SEQ_GAP'::text, s.n::bigint, s.mx::bigint
    from (select t.usuario_id, count(*) n, max(t.seq) mx, min(t.seq) mn, count(distinct t.seq) dn
            from public.transacciones_cartera t where t.seq is not null and (p_usuario is null or t.usuario_id = p_usuario) group by t.usuario_id) s
   where s.mn <> 1 or s.mx <> s.n or s.dn <> s.n
  union all
  -- (c) the balance chain must close row by row and never go negative
  select c.usuario_id, 'CHAIN_BROKEN'::text, c.expected_anterior::bigint, c.saldo_anterior::bigint
    from (select t.usuario_id, t.saldo_anterior, t.saldo_resultante, t.cantidad,
                 coalesce(lag(t.saldo_resultante) over (partition by t.usuario_id order by t.seq), 0) as expected_anterior
            from public.transacciones_cartera t where t.seq is not null and (p_usuario is null or t.usuario_id = p_usuario)) c
   where c.saldo_anterior is distinct from c.expected_anterior or c.saldo_resultante <> c.saldo_anterior + c.cantidad or c.saldo_resultante < 0
$$;

create or replace view public.v_wallet_reconcile as select * from public.app_wallet_reconcile(null);

revoke all on function public.app_wallet_reconcile(uuid) from public, anon, authenticated;
grant execute on function public.app_wallet_reconcile(uuid) to service_role;
revoke all on table public.v_wallet_reconcile from public, anon, authenticated;
grant select on table public.v_wallet_reconcile to service_role;

-- 6. App roles cannot write the ledger or the snapshot directly; only the SECURITY DEFINER functions (owner) do.
revoke insert, update, delete, truncate on table public.transacciones_cartera from service_role;
revoke insert, update, delete, truncate on table public.carteras_usuario from service_role;

-- 7. admin_grant_tickets: same signature and grants, per-user idempotency key, no direct snapshot write (the ledger trigger does it).
create or replace function public.admin_grant_tickets(
  p_usuario_id uuid, p_cantidad integer, p_descripcion text, p_idempotency_key text, p_tipo text default 'ajuste'::text)
returns table(transaction_id uuid, wallet_user_id uuid, cantidad integer, saldo_anterior integer, saldo_resultante integer, idempotency_key text)
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_key text := btrim(p_idempotency_key);
  v_existing public.transacciones_cartera%rowtype;
  v_row public.transacciones_cartera%rowtype;
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

  -- The wallet lock serializes everything for this user; the key is looked up PER USER.
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario_id::text, 0));

  select t.* into v_existing
  from public.transacciones_cartera t
  where t.usuario_id = p_usuario_id and t.idempotency_key = v_key;

  if found then
    if v_existing.tipo is not distinct from p_tipo
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

  insert into public.transacciones_cartera (
    usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key, metadatos
  ) values (
    p_usuario_id, p_tipo, p_cantidad, btrim(p_descripcion), 'admin', v_key, '{}'::jsonb
  ) returning * into v_row;

  return query select v_row.id, v_row.usuario_id, v_row.cantidad, v_row.saldo_anterior, v_row.saldo_resultante, v_row.idempotency_key;
end;
$function$;

-- Same ACL as MAIN ({postgres, service_role}); explicit so a database that does not have the function yet gets the same grants.
revoke all on function public.admin_grant_tickets(uuid, integer, text, text, text) from public, anon, authenticated;
grant execute on function public.admin_grant_tickets(uuid, integer, text, text, text) to service_role;

-- 7. Legacy backfill (idempotent; see header item 7). Runs as the table owner: the ledger triggers are switched off for the wallet being repaired and back on.
create or replace function private.v1_ledger_backfill(p_usuario uuid default null)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  w record;
  r record;
  v_wallets uuid[];
  v_run integer;
  v_n integer;
  v_diff integer;
  v_done integer := 0;
  v_before boolean := exists (select 1 from pg_trigger where tgrelid = 'public.transacciones_cartera'::regclass and tgname = 'trg_ledger_before_insert' and not tgisinternal);
  v_immut boolean := exists (select 1 from pg_trigger where tgrelid = 'public.transacciones_cartera'::regclass and tgname = 'trg_ledger_immutable' and not tgisinternal);
begin
  -- the wallet list is materialised first: ALTER TABLE ... DISABLE TRIGGER fails while a query over the table is still open in this session
  select coalesce(array_agg(x.usuario_id order by x.usuario_id), '{}'::uuid[]) into v_wallets from (
    select c.usuario_id from public.carteras_usuario c
     where (p_usuario is null or c.usuario_id = p_usuario)
       and (exists (select 1 from public.transacciones_cartera t where t.usuario_id = c.usuario_id and t.seq is null)
            or (c.saldo_tickets <> 0 and not exists (select 1 from public.transacciones_cartera t where t.usuario_id = c.usuario_id)))
  ) x;
  for w in select c.usuario_id, c.saldo_tickets from public.carteras_usuario c where c.usuario_id = any (v_wallets) order by c.usuario_id
  loop
    perform pg_advisory_xact_lock(hashtextextended('wallet:' || w.usuario_id::text, 0));
    if exists (select 1 from public.transacciones_cartera t where t.usuario_id = w.usuario_id and t.seq is null)
       and exists (select 1 from public.transacciones_cartera t where t.usuario_id = w.usuario_id and t.seq is not null) then
      raise exception using errcode = 'P0001', message = 'V1_LEDGER_BACKFILL_MIXED', hint = 'a wallet has ledger rows with and without seq; resolve with the owner first';
    end if;
    if v_before then alter table public.transacciones_cartera disable trigger trg_ledger_before_insert; end if;
    if v_immut then alter table public.transacciones_cartera disable trigger trg_ledger_immutable; end if;
    v_run := 0; v_n := 0;
    for r in select t.id, t.cantidad from public.transacciones_cartera t where t.usuario_id = w.usuario_id order by t.creado_en, t.id loop
      v_n := v_n + 1;
      update public.transacciones_cartera t set seq = v_n, saldo_anterior = v_run, saldo_resultante = v_run + r.cantidad where t.id = r.id;
      v_run := v_run + r.cantidad;
    end loop;
    v_diff := w.saldo_tickets - v_run;
    if v_diff <> 0 then
      insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion, referencia_tipo, seq, metadatos)
      values (w.usuario_id, 'ajuste', v_diff, v_run, w.saldo_tickets, 'Saldo inicial', 'legacy_opening', v_n + 1, jsonb_build_object('op', 'legacy_opening'));
    end if;
    if v_before then alter table public.transacciones_cartera enable trigger trg_ledger_before_insert; end if;
    if v_immut then alter table public.transacciones_cartera enable trigger trg_ledger_immutable; end if;
    -- FIFO traceability of the legacy debits (best effort: a history that cannot be traced stops at the first debit it cannot allocate)
    begin
      for r in select t.id, t.cantidad, t.tipo, t.referencia_id from public.transacciones_cartera t where t.usuario_id = w.usuario_id and t.cantidad < 0 order by t.seq loop
        perform private.ledger_allocate_debit(r.id, w.usuario_id, -r.cantidad, r.tipo, r.referencia_id);
      end loop;
    exception when others then
      raise notice 'V1_LEDGER_BACKFILL: FIFO trace skipped for a wallet (%)', sqlerrm;
    end;
    v_done := v_done + 1;
  end loop;
  return v_done;
end;
$$;
revoke all on function private.v1_ledger_backfill(uuid) from public, anon, authenticated;

select private.v1_ledger_backfill();

commit;
