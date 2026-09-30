-- =============================================================================
-- AUDIT LOOP 3 -- one `tickets_grant` audit row per wallet transaction (MASTER3-OBS-002)
-- AUDIT LOOP 4 -- amended IN PLACE (MASTER4-DB-001, MASTER4-DB-003)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: no existing migration is
-- edited, no function is replaced. Idempotent (create ... if not exists + exact-definition post-check), one
-- transaction.
--
-- IN-PLACE AMENDMENT (audit loop 4)
--   This file was created in audit loop 3 and has NEVER been applied anywhere except ephemeral local validation
--   (embedded PG dbtest and the throwaway local Supabase stack), so it is amended in place instead of adding a new
--   migration. A database that ran the loop 3 text would hold the index with the old predicate
--   (`despues ? 'transactionId'`) and this text would stop on TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION: drop
--   the index (ROLLBACK block) and re-apply. Changes:
--     - MASTER4-DB-003: the gate, the index predicate and VERIFY use one rule, `(despues ->> 'transactionId') is
--       not null`. Before, the gate/VERIFY counted rows whose transactionId is JSON null (or whose despues is a
--       JSON array/string containing "transactionId") as one duplicate group, although the index treats their
--       NULL keys as distinct: the gate failed closed on data the index accepts and VERIFY reported a false
--       nonzero. Uniqueness is unchanged (NULL keys never collided); the index now also serves Ktor's pre-check
--       (`despues->>transactionId = <tx>` implies the predicate).
--     - MASTER4-DB-001: PREFLIGHT asserts public.auditoria_log exists with `accion text` and `despues jsonb`;
--       the post-check asserts the EXACT definition (pg_get_indexdef) plus indisunique/indisvalid/indisready,
--       not just that some relation with the name exists. A pre-existing same-name index that is non-unique,
--       invalid or has other columns/predicate makes the migration fail (P0001), never "succeed" without the
--       guarantee. The drifted index is not dropped by the migration: an operator decides.
--
-- WHAT THIS MIGRATION DOES
--   Ktor (AdminV1Gateway.grantTickets, MASTER2-OBS-009) writes the `tickets_grant` row of public.auditoria_log
--   AFTER public.admin_grant_tickets and stores the wallet transaction id in despues->>'transactionId'. An
--   idempotent replay (same key -> same transaction id, no second credit) must not write a second row. Ktor
--   de-duplicated with a read-then-insert (GET auditoria_log ... then POST), which is not atomic: two concurrent
--   replays both passed the read and wrote two rows, and a failed read wrote one anyway.
--   This migration adds the atomic guarantee:
--     0. PREFLIGHT: public.auditoria_log (table) with columns accion text and despues jsonb, else P0001
--        TICKETS_GRANT_AUDIT_PREFLIGHT and nothing is applied.
--     1. GATE: if auditoria_log already holds more than one `tickets_grant` row for the same non-null
--        transactionId the migration RAISES (P0001 TICKETS_GRANT_AUDIT_DUPLICATES_GATE) and nothing is applied.
--        Audit rows are append-only evidence and are never deleted by a migration: an operator decides (see
--        VERIFY `tickets_grant_duplicate_transactions`). On MAIN the count is 0 by construction: before this loop
--        no deployed Ktor wrote transactionId into despues (MASTER2-OBS-009 is branch-only).
--     2. Partial UNIQUE index public.auditoria_log_tickets_grant_tx_uq on ((despues ->> 'transactionId'))
--        WHERE accion = 'tickets_grant' AND (despues ->> 'transactionId') IS NOT NULL. Only rows that carry a
--        transaction id are constrained; every other action and every historical grant row (no transactionId,
--        or a JSON-null one) is untouched. Gate, index predicate and VERIFY use the same row set.
--     3. POST-CHECK: the index named auditoria_log_tickets_grant_tx_uq must exist (else P0001
--        TICKETS_GRANT_AUDIT_UNIQUE_NOT_INSTALLED) with exactly the definition of step 2 and be unique, valid and
--        ready (else P0001 TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION, DETAIL carries the found definition).
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
--   PREFLIGHT and POST-CHECK only read the catalog.
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
-- VERIFY (read only). `tickets_grant_tx_index_missing` = 1 when the index is absent OR differs from the exact
-- definition (same rule as the POST-CHECK); `tickets_grant_duplicate_transactions` uses the gate's row set.
-- VERIFY-BEGIN
-- select 'tickets_grant_tx_index_missing' as check_name,
--        (case when exists (select 1 from pg_index i
--                           where i.indexrelid = to_regclass('public.auditoria_log_tickets_grant_tx_uq')
--                             and i.indrelid = to_regclass('public.auditoria_log')
--                             and i.indisunique and i.indisvalid and i.indisready
--                             and pg_get_indexdef(i.indexrelid) =
--                                 'CREATE UNIQUE INDEX auditoria_log_tickets_grant_tx_uq ON public.auditoria_log USING btree (((despues ->> ''transactionId''::text))) WHERE ((accion = ''tickets_grant''::text) AND ((despues ->> ''transactionId''::text) IS NOT NULL))')
--              then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'tickets_grant_duplicate_transactions', count(*)::bigint, null
-- from (select a.despues ->> 'transactionId'
--       from public.auditoria_log a
--       where a.accion = 'tickets_grant' and (a.despues ->> 'transactionId') is not null
--       group by 1 having count(*) > 1) d;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (MASTER4-DB-001): the table and the two columns the index is built on.
do $$
declare
  v_tabla regclass := to_regclass('public.auditoria_log');
begin
  if v_tabla is null or (select c.relkind from pg_class c where c.oid = v_tabla) not in ('r', 'p') then
    raise exception 'TICKETS_GRANT_AUDIT_PREFLIGHT'
      using detail = 'public.auditoria_log does not exist or is not a table.';
  end if;
  if not exists (select 1 from pg_attribute a
                 where a.attrelid = v_tabla and a.attname = 'despues' and not a.attisdropped
                   and a.atttypid = 'jsonb'::regtype)
     or not exists (select 1 from pg_attribute a
                    where a.attrelid = v_tabla and a.attname = 'accion' and not a.attisdropped
                      and a.atttypid = 'text'::regtype) then
    raise exception 'TICKETS_GRANT_AUDIT_PREFLIGHT'
      using detail = 'public.auditoria_log must have columns accion text and despues jsonb.';
  end if;
end;
$$;

-- GATE (MASTER4-DB-003: same row set as the index predicate; JSON-null transactionIds are not duplicates).
do $$
declare
  v_duplicadas bigint;
begin
  select count(*) into v_duplicadas
  from (
    select a.despues ->> 'transactionId'
    from public.auditoria_log a
    where a.accion = 'tickets_grant' and (a.despues ->> 'transactionId') is not null
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
  where accion = 'tickets_grant' and (despues ->> 'transactionId') is not null;

-- POST-CHECK (MASTER4-DB-001): `if not exists` skips a pre-existing same-name relation, so assert the exact
-- definition instead of the name.
do $$
declare
  v_indice regclass := to_regclass('public.auditoria_log_tickets_grant_tx_uq');
  v_def text;
  v_ok boolean;
begin
  if v_indice is null then
    raise exception 'TICKETS_GRANT_AUDIT_UNIQUE_NOT_INSTALLED';
  end if;
  select pg_get_indexdef(i.indexrelid),
         i.indrelid = to_regclass('public.auditoria_log') and i.indisunique and i.indisvalid and i.indisready
    into v_def, v_ok
  from pg_index i
  where i.indexrelid = v_indice;
  if v_def is distinct from
       'CREATE UNIQUE INDEX auditoria_log_tickets_grant_tx_uq ON public.auditoria_log USING btree (((despues ->> ''transactionId''::text))) WHERE ((accion = ''tickets_grant''::text) AND ((despues ->> ''transactionId''::text) IS NOT NULL))'
     or v_ok is not true then
    raise exception 'TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION'
      using detail = format('public.auditoria_log_tickets_grant_tx_uq is %s (valid/ready/unique on auditoria_log: %s).',
                            coalesce(v_def, 'not an index'), coalesce(v_ok::text, 'n/a')),
            hint = 'A same-name relation with another definition already exists. The migration does not drop it: review, drop or rename it, then re-apply.';
  end if;
end;
$$;

commit;
