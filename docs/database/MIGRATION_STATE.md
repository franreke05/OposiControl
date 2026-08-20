# Migration State

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
NOT_VERIFIED
NO CANONICAL MIGRATION SOURCE SELECTED YET
```

## Evidence status

| Evidence area | Classification | Meaning |
|---|---|---|
| Active Kotlin/code expectations | `CODE_EXPECTED` | What routes/gateways/repositories expect |
| `docs/supabase/*.sql` and plans | `DOCUMENTED_ONLY` | Historical/documentary DDL, policies and plans |
| `supabase/migrations` | `DOCUMENTED_ONLY` | Versioned subset, not proven complete applied history |
| Supabase runtime schema | `LIVE_NOT_VERIFIED` | No read-only runtime verification in this task |
| Migration history in production | `LIVE_NOT_VERIFIED` | Historical plan mentions an empty history at an earlier point; not a current query |

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
| Migration history drift | `DOCUMENTED_ONLY`; current live `LIVE_NOT_VERIFIED` | historical plan plus partial migration directory |
| SQL documentation drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | DDL/policies distributed across docs rather than one chain |
| Legacy/V1 coexistence | `VERIFIED_FROM_SOURCE` | routes, repositories and screen mappings use both |
| Application contract drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | endpoint families and data repositories span generations |
| Policies/RLS | `DOCUMENTED_ONLY`; effective state `LIVE_NOT_VERIFIED` | SQL definitions exist, deployment not verified |
| RPC/functions/triggers | `DOCUMENTED_ONLY`; applied state `LIVE_NOT_VERIFIED` | definitions and migrations cover different subsets |

## Guardrails

No SQL, migration, RPC, RLS, storage or Supabase write was performed. HL-21 remains active: future durable DB changes require a canonical migration decision, review, ledger, tests and human gate.
