-- =============================================================================
-- SLICE 7 -- keyset index for the wallet ledger read (D6-02 GET /api/v1/app/wallet/movimientos)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). NEW forward-only migration: no earlier file is edited.
-- One btree index on public.transacciones_cartera; no column, constraint, policy, grant, function or row changes.
--
-- EVIDENCE (MAIN catalog capture, read-only)
--   - transacciones_cartera has only: pkey (id), idempotency_key unique, idx_transacciones_cartera_compra_id (compra_id) and
--     idx_transacciones_cartera_usuario (usuario_id). The wallet read pages one user's ledger by the keyset
--     (creado_en desc, id desc); with only (usuario_id) every page sorts all the rows of that user.
--
-- WHAT THIS MIGRATION DOES
--   1. CREATE INDEX IF NOT EXISTS idx_transacciones_cartera_usuario_creado_id
--        ON public.transacciones_cartera (usuario_id, creado_en DESC, id DESC).
--   2. POSTCHECK: the index exists, is valid and has exactly that definition; otherwise P0001
--      WALLET_LEDGER_KEYSET_INDEX_POSTCHECK and the transaction rolls back.
--   UNCHANGED: idx_transacciones_cartera_usuario (kept: other readers and the FK cascade use it), RLS, grants, data.
--
-- PREFLIGHT (fail closed, P0001 WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT, nothing created)
--   The table and its three columns exist; an index with this name, if present, is exactly the expected one (rerun = no-op).
--
-- HISTORICAL DATA
--   None read, written or deleted.
--
-- FORWARD PLAN
--   Order-independent from Ktor (the read works without the index, only slower). MAIN only at its apply gate.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops the index. Idempotent. No data is touched.
--
-- LOCKING
--   CREATE INDEX (non concurrent) takes a SHARE lock on the table until COMMIT: ledger writes wait, reads do not. The ledger
--   is tiny today, so the build is instantaneous. lock_timeout 5s.
--
-- DATA LOSS: none.   INDEXES: one added.
--
-- RLS AND GRANTS
--   None changed.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop index if exists public.idx_transacciones_cartera_usuario_creado_id;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'wallet_ledger_keyset_index_missing' as check_name,
--        (case when exists (select 1 from pg_index i
--                           join pg_class c on c.oid = i.indexrelid
--                           where c.relname = 'idx_transacciones_cartera_usuario_creado_id'
--                             and c.relnamespace = 'public'::regnamespace
--                             and i.indrelid = 'public.transacciones_cartera'::regclass
--                             and i.indisvalid and i.indisready
--                             and pg_get_indexdef(i.indexrelid) ~ '\(usuario_id, creado_en DESC, id DESC\)$')
--              then 0 else 1 end)::bigint as failures, null as info;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_rel oid := to_regclass('public.transacciones_cartera');
  v_idx oid := to_regclass('public.idx_transacciones_cartera_usuario_creado_id');
begin
  if v_rel is null then
    raise exception 'WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT' using detail = 'table public.transacciones_cartera missing';
  end if;
  if (select count(*) from pg_attribute a
      where a.attrelid = v_rel and not a.attisdropped and a.attname in ('usuario_id', 'creado_en', 'id')) <> 3 then
    raise exception 'WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT' using detail = 'columns usuario_id, creado_en, id of public.transacciones_cartera are required';
  end if;
  if v_idx is not null and not exists (
       select 1 from pg_index i
       where i.indexrelid = v_idx and i.indrelid = v_rel and i.indisvalid
         and pg_get_indexdef(i.indexrelid) ~ '\(usuario_id, creado_en DESC, id DESC\)$') then
    raise exception 'WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT'
      using detail = 'idx_transacciones_cartera_usuario_creado_id exists and differs from the expected definition';
  end if;
end;
$$;

create index if not exists idx_transacciones_cartera_usuario_creado_id
  on public.transacciones_cartera (usuario_id, creado_en desc, id desc);

do $$
begin
  if not exists (
       select 1 from pg_index i
       join pg_class c on c.oid = i.indexrelid
       where c.relname = 'idx_transacciones_cartera_usuario_creado_id' and c.relnamespace = 'public'::regnamespace
         and i.indrelid = 'public.transacciones_cartera'::regclass and i.indisvalid and i.indisready
         and pg_get_indexdef(i.indexrelid) ~ '\(usuario_id, creado_en DESC, id DESC\)$') then
    raise exception 'WALLET_LEDGER_KEYSET_INDEX_POSTCHECK' using detail = 'index missing, invalid or with another definition';
  end if;
end;
$$;

commit;
