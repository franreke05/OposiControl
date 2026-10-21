# QA stack incident 2026-10-19 (local `oposi-exam-local` data loss)

Scope: LOCAL test data only (fixtures, journey/QA rows). MAIN / production were not touched. Author: E5 (QA), 2026-10-20.

## Facts

| Item | Fact | Source |
|---|---|---|
| Storage | Service `db` in `ops/local-exam/docker-compose.yaml` has no named volume and no bind mount; its data lives only in the container. Recreating the container loses the database. | compose file (this repo) |
| First loss | Before 09:30 the `db` container was recreated with new host ports (54321/54329 fell inside Windows reserved ranges; new ports 55432/55321 via `ports.mjs`). The previous QA database was lost; the new one was half-built (normal start stopped at `WALLET_INCONSISTENT`). | `docs/ledger/2026/10/SYNC-REPORT-2026-10-19.md` (OposiBot) |
| Backup | 09:31: dump of the half-built database before any reset: `OposiControl-PhaseB-Server/ops/local-exam/.local/backups/db-prereset-20261019093118.sql` (1,134,150 bytes; `.err` file empty). | file on disk; `COORD-REPORT-2026-10-19.md` section 2 |
| Reset | 09:33: the owner ran `up.mjs --reset` by hand, following the coordinator's proposal (the coordinator's auto-mode could not run it). `--reset` rebuilds the database from scratch. | coordinator report + owner |
| Restore | Restored from the 09:31 backup: 1,668 of 1,673 rows. | coordinator (restore run) |
| Unreconciled | 5 rows of `auth.users`, all harness fixture users (not real people): `a@exam.local` (0a..0a), `b@exam.local` (0b..0b), `xa@exam.local` (1a..1a), `xb@exam.local` (1b..1b), `p@exam.local` (0f..0f). In the dump all 5 have `confirmed_at` and `email_confirmed_at` null. | dump lines 12938-12944 (checked by E5) |

## Why the 5 rows were rejected

The dump's `COPY auth.users (...)` lists 22 columns, ending in `email_confirmed_at`, which the harness had added (`supabase/baseline/main_shape_auth_users.sql`).
The pinned base image (`supabase/postgres` 17.6.1.171) ships a minimal pre-2021 `auth.users` (21 columns, no `email_confirmed_at`).
So a restore into a fresh base, before the harness shape file runs, fails for this table only.

## Reconciliation status

- The 5 users are re-created by the fixtures that `up.mjs` loads: `sql/90_fixtures.sql` (A, B, P), `sql/92_phase_b_wallet_entitlement.sql`, `sql/94_e2e_users.sql` (XA, XB).
  Their rows have the same ids and null confirmation.
  No data from the dump is needed for them, but nobody has checked the live stack row by row: **NOT VERIFIED**.
- Rows in other tables that point at these ids were restored. They hold again once the fixtures re-create the users.

## Related harness defect (fixed on `feature/v1-p3-qa-harness-fix`)

In the same base image `auth.users.confirmed_at` is a plain column. In GoTrue (MAIN) it is `GENERATED ALWAYS AS (LEAST(email_confirmed_at, phone_confirmed_at)) STORED`.
`main_shape_auth_users.sql` now reproduces the GoTrue shape:
- adds `phone_confirmed_at`;
- copies any value found only in the old plain column into `email_confirmed_at`, with triggers off;
- turns `confirmed_at` into the generated column.

That defect caused J17's 403 `EMAIL_UNVERIFIED` failures in the live run of 2026-10-19 (`E5-LIVE-RUN-2026-10-19.md`).

## Prevention (open)

- `db-guard.mjs` already stops `up.mjs` (exit 4) when the `db` container would be recreated without mounts. `--allow-db-recreate` saves a `docker commit` and a `pg_dumpall` first.
  `--reset` bypasses the guard on purpose.
- Still open, owner decision: give `db` a named volume, or make `--reset` always take a dump first.
- Restore procedure for a dump taken from a harness-shaped database (this one included):
  1. As `supabase_admin`, run only `alter table auth.users add column if not exists email_confirmed_at timestamptz;`.
     Do not run the whole shape file yet: the dump's `COPY` writes `confirmed_at`, and a generated column rejects that.
  2. Load the dump.
  3. Run `supabase/baseline/main_shape_auth_users.sql`, which moves the confirmation into `email_confirmed_at` and turns `confirmed_at` into the generated column.
