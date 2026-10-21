# Candidate v1 bot Ktor: manifest

Branch `candidate/v1-bot-ktor` (local only, NOT pushed), worktree `C:\Users\franr\AndroidStudioProjects\OposiControl-Candidate`.
Built 2026-10-21 by COORD with the owner's GO: create the branch, merge the listed branches, and run Ktor, dbtest, the PG17 gate and e2e.
Nothing was pushed. No existing branch was changed.

## SHAs

| What | SHA |
|---|---|
| Base `develop` | `8fecd02` |
| Code SHA tested (Ktor/dbtest ran on `b1b282a`; `baf4264` changes only `ops/local-exam/up.mjs`; the PG17 gate ran on `b1b282a`, and e2e and journeys ran on `baf4264`) | `baf4264ba3bdada5dcaa251a1a1c3735f2b532ad` |
| HEAD | the commit that adds this file (docs only, on top of `baf4264`) |

Commits on top of `develop`, oldest first:

| Commit | What |
|---|---|
| `ded442f` | merge feature/v1-p3-store-payments `be9e0bd` |
| `25aa997` | merge feature/v1-p3-user-tables-revoke `11bae54` |
| `e5e7ef1` | merge feature/v1-p3-qa-harness-fix `2e1dda6` |
| `ee08cd4` | merge feature/v1-p3-idor-sweep `b86dd42` (no conflicts) |
| `ab1870b` | merge feature/v1-p2-deploy-env-templates `aff478d` |
| `b1b282a` | docs(deploy): cite V1 migrations 102500, 103200 and 103300 in DEPLOYMENT.md. The source branches did not cite them, so `verify-migrations-manifest.sh` failed. |
| `baf4264` | fix(local-exam): `up.mjs` applies develop's `20261020100000` on the QA stack (see e2e) |

## Conflict log

### Merge 1: store-payments `be9e0bd`

- `Application.kt`: kept both sides. develop's SEC-V1-02 `adminMutationLimiter` stays, and so does V1's `DailyChallengeJob` with the foro image sweep. Route registration auto-merged.
- `OposiBotsAuthGateway.exchangeEmail`: kept both sides.
  - develop: `toSession(context, AccessRequestSessionGrant.EMAIL_LINK)`, which runs the account gate (H1/RB-16).
  - V1: FA39 binding check (`matchesBinding`, otherwise revoke the minted session and return AUTH_CODE_INVALID).
  - Resolution: decode the session, run the binding check BEFORE the account gate, then call `toSession(EMAIL_LINK, decoded)`. A mismatched binding never reaches the gate.
- `OposiBotsAuthRoutes.kt`: develop moved email validation to `AuthCredentialRules` (canonical form, rejects control characters). V1's `isValidEmail` was removed. The FA39 email hint now uses `authEmailIssue(email) != null`, the same rule as integration commit `4bda9e0`.
- `freeze-manifest.sha256`:
  - The header records develop's FA17 (password unit) and V1's FA18..FA39. The numbering did not collide: V1 skipped FA17.
  - All hashes were recomputed from the merged `openapi/`. `auth.yaml` and `profiles.yaml` changed on both sides.
- `scripts/db-pg17-gate.sh`: V1's design (PENDING = repo minus MAIN_MIGRATION_HISTORY minus HISTORY_DIVERGENT) combined with develop's DEBT-156b.
  - Non-V1 `202610*_security_*` migrations stay out of PENDING. Step 1b checks that each has VERIFY, ROLLBACK and a dbtest class.
  - develop's rule "a 20261022* migration may not carry _security_" would have FAILED on V1's `20261022100100_security_exam_publication_time`. It is replaced by "a V1 _security_ migration stays PENDING and must be listed". That keeps the purpose of DEBT-156b: the filter never hides a V1 migration. **Rewording of DEBT-156b step 1b approved by the owner on 2026-10-21.**
