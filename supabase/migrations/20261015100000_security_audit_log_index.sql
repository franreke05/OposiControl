-- Audit log keyset index (2026-10-15). Forward-only; creates one index, touches no existing data.
--
-- The «Auditoría» tab of OposiControl now reads `public.auditoria_log` page by page from the backend
-- (`GET /api/v1/admin/auditoria/movimientos`): newest first, `order by creado_en desc, id desc`, with a keyset cursor
-- (`creado_en < x or (creado_en = x and id < y)`) and an optional date range on `creado_en`. Until now the table only had
-- its primary key and the partial unique index of `tickets_grant`, so every page was a full scan plus a sort of a table
-- that only grows. This index serves that exact order: a page is an index range scan of `limit + 1` rows.
--
-- `id` is in the index on purpose: it is the tie-breaker of the cursor. Nothing forbids two rows with the same
-- `creado_en`, and without the tie-breaker a page boundary falling between them would skip or repeat one.
--
-- The filters by action, actor and table are NOT indexed here. They are applied while walking this same index; whether one
-- of them deserves an index of its own depends on how the tab is used, which nobody has measured yet.
--
-- Not `concurrently`: it cannot run inside a transaction, and migrations are applied in one. Building the index takes a
-- lock that blocks writes to `auditoria_log` while it runs; `lock_timeout` makes the migration fail fast instead of
-- queueing behind a long transaction. The size of the table in production has not been measured from here: apply it in a
-- quiet moment.
--
-- `if not exists`: applying it twice is a no-op.
--
-- Rollback (manual, loses nothing but the index): drop index if exists public.auditoria_log_creado_en_idx;

begin;

set local lock_timeout = '5s';

create index if not exists auditoria_log_creado_en_idx on public.auditoria_log (creado_en desc, id desc);

commit;

-- VERIFY-BEGIN
-- select 'index_exists', (count(*) <> 1)::int from pg_index i join pg_class c on c.oid = i.indexrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'auditoria_log_creado_en_idx' and i.indrelid = 'public.auditoria_log'::regclass and i.indisvalid and i.indisready
-- union all select 'index_has_the_expected_order', (count(*) <> 1)::int from pg_indexes where schemaname = 'public' and indexname = 'auditoria_log_creado_en_idx' and indexdef ~ '\(creado_en DESC, id DESC\)$';
-- VERIFY-END
