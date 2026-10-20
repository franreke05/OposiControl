# Store payments: round-4 fixes (N4-1 to N4-6)

Date: 2026-10-20. Source: OposiControl `docs/audit-v1/HANDOFF_BOT_store_payments_round4.md` (re-audit of `6987c1b` + `ae2028a` + `e9e9b93`).
Branch `feature/v1-p3-store-payments`. New migration `20261022103100_v1_store_payments_round4.sql`. The earlier migrations (`102600`, `102800`,
`102900`, `103000`) are unchanged. Payments stay off by default: G1 `PAYMENTS_STORE_LIVE_CREDIT_ENABLED=false` and D8
`PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED=false`. Nothing was applied to MAIN.

Each regression test was run before the fix and failed. The "before" column quotes that run.

| ID | Status | Change | Test(s) | Before (fails) | After |
|---|---|---|---|---|---|
| N4-1 | FIXED | `StorePaymentsService.products(userId)`: with G1 off nothing is listed (packs and subscriptions), except to a sandbox tester (user on the allowlist, or `accept` in local/test). Subscriptions also need D8. The route passes the JWT user | `StoreRound4RegressionTest.withLiveCreditOffNoProductionProductIsListedSoNobodyPaysForSomethingUndeliverable`, `.sandboxTestersStillSeeThePacksWithLiveCreditOff`, `.withLiveCreditOnTheCatalogIsListedAsBefore` (guard: passed before and after) | `expected:<[]> but was:<[CONSUMABLE]>` (both) | green |
| N4-2 | FIXED | Kotlin: `LIVE_CREDIT_DISABLED` and `SUBSCRIPTIONS_DISABLED` deferrals are marked `retenido`, like `REFUNDS_DISABLED` (`HOLD_REASONS`). The job sends `p_retenidos` = the reasons whose switch is ON. SQL: `app_store_inbox_pending(integer, text[])` claims a retained row only for a named reason. `app_store_inbox_stats` reports G1/D8 holds as `retenido_decision`, and the gauge has that state. Rows left `pendiente` / `muerto` with those reasons are moved to `retenido` once. `StoreInboxPendingStuck` reads only `pendiente`, and `StoreInboxRefundsRetained` reads only `retenido` (refunds), so neither fires for these holds | `StoreRound4RegressionTest.aLiveCreditDeferralIsRetainedNotAFailedAttempt`, `.aSubscriptionsDisabledDeferralIsRetainedToo`, `.theJobReAsksEachRetainedReasonOnlyWhenItsSwitchIsOn`, `.decisionHoldsAreMeasuredApartAndRaiseNoAlert`; gate step 6 `N4-2 mark/retenido/stats/interruptor apagado/encendido` | `expected:<[retenido]> but was:<[pendiente]>` (both); `{"p_limit":50,"p_incluir_retenidos":false}` has no `p_retenidos`; `retenido_decision` not a gauge state; gate: see "PG17 gate" below | green |
| N4-3 | FIXED | `AppStoreProvider.notificationHistory` returns `AppleHistoryRead(events, complete)`. `complete` is false when the 100-page cap stops reading while Apple still has a `paginationToken`. In that case `pollAppleHistory` leaves the cursor where it was: it calls `app_store_poll_advance` with the old cursor, so only the poll time moves. It also logs `ALERT store apple history: page cap reached ...`. The next poll asks for the same window again, and the inbox deduplicates | `StoreServerSyncTest.aTruncatedHistoryReadDoesNotMoveTheCursorPastTheUnreadPages` | `the cursor stays where the unread pages start expected:<1792324037186> but was:<1792496837186>` (the cursor had jumped to `now`) | green |
| N4-4 | FIXED | `StoreEvent.fingerprint`: Apple sets it to `sha256(signedPayload)` in `eventFromSignedPayload`. The webhook and Notification History both use it, and Google keeps `sha256(rawBody)` | `StoreServerSyncTest.theSameNotificationHasTheSameFingerprintFromTheWebhookAndFromHistory` | red (first version of the test; its HTTP queue was then ordered correctly). The code before the fix wrote `sha256(rawBody)` from the webhook and `sha256(signedPayload)` from history | green |
| N4-5 | FIXED | Partial index `ix_tienda_bandeja_atasco (estado, recibido_en) where estado in ('pendiente','retenido','muerto')` | gate step 6 `N4-5 indice del atasco`: 300,000 processed rows plus `analyze`; one call to `app_store_inbox_stats()` must increase `pg_stat_user_indexes.idx_scan` of the new index | see "PG17 gate" | see "PG17 gate" |
| N4-6 | FIXED | `app_store_reconcile_orphan_step(p_reembolsos_habilitados, p_tras_recibido, p_tras_proveedor, p_tras_evento)` handles one orphan per call, so one transaction holds at most one store-key lock and one wallet lock. It uses a keyset cursor, so a busy (`BUSY`) or failing (`ERROR`) row never blocks the next one. The job loops it (at most 50 per run). Lock order is the same as `103000`: key try-lock, then row `FOR UPDATE SKIP LOCKED`, then wallet. The 200-row batch is not called any more and is left unchanged | `StoreRound4RegressionTest.orphanReconcileRunsOneRowPerTransactionFollowingTheCursor`, `.orphanReconcileStopsAfterFiftyRowsPerRun`; gate step 6 `D2.1/D2.2` (the D1 race against the step), `N4-6 cerrojos de un paso` (= 2 advisory locks held while the step's transaction is open), `N4-6 cursor`, `N4-6 fin`, `N4-6 ligados`, ledger = balance for u9..u12 | `the 200-row batch is not used: [app_store_reconcile_orphans]`; `expected:<50> but was:<0>` | green |

Tests that encoded the old behaviour and were updated on purpose:
- `StoreDecisionsTest.aProductionPurchaseNotificationIsStoredAndRetainedNotCredited`: it was `...LeftPending...` and asserted `pendiente`.
- `StorePaymentRoutesTest.theJobAsksForRetainedRowsOnlyWhenRefundsAreOn`: it now checks `p_retenidos` instead of `p_incluir_retenidos`.
- `StorePaymentRoutesTest.deadLetterThresholdOrphanRescanAndRetentionAreConfiguredNotImplicit`: it now uses `app_store_reconcile_orphan_step`.
- `StoreAuditRegressionTest` (A-K refunds held): it now checks `p_retenidos`.

## PG17 gate

Before: the gate (with the new step-6 checks) was run on a copy of `e9e9b93` without `20261022103100`. It ran `rc=1` at step 6:

```
race b: ERROR:  function public.app_store_reconcile_orphan_step(boolean, unknown, unknown, unknown) does not exist   (D2.1, D2.2)
race N4-6 cerrojos de un paso: '0' (esperado '2')
race N4-6 ligados: 'REVERSAL_ORPHAN_HELD,REVERSAL_ORPHAN_HELD,REVERSAL_ORPHAN_HELD' (esperado 'REVERSAL_RECORDED,...')
race N4-2 stats: 'retenido:1|10' (esperado 'retenido_decision:1|10')
race N4-2 interruptor apagado / encendido: ERROR: function public.app_store_inbox_pending(integer, text[]) does not exist
race N4-5 indice del atasco: 'f' (esperado 't')
FAIL: carreras de tienda con dos conexiones (cerrojo por clave / orden de cerrojos)
```

After: the full worktree gate, including H-04 step 7 (`cf64d78`), ran `rc=0`. There are 30 pending migrations and VERIFY = 0. `assertions.sql`
passed. Rollback == bootstrap and re-apply == first apply. Store races R1..R4, S1..S3, D1 x2 plus the new D2 x2, N4-6, N4-2 and N4-5 checks
are green. The exam races F1..F5 x2 are green. Output: `db-pg17-gate OK`.


## `:backend:ktor:test`

Before: `StoreRound4RegressionTest` + `StoreServerSyncTest` had 22 tests, and 10 failed (all the new ones except the G1-on guard).
After: the full `:backend:ktor:test` has 2223 tests, 0 failures, 0 errors and 1 skipped. `StoreRound4RegressionTest` has 9 tests and
`StoreServerSyncTest` has 13.


## Retest for Control

1. Kotlin: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.StoreRound4RegressionTest" --tests "com.oposicontrol.backend.StoreServerSyncTest"`,
   then the full `:backend:ktor:test`.
2. DB: `bash scripts/db-pg17-gate.sh`. Step 6 now also prints `D2.x`, `N4-6 ...`, `N4-2 ...` and `N4-5 ...` lines when something fails. The
   final line `ok: carreras de tienda ...` must appear. To see the gate fail without the fix, drop `20261022103100` from the tree and from
   `supabase/baseline/PENDING_MIGRATIONS.txt` and keep the new gate.
3. N4-1 by hand: with the default env (G1 off, sandbox `reject`), `GET /api/v1/app/store/products` returns `{"items":[]}`. With
   `PAYMENTS_STORE_SANDBOX=allowlist` and your user in `PAYMENTS_STORE_SANDBOX_USERS`, the packs are listed for that user only.
4. N4-2 in SQL: `app_store_inbox_mark(..., 'retenido', null, 'LIVE_CREDIT_DISABLED', <claim>, 20)` leaves `retenido|0`.
   `app_store_inbox_stats()` shows `retenido_decision`. `app_store_inbox_pending(200, array['REFUNDS_DISABLED'])` does not return the row, and
   `array['LIVE_CREDIT_DISABLED']` does.
5. N4-6 lock hold: in one session, `begin; select * from app_store_reconcile_orphan_step(false, null, null, null);`. Keep the transaction
   open and run `select count(*) from pg_locks where locktype = 'advisory' and granted` from another session: 2. The batch
   `app_store_reconcile_orphans(50, false)` with N orphans holds 2·N.
6. N4-5: `explain analyze select * from app_store_inbox_stats()` is a function scan, so check the index counter instead:
   `pg_stat_user_indexes.idx_scan` of `ix_tienda_bandeja_atasco` increases with each call.

## Not done / limits

- N4-3 does not store Apple's `paginationToken`. If one 6-hour window ever has more than 100 pages, every poll reads the same first 100 pages
  and the ALERT log repeats, so nothing is lost silently. Storing the token, its window and its end in `tienda_sondeo` would let polling go past
  that point. That was left out as more than this P3 needs.
- `scripts/db-pg17-gate.test.sh` (the mutants) and `promtool` were not run in this round. `alert_rules.yml` only gained a comment.
- Apple's real Notification History (that it returns the same `signedPayload` that was sent to the webhook) was not checked against Apple. It is
  the documented behaviour, and the test uses forged payloads.

# Round 5 (N5-1 to N5-3 and the H-04 contract bug)

Source: OposiControl `docs/audit-v1/HANDOFF_BOT_store_payments_round5.md` (re-audit of `c30292c` + `764e334`). It found N4-1/2/4/5/6 FIXED and
N4-3 PARTIAL. New migration `20261022103200_v1_store_payments_round5.sql`. G1/D8 defaults are unchanged.

| ID | Status | Change | Test(s) | Before (fails) | After |
|---|---|---|---|---|---|
| N5-1 (N4-3) | FIXED | When a read stops at 100 pages, the server **stores Apple's `paginationToken`** together with its window (`tienda_sondeo.pagina_token / ventana_inicio_ms / ventana_fin_ms`, `app_store_poll_page`). The cursor does not move. The next job run (no 6-hour wait) continues from that token in the same window. When the window is complete, `app_store_poll_advance` moves the cursor to the window end and clears the token. If Apple refuses a stored token (4xx), the token is dropped and the window is read again from the cursor. Gauge `store_inbox_history_incomplete` (1 while a token is stored), alert `StoreAppleHistoryIncomplete` (`== 1` for 6 h), ALERT log. **Why the token and not "advance to the signed date of the last notification read":** that option is only safe if History comes back in chronological order, which nobody has checked against Apple. With the token, nothing can be skipped in any order: the cursor moves only after the whole window was read | `StoreServerSyncTest.moreThanAHundredPagesAreReadAcrossPollsFromThePersistedTokenAndNothingIsSkipped` (101 pages across 2 polls), `.aRejectedStoredTokenRestartsTheWindowFromTheCursor`; gate step 6 `N5-1 pagina`, `N5-1 fin`; VERIFY `poll_advance_keeps_token`, `poll_page_check_missing` | `expected:<p101> but was:<null>`; `Expected an exception ... completed successfully with the result: <0>` | green |
| N5-2 | FIXED | `drop function app_store_inbox_pending(integer, boolean)`. Nothing called it: Ktor uses `(integer, text[])` and `assertions.sql` was moved to it (`false` -> `'{}'::text[]`, `true` -> `array['REFUNDS_DISABLED']`) | VERIFY `old_pending_overload_left`; gate `N5-2 sobrecarga` | (the overload existed) | green |
| N5-3 | FIXED | The orphan loop keeps its cursor between runs (`orphanCursor`). A run that hits the 50 cap leaves the cursor for the next run. A run that reaches the end (no row) resets it to null. It is kept in the replica's memory because it only sets the order of visits, never whether a row is processed: the database serialises by key and each replica rotates on its own | `StoreRound4RegressionTest.orphanReconcileResumesTheNextRunFromTheCursorWhenARunHitsTheCap` | `expected:<[assn:50]> but was:<[null]>` | green |
| H-04 contract | FIXED (policy-neutral) | `AttemptService` answer / finish / abandon map an `ATTEMPT_EXPIRED` outcome to 409 `ATTEMPT_EXPIRED`, not 502 `UPSTREAM_CONTRACT_ERROR`. No timer behaviour was added: no RPC emits it today | `AttemptRoutesTest.h04AnAttemptExpiredOutcomeFromTheDatabaseIsThe409OfTheContract` (it replaces `h04TheRpcsNeverEmitAttemptExpiredSoKtorTreatsItAsAnUpstreamContractError`) | 502 `upstream_contract_error` | 409 `ATTEMPT_EXPIRED` |

Evidence:
- `:backend:ktor:test`: 2225 tests, 0 failures, 0 errors, 1 skipped. Before the fix, the 4 new or rewritten tests failed with the messages above.
- PG17 gate on the worktree: `rc=0`. 31 pending migrations, VERIFY = 0, `assertions.sql` OK, rollback == bootstrap, store races (incl. D2,
  N4-x, N5-x), H-04 races F1..F5.
- `scripts/verify-ops-config.sh` rc=0: the metrics named in the alerts exist in the code. Whether promtool ran inside it was not checked.

Retest:
1. `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.StoreServerSyncTest" --tests "com.oposicontrol.backend.StoreRound4RegressionTest" --tests "com.oposicontrol.backend.AttemptRoutesTest"`, then the full suite.
2. `bash scripts/db-pg17-gate.sh`.
3. SQL by hand: `select app_store_poll_page('app_store','notification_history','tok',900,5000)` leaves `cursor_ms` unchanged and stores the token.
   `select app_store_poll_advance('app_store','notification_history',5000)` moves the cursor and clears it.
   `to_regprocedure('public.app_store_inbox_pending(integer,boolean)')` is null.

Still not verified: Apple's real behaviour (token lifetime, order of History).

# Round 6 (N6-1, N6-2)

Source: OposiControl `docs/audit-v1/HANDOFF_BOT_store_payments_round6.md` (re-audit of `999a50b` + `c17a804`). It found N5-1/2/3 and H-04 FIXED.
H-04 is only reachable in theory: the time limit is still an open product decision. New migration `20261022103300_v1_store_payments_round6.sql`.
G1/D8 defaults are unchanged.

| ID | Status | Change | Test(s) | Before (fails) | After |
|---|---|---|---|---|---|
| N6-1 | FIXED | `pollAppleHistory` sets `store_inbox_history_incomplete` from the **database** state (`pollState(...).pendingPage != null`) right after reading it, on every run. This includes the early return (6-hour wait) and runs where the API call fails. The gauge no longer reflects only this replica's last read | `StoreServerSyncTest.theHistoryGaugeFollowsTheDatabaseAcrossReplicas` (A leaves a page pending, B finishes the window, A's next run exports 0); `.aRestartedReplicaRaisesTheGaugeFromAStuckTokenInTheDatabase` (gauge 0 after restart, stuck token, 5xx: exports 1) | `A exports the database state ... expected:<0> but was:<1>`; `expected:<1> but was:<0>` | green |
| N6-2 | FIXED | The gauge goes to 1 (and the ALERT log is written) BEFORE `savePollPage`, so a token the database refuses still raises the alert. The token limit goes from 4096 to 65536 (`tienda_sondeo_pagina_check` and `app_store_poll_page`) because Apple documents no maximum | `StoreServerSyncTest.aTokenTheDatabaseRefusesStillRaisesTheGaugeAndALongTokenIsStored`; gate step 6 `N6-2 token largo` (5000 chars saved); VERIFY `poll_page_token_limit_4096`, `poll_page_check_limit_4096` | `the refused save does not silence the alert expected:<1> but was:<0>` | green |

Limit: if the database still refuses a token (now only above 65536 characters), the next run first exports the database state (0) and then 1 again
after the read. A scrape in between can restart the 6-hour `for` of the alert. The ALERT log line is written on every run.

Evidence: `:backend:ktor:test` 2228 tests, 0 failures, 1 skipped (3 new, red before). PG17 gate rc=0 (32 pending, VERIFY = 0, store and H-04 races).

Retest: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.StoreServerSyncTest"` and then the full suite; `bash scripts/db-pg17-gate.sh`.