- `scripts/db-pg17-gate.test.sh`: V1 mutations plus develop's four DEBT-156b negative controls. The V1-probe expectation now matches the new message.
- `scripts/verify-migrations-manifest.test.sh`: took the V1 side. It already strips CR and includes `V1_MIGRATIONS.txt`.
- `supabase/baseline/assertions.sql`: both sides independently fixed "an annulled question is frozen" (RB-15).
  - Kept V1's revision-bound freeze assertion.
  - Kept develop's "annulled answer is never stored" checks.
  - Kept V1's "archived question is not in the set" check.
- `docs/registry/TECH_DEBT.md`: develop rows DEBT-116..156 are kept. The V1 DEBT-116 row (exam engine) collides by id and is renamed `DEBT-116-EXAM` here.
- `ops/inventory/undocumented-routes.tsv`: union of the rows. `# max: 175` (base 168, develop +2, store +5).

### Merge 2: user-tables-revoke `11bae54`

- `V1_MIGRATIONS.txt` and `PENDING_MIGRATIONS.txt`: union, sorted. 102500 and 102700 slot between the store migrations. No duplicate timestamps.
- `assertions.sql`: the store/refund sections close their transaction. The SEC-V1-08 binding and P3 user-tables sections follow in their own `begin`/`rollback`, with the shared `expect_e` helper. The `pg_temp` helpers (`u`, …) are never shared between transactions.
- `backend.bot.env.example`: PKCE/nonce notes plus the store payments block.
- `DEPLOYMENT.md`: both lines kept.

### Merge 3: qa-harness-fix `2e1dda6`

- `ops/local-exam/disposable-db.mjs`: took the harness-fix `bootstrap()`. It already loads `main_shape_subset_extra_import.sql`, the only change `1ec2b37` made there.

### Merge 4: idor-sweep `b86dd42`

- Clean merge.

### Merge 5: deploy-env-templates `aff478d`

- `backend.bot.env.example`:
  - `PAYMENTS_REFUNDS_ENABLED` appears once; there are no duplicate keys.
  - `CHALLENGE_JOB_SECONDS` was added.
  - The stale note "AUTH_PKCE_MODE today optional" was dropped in favour of SEC-V1-04: required outside local/test, and startup fails otherwise.
- `PRODUCTION_RUNBOOK.md` auto-merged. Semantic fix: `AUTH_PKCE_MODE=required`, not `optional`. With optional, production would not start (`OposiBotsAuthConfiguration` RB-04).

No conflict needed a product decision, apart from the DEBT-156b wording flagged above.

## SHA ancestry (all ancestors of HEAD)

aa5e3ab 1ec2b37 13bbdf6 11bae54 2cab55b 0a13f3d ae62375 1a203b9 0c9f078 72627c8 8859ee4 94a867c 9e4ecd0 3e630db 1cabc65
ad88bc0 6987c1b ae2028a e9e9b93 999a50b c17a804 be9e0bd 96d9d1f 88d0baf eaa546b b86dd42 c180fb8 2e1dda6 aff478d: **29/29 Y, none missing or unknown.**

## Migrations

- `git ls-files 'supabase/migrations/*.sql'` returns 101 files, with no duplicate timestamp.
- 41 of them are absent from `origin/main` (`cfa2e14`).
- 43 are not in the captured MAIN history (`supabase/baseline/MAIN_MIGRATION_HISTORY.txt`, 2026-10-14):

