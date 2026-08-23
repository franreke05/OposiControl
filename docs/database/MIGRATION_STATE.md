# Migration State

## Database Redesign V1 closure — 2026-08-21

Three controlled MAIN migrations were applied after read-only preflight:
`20260821110000_database_redesign_v1_012_drop_redundant_attempt_index`,
`20260821110100_database_redesign_v1_013_least_privilege_grants` and
`20260821110200_database_redesign_v1_014_revoke_view_mutation_grants`.
The first removed only the proven unused duplicate `intentos_examen` index;
the latter two removed anonymous mutation grants globally and mutation grants
without an authenticated policy, including public derived views. No table,
row, bucket, object, policy, RPC or legacy adapter was removed.

## Current operational state — 2026-08-21

The authorized MAIN project `ndiooyyqtaeysnedywer` has Product Metrics migrations `002`–`010` applied through the tracked migration mechanism. The current verified contract includes canonical `record_answer`, `record_event` and `admin_grant_tickets`; the latter is atomic, idempotent, concurrency-safe and executable only by `service_role`. The candidate V2 and `POST_OPOSIBOT_LEGACY_CLEANUP` packages remain separate documentation for their own gates and were not used for this closure.

The broader legacy/V1 migration history remains mixed and is not declared globally canonical by this document.

All sections below this current operational state are historical reconciliation or candidate-package evidence. They do not override the current Product Metrics/wallet closure above.

## Candidate V2 review — 2026-08-20

`supabase/canonical/migrations_candidate_v2/` is the corrected pre-OposiBot package. It reuses `es_admin()`/`es_moderador_o_admin()`, denies direct authenticated business writes, preserves `public.usuario` and `/v1/auth/*`, keeps `can_access_conversation(uuid)`, omits `current_auth_user_id()`, applies the guarded `permite_guiados -> permite_razonados` rename in `007_plan_entitlements.sql`, and defines the confirmed plan entitlement model there. `POST_OPOSIBOT_LEGACY_CLEANUP` is separate and contains no capability rename.

Historical disposable evidence `OPC-20260820-012` recorded that files `001` through `007` passed twice on disposable project `vsdvqhjgvttjdldlkgim`; classification is `READY_FOR_FINAL_LIVE_READ_ONLY_GATE`, not a live migration approval. Live project `ndiooyyqtaeysnedywer` received no writes.

## Executable candidate package — 2026-08-20

The first concrete package is at `supabase/canonical/migrations_candidate/` and remains outside `supabase/migrations/`. Its exact order and rollback classes are documented in `CANONICAL_MIGRATION_EXECUTION_PLAN.md`. Static checks passed; no PostgreSQL clone dry-run or live apply was performed because the local `psql`/Supabase CLI are unavailable and the requested scope forbids live mutation. Classification remains `CANDIDATE_ONLY / HUMAN_REVIEW_REQUIRED`.

## Live reconciliation — 2026-08-20

Read-only Supabase inspection verified five applied migrations. The live statements for `20260803092231_oposibots_usuario_base_table` and `20260803092306_oposibots_auth_profile_rls` were recovered exactly into `supabase/migrations/`. Normalized content comparison shows `20260730120000_oposibots_auth_profile_rls.sql` and `20260803092306_oposibots_auth_profile_rls.sql` are equal; the former is `DUPLICATE_HISTORY` and is retained to avoid rewriting historical evidence. Migration reproducibility is restored for these entries; Canonical V1 application remains blocked.

## OPC-20260819-004 evidence

The repository contains 14 SQL files across `docs/supabase` and `supabase/migrations`, with multiple table/function/policy families and backend REST/RPC references. This is inventory evidence only; it does not prove application order, live compatibility, or deployment history. The Supabase connector returned `USER_NOT_LOGGED_IN` and the local CLI is not installed, so no project ref, table catalog, effective RLS state, RPC list, Storage bucket or migration history could be read.

## Recovery sequence

1. Connect the authorized Supabase project and capture a read-only inventory.
2. Compare live tables, columns, keys, policies, functions and buckets against code and local migration files.
3. Select one canonical migration owner/source with Architecture + Database + Security approval.
4. Produce additive, reversible migrations and compatibility tests; do not rebuild or drop families blindly.
5. Re-run the backend contract matrix and request the human gate.

## CURRENT CLASSIFICATION

`SEVERE_DRIFT`

This is a governance/source-alignment classification, not a claim about a verified production database. The exact live state remains:

```text
LIVE SUPABASE SCHEMA:
READ_ONLY_VERIFIED / RECONCILIATION_PARTIAL
NO CANONICAL MIGRATION SOURCE SELECTED YET
```

## Evidence status

| Evidence area | Classification | Meaning |
|---|---|---|
| Active Kotlin/code expectations | `CODE_EXPECTED` | What routes/gateways/repositories expect |
| `docs/supabase/*.sql` and plans | `DOCUMENTED_ONLY` | Historical/documentary DDL, policies and plans |
| `supabase/migrations` | `DOCUMENTED_ONLY` | Versioned subset, not proven complete applied history |
| Supabase runtime schema | `LIVE_VERIFIED` | Read-only catalog and migration inspection completed 2026-08-20 |
| Migration history in production | `LIVE_VERIFIED` and recovered | Five applied migrations; statements are now present locally |

## Coexisting families

The repository documents and/or expects all of the following at the same time:

- legacy singular/English schema family with integer primary-key expectations;
- plural/Spanish/UUID V1 schema family used by newer routes and code;
- app-specific tables for attempts, feedback, events and user-facing flows;
- SQL documentation under `docs/supabase` that is not a complete migration chain;
- dated migration files that cover only a subset of RLS/profile/index/function hardening;
- application contracts and gateways that still reference legacy and V1 families together.

No family is declared canonical by this document.

## Drift dimensions

| Dimension | Classification | Evidence |
|---|---|---|
| Table naming drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | code and SQL use legacy singular plus plural/V1 families |
| PK type drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | int/UUID differences recorded by audit |
| Column drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | code/DDL expectations differ in names and nullability |
| Migration history drift | `LIVE_VERIFIED` | duplicate historical filename remains; live statements are locally recoverable |
| SQL documentation drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | DDL/policies distributed across docs rather than one chain |
| Legacy/V1 coexistence | `VERIFIED_FROM_SOURCE` | routes, repositories and screen mappings use both |
| Application contract drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | endpoint families and data repositories span generations |
| Policies/RLS | `LIVE_VERIFIED` counts; intent pending | 86 tables, 66 with policies and 20 without policies |
| RPC/functions/triggers | `LIVE_VERIFIED` grants; body review pending | deployed `SECURITY DEFINER` grants were inspected read-only |

## Guardrails

No SQL, migration, RPC, RLS, storage or Supabase write was performed. HL-21 remains active: future durable DB changes require a canonical migration decision, review, ledger, tests and human gate.
