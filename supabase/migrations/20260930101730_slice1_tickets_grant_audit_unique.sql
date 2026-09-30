-- =============================================================================
-- AUDIT LOOP 3 -- one `tickets_grant` audit row per wallet transaction (MASTER3-OBS-002)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: no existing migration is
-- edited, no function is replaced. Idempotent (create ... if not exists), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   Ktor (AdminV1Gateway.grantTickets, MASTER2-OBS-009) writes the `tickets_grant` row of public.auditoria_log
--   AFTER public.admin_grant_tickets and stores the wallet transaction id in despues->>'transactionId'. An
--   idempotent replay (same key -> same transaction id, no second credit) must not write a second row. Ktor
--   de-duplicated with a read-then-insert (GET auditoria_log ... then POST), which is not atomic: two concurrent
--   replays both passed the read and wrote two rows, and a failed read wrote one anyway.
--   This migration adds the atomic guarantee:
--     1. GATE: if auditoria_log already holds more than one `tickets_grant` row for the same transactionId the
--        migration RAISES (P0001 TICKETS_GRANT_AUDIT_DUPLICATES_GATE) and nothing is applied. Audit rows are
--        append-only evidence and are never deleted by a migration: an operator decides (see VERIFY
--        `tickets_grant_duplicate_transactions`). On MAIN the count is 0 by construction: before this loop no
--        deployed Ktor wrote transactionId into despues (MASTER2-OBS-009 is branch-only).
--     2. Partial UNIQUE index public.auditoria_log_tickets_grant_tx_uq on ((despues ->> 'transactionId'))
--        WHERE accion = 'tickets_grant' AND despues ? 'transactionId'. Only rows that carry a transaction id
--        are constrained; every other action and every historical grant row (no transactionId) is untouched.
--   Ktor (MASTER3-OBS-002) inserts the row and treats a 23505 on it as "already audited" (never as a failure).
--
-- FORWARD PLAN
--   Apply before (or together with) the audit loop 3 Ktor build. MAIN's deployed Ktor (pre loop 2) writes
--   tickets_grant rows WITHOUT transactionId: they are outside the partial index, nothing changes for it. The
--   branch-only loop 2 build would, in the rare concurrent-replay case, get 23505 instead of writing a duplicate
--   and report a failed privileged audit (5xx on a replay that credited nothing; its retry then finds the row
--   with the pre-check). MAIN only at the dedicated apply gate.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops the index only. Loop 3 Ktor stays correct without it (it falls back to the
--   non-atomic pre-check; duplicates become possible again, never a missing row). No data is touched.
--
-- COMPATIBILITY WITH OLD KTOR
--   See FORWARD PLAN. No column, policy, grant or function changes. Direct writers of auditoria_log other than
--   Ktor's tickets_grant path never set accion = 'tickets_grant' with a transactionId, so they are unaffected.
--
-- LOCKING
--   CREATE UNIQUE INDEX (not CONCURRENTLY: migrations run in a transaction) takes a SHARE lock on
--   public.auditoria_log for the build: concurrent INSERTs into the audit log wait until commit. The table is
--   append-only and small; the build reads it once. `lock_timeout = 5s` bounds the wait to acquire the lock.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   + public.auditoria_log_tickets_grant_tx_uq (unique, partial, expression).
--
-- RLS AND GRANTS
--   Unchanged (auditoria_log keeps RLS on, admin-only SELECT policy and MAIN's grants).
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop index if exists public.auditoria_log_tickets_grant_tx_uq;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'tickets_grant_tx_index_missing' as check_name,
--        (case when exists (select 1 from pg_index i
--                           where i.indexrelid = to_regclass('public.auditoria_log_tickets_grant_tx_uq')
--                             and i.indisunique and i.indisvalid and i.indpred is not null)
--              then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'tickets_grant_duplicate_transactions', count(*)::bigint, null
-- from (select a.despues ->> 'transactionId'
--       from public.auditoria_log a
--       where a.accion = 'tickets_grant' and a.despues ? 'transactionId'
--       group by 1 having count(*) > 1) d;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_duplicadas bigint;
begin
  select count(*) into v_duplicadas
  from (
    select a.despues ->> 'transactionId'
    from public.auditoria_log a
    where a.accion = 'tickets_grant' and a.despues ? 'transactionId'
    group by 1
    having count(*) > 1
  ) d;
  if v_duplicadas > 0 then
    raise exception 'TICKETS_GRANT_AUDIT_DUPLICATES_GATE'
      using detail = format('%s wallet transaction(s) already have more than one tickets_grant audit row.', v_duplicadas),
            hint = 'Audit rows are evidence and are not deleted by this migration. Review VERIFY tickets_grant_duplicate_transactions and decide before re-applying.';
  end if;
end;
$$;

create unique index if not exists auditoria_log_tickets_grant_tx_uq
  on public.auditoria_log ((despues ->> 'transactionId'))
  where accion = 'tickets_grant' and despues ? 'transactionId';

do $$
begin
  if to_regclass('public.auditoria_log_tickets_grant_tx_uq') is null then
    raise exception 'TICKETS_GRANT_AUDIT_UNIQUE_NOT_INSTALLED';
  end if;
end;
$$;

commit;