**Gate PENDING: 34** (`supabase/baseline/PENDING_MIGRATIONS.txt` equals the gate's computation):

```
20261022100000_slice3_attempt_content_revisions
20261022100100_security_exam_publication_time      (V1 batch, _security_ name, stays PENDING)
20261022100200_v1_exam_community_required
20261022100300_v1_wallet_ledger_hardening
20261022100400_v1_payment_orders
20261022100500_v1_exam_redeem
20261022100600_v1_specialty_immutable
20261022100700_v1_admin_grant
20261022100800_v1_saved_items
20261022100900_v1_plan_access_v2
20261022101000_v1_demo_mode
20261022101100_v1_attempt_limiter
20261022101150_v1_plan_catalog
20261022101200_v1_daily_challenge
20261022101300_v1_official_rules
20261022101500_v1_config_bounds
20261022101600_v1_stats
20261022101700_v1_ranking
20261022101800_v1_referral
20261022101900_v1_foro
20261022102000_v1_import_reserves
20261022102100_v1_razonado_markup
20261022102200_v1_auth_code_binding
20261022102300_v1_control_rpcs
20261022102400_v1_refund_order_fix
20261022102500_v1_auth_binding_hardening            (from user-tables-revoke)
20261022102600_v1_store_payments
20261022102700_v1_user_tables_client_write_revoke   (from user-tables-revoke)
20261022102800_v1_store_payments_fixes
20261022102900_v1_store_payments_concurrency
20261022103000_v1_store_payments_round3
20261022103100_v1_store_payments_round4
20261022103200_v1_store_payments_round5
20261022103300_v1_store_payments_round6
```

**Handled outside PENDING (DEBT-156b, step 1b: VERIFY/ROLLBACK/dbtest), 7:**

```
20261020100000_security_access_request_email_verified   (LEGACY: no ROLLBACK)
20261021100000_security_audit_log_append_only           (LEGACY: no ROLLBACK)
20261023100000_security_content_admin_write_revoke
20261023100100_security_storage_admin_write_revoke
20261023100200_security_revenue_excludes_sandbox
20261023100300_security_moderator_requires_active_account
20261023100400_security_noticia_trigger_search_path
```

**Known history divergences (HISTORY_DIVERGENT, never pending), 2:**
- `20260730120000_oposibots_auth_profile_rls`
- `20260923103000_news_canvas_schema`

The "35 new vs MAIN" expectation does not match: 34 PENDING plus 7 develop security migrations equals 41, the same number as the files absent from origin/main. The difference comes from:
- 102500 and 102700, which come from user-tables-revoke;
- 20261023100300 and 20261023100400, which are on develop;
- 20261020 and 20261021, which are not in the 2026-10-14 MAIN capture. DEBT-156b says they are applied to MAIN by hand; this is not verified here because MAIN was not read.

## Migration ↔ code dependencies

- **`20261022100700_v1_admin_grant` must never ship without the `admin_grant_exam_access` Ktor code.**
  - The migration adds the trigger `GIFT_UNLIMITED_FORBIDDEN` (23514 on an insert into `contenido_desbloqueado_usuario` without `valido_hasta`).
  - Checked on the candidate:
    - `AdminV1Gateway.upsertExamAccessResult` grants through `POST /rest/v1/rpc/admin_grant_exam_access` (AdminV1Routes.kt:7432).
    - Revoke goes through `rpc/admin_revoke_exam_access` (:7465).
    - grep finds no write (POST/PATCH) to `/rest/v1/contenido_desbloqueado_usuario` in `backend/ktor/src/main`; only GETs remain.
  - Tests:
    - `AdminV1ExamAccessGrantTest`: 16/16.
    - Live J15 (`admin-grant-journey.mjs`): 20/20 against the control-profile Ktor.
  - Control merges its panel on top of the candidate. It must keep this path and must not reintroduce develop's direct insert.
- **`20261020100000_security_access_request_email_verified` must be applied wherever develop's Ktor runs.** The register mail guard reads `correo_verificado_en` and `intentos_verificacion`, and fails closed (503) without them. The QA stack now gets the migration from `up.mjs` (`baf4264`).
- `20261022102200` and `20261022102500` (FA39 binding, SEC-V1-08) go with the PKCE code (`aa5e3ab`). `AUTH_PKCE_MODE=required` is mandatory outside local/test.

## Control-sensitive files: diff vs develop `8fecd02`

| File | Touched | Summary |
|---|---|---|
| `backend/ktor/.../Application.kt` | yes, +70/-3 | Additions: `enforceSandboxProviderGuard()` and `enforceStorePaymentsConfig()` before binding the port; the DailyChallengeJob (not in the CONTROL profile); the StorePaymentsRuntime and its job. Catalog, Wallet and Attempt runtimes now get the rate limiter and auth. New runtimes: economy, store, saved, plan quota, challenge, stats, ranking, referral, foro. `appV1Routes(idempotency, limiter)`. Route registrations: `economyRoutes`, `storePaymentRoutes`, `savedItemsRoutes`, `planQuotaRoutes`, `challengeRoutes`, `statsRoutes`, `rankingRoutes`, `referralRoutes`, `foroRoutes`. develop's `adminMutationLimiter` is unchanged. |
| `backend/ktor/.../AppV1Routes.kt` | yes, +47/-12 | `appV1Routes` is now internal, with optional `idempotency` and `limiter`. `POST /feedback/pregunta`: optional `Idempotency-Key`, per-user rate limits (E2-V1-R01), `intentoId` required (E2-V1-R02), and QUESTION_NOT_IN_ATTEMPT instead of a generic 5xx. Exam reads use `publishedByNow()` instead of `publicado_en not null` (E2-P03). |
| `backend/ktor/.../AdminV1Routes.kt` | yes, +289/-120 | `POST /estadisticas/recalcular` (D3-23). Exam create/patch require an active community (FA21). Import: `bloque`, `razonamiento_markup` / `razonamiento_rico` (FA38), lot fingerprint (`admin_import_lote_huella`), reserves (FA37). Audio ingest envelope metadata. Admin grant/revoke of exam access moved from direct table writes to the RPCs `admin_grant_exam_access` / `admin_revoke_exam_access`, with audit inside the RPC. `recalcularEstadisticas`, `requireActiveCommunity`. |
| `backend/ktor/.../AdminAuthorizationGuard.kt` | **no** | unchanged |
| `AdminControlOfficialQuestions.kt` | **does not exist** in the candidate tree | n/a |

## Test results

| Gate | Command | Result |
|---|---|---|
| Ktor | `./gradlew.bat :backend:ktor:test :backend:dbtest:test --console=plain --continue` (mutex `OposiBotGradleMutex`, `LOCALAPPDATA=OposiBotEnv-candidate`) | **2358 tests, 0 failures, 0 errors, 1 skipped** (`LocalExamStackIngestFixture`, assumeTrue on the local stack), 213 XML reports. BUILD SUCCESSFUL in 16m15s. |
| dbtest | same command | **309 tests, 0 failures, 0 errors, 0 skipped**, 43 XML reports |
| PG17 gate | `bash scripts/db-pg17-gate.sh` (own disposable container `db-pg17-gate-<pid>-<rand>`, no published port, removed on exit; it does not touch `oposi-exam-local`) | **rc=0**: 34 pending, VERIFY = 0, assertions, rollback == bootstrap, store races R1..R4/S1..S3/D1, attempt races F1..F5 |
| PG17 gate negative controls | `bash scripts/db-pg17-gate.test.sh` | **rc=1 at scenario (f)**. See open problem 2. The manifest/DEBT-156b controls, the unmutated copy and (a), (a2), (b), (c), (d), (e) pass. |
| Migration manifests | `bash scripts/verify-migrations-manifest.sh` / `.test.sh` | ok (V1 32) / OK (41 scenarios) |
| Ops config | `bash scripts/verify-ops-config.sh` and `verify-ops-config.test.sh` on a clean `git archive HEAD` export | rc=0 (47 ok); test OK (90 mutations). In the worktree it reports "secretos en claro bajo ops/" only because of the gitignored `ops/local-exam/.local/` (QA stack keys), which is not tracked. |
| e2e | `STACK_HOLDER=COORD APP_ENV=local node ops/local-exam/e2e.mjs` (fresh Ktor) | **166 PASS, 1 FAIL, 0 NOT_RUN** (167 checks). The first run, before `baf4264`, was 157/10: all 9 REGISTER_J6 checks failed with 503 (the 20261020 dependency). |
| J15 admin grant | `admin-grant-journey.mjs` | 20/20 on a fresh Ktor. Run right after e2e on the same Ktor it was 15/5 (429 from the in-memory limiter used up by e2e). |
| J16 access v2 | `access-v2-journey.mjs` | 17/17 |
| J17 challenge | `challenge-journey.mjs` | **25/25** |
| J18 official rules | `official-rules-journey.mjs` | 24/24 |
| J19 stats | `stats-journey.mjs` | 15/15 |
| J20 ranking | `ranking-journey.mjs` | 16/16 |
| J21 referral | `referral-journey.mjs` | 13/13 |
| J22 foro | `foro-journey.mjs` | 28/28 |

### QA stack handling

- STACK_LOCK was already held by `COORD` (since 2026-10-19). It was rewritten as COORD for this run and deleted (released) at the end.
- `oposi-exam-local-db-1` and `oposi-exam-local-gateway-1` had exited 13 hours earlier (code 255).
- `.local/secrets.json` and `ports.json` were copied (read only) from the stack owner worktree `OposiControl-PhaseB-Server`, as the harness README requires. Compose config hashes were checked BEFORE `up.mjs`:
  - db, rest and storage were unchanged, so `up.mjs` only **started** the db (plain start, data kept).
  - The gateway (stateless nginx) was recreated because its mounts now point to the candidate's `.local/` (jwks and issuer generated from the same keys).
- `up.mjs` ran plain, with no `--reset`.
- `--restore-fixtures` ran before e2e and again at the end.
- Ktor bot (8089) and control (8090) were started from the candidate's installDist and stopped afterwards. The GoTrue stub was stopped by e2e.
- The stack is left with db, rest, storage and gateway running. The db and gateway were down before this run because of the crash.
- The QA DB now holds the candidate's V1 stamps (102500, 102700, 103x) and `20261020100000`.

## What was NOT run, and why

- No push and no merge into develop or main. No production or MAIN access.
- The develop security migrations `20261021`, `20261023100000..100400` were not applied to the QA stack:
  - they are not needed by Ktor;
  - append-only audit triggers could break other teams' harness cleanup;
  - DEBT-156b covers them in dbtest.
- `real-auth-gate.mjs` (real GoTrue) and `e2-*` attack scripts were not run; they are outside the requested scope.
- The Control panel (feature/december-v1-control-panel) was not merged; Control integrates it separately.

## Open problems

1. `WALLET.local_schema_matches_main` fails. The store payments migrations (20261022102600+) intentionally add `entorno`, `tienda_producto_id` and `tienda_finalizada_en` to `compras_usuario`; `suscripciones` also gets `version_evento`. They also add CHECK constraints and a unique index, and narrow the `service_role` grants. The e2e therefore differs from the 2026-10-14 MAIN capture (`main-wallet-shape.txt`). This is expected drift for pending migrations. The test was NOT changed: the owner of the harness decides whether to keep a pending-delta expectation.
2. `db-pg17-gate.test.sh` (f) "el congelado no liga la revision" is now ineffective (TEST FAIL, the mutation passes the gate). It mutates the freeze insert of 20261022100000, but `20261022101000_v1_demo_mode` and `20261022101300_v1_official_rules` later replace the freeze function. **This is not caused by the merge:** the same test on a clean `git archive` of feature/v1-p3-store-payments `be9e0bd` also stops at `TEST FAIL: (f) el congelado no liga la revision paso` (rc=1). The fix would be to mutate the last definition (101300); this is not done here.
3. ~~The DEBT-156b step 1b rewording needs confirmation.~~ Approved by the owner on 2026-10-21.
4. The QA stack gateway now mounts files from this worktree's `ops/local-exam/.local/`. Do not delete this worktree without first running `up.mjs` from the stack owner's worktree.
5. Two harness facts to know before the next run:
   - J15 must run on a fresh Ktor: the in-memory limits of e2e and J15 add up.
   - J21 moves the Q1/Q4 balances (`--restore-fixtures` afterwards).
