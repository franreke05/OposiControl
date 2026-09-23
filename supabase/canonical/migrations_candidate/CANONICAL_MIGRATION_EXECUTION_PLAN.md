# OPOSICONTROL — CANONICAL V1 MIGRATION EXECUTION PLAN

Status: `CANDIDATE_ONLY / READY_FOR_HUMAN_REVIEW / NO LIVE APPLY`

## Order and gates

| Order | File | Scope | Rollback classification |
|---:|---|---|---|
| 1 | `001_preflight.sql` | Read-only assertions: UUID tables, `public.usuario = 0`, no `guiado/guiada`, legacy boundary, protected tables | Safe abort; no data/schema mutation |
| 2 | `002_canonical_vocabulary.sql` | Canonical exam/question constraints and `permite_guiados -> permite_razonados` | Transaction rollback; review dependent callers before execution |
| 3 | `003_identity_and_roles.sql` | UUID-only role helpers and missing role catalog rows; retain legacy table | Transaction rollback; helper replacement is reversible before consumers switch |
| 4 | `004_rls_and_grants.sql` | Deny-by-default policies and explicit grants for all 20 no-policy tables | Transaction rollback; live authorization behavior must be regression-tested |
| 5 | `005_security_definer_grants.sql` | Revoke direct execution of broken/legacy helpers | Transaction rollback; re-grant only by reviewed contract |
| 6 | `006_api_identity_support.sql` | UUID `current_auth_user_id()` helper; no route changes | Transaction rollback |
| 7 | `007_storage_and_preservation.sql` | Assertion-only Storage/Opositeca/import preservation gate | Safe abort; no object/bucket mutation |
| 8 | `008_legacy_cleanup_final_gate.sql` | Final gated function/table cleanup and `public.usuario` removal | Irreversible once committed; backup and restore plan required |

Each file runs in its own transaction. Do not concatenate files into one unreviewed migration. Stop on the first failure. Never use `CASCADE` for the final cleanup.

## Preconditions

- Fresh read-only snapshot matches the current project `ndiooyyqtaeysnedywer` and the expected schema.
- `001_preflight.sql` passes: `public.usuario` has zero rows; no live `guiado`/`guiada` values remain; UUID identity and role tables exist; legacy relations intended to be absent remain absent.
- Canonical role codes are exactly `superadmin`, `admin`, `moderador`, `usuario`; admin access is based on explicit codes, never on the existence of any role assignment.
- Client/backend consumer review has approved the `permite_razonados` column rename and the new exam/question vocabulary.
- OposiBot remains unchanged and its full `/v1/auth/*` contract remains available. No route is removed.
- Existing Opositeca tables, PDF/import staging/parser and Storage buckets/objects have a verified inventory.
- A disposable database clone has been restored and the complete package has passed the dry run, application tests and negative RLS tests.

## Identity and legacy strategy

`auth.users.id`, `perfiles_usuario.usuario_id` and `roles_usuario.usuario_id` remain UUID. The candidate does not dual-write to `public.usuario`; that table is retained empty until the final phase. The final phase is blocked by default and also scans function definitions for `public.usuario` dependencies, which currently includes legacy profile-adapter risk. This is an intentional human gate. The runner must set the session variable `oposicontrol.legacy_cleanup_approved = 'true'` explicitly before opening phase 8.

Security-definer handling is split: the new explicit role helpers are tightly scoped and have `search_path = ''`; broken legacy helpers lose direct Data API execution in phase 5; function drops are delayed to phase 8. The valid UUID conversation function and active auth compatibility functions are not removed by this package.

## RLS and permissions

Phase 4 covers the 20 live tables without policies. User-owned rows are keyed by `auth.uid()` and update policies include both `USING` and `WITH CHECK`. Balance, score, summary and analytics tables are read-only or backend-only for authenticated clients. Public-read catalog tables receive explicit read grants and active-row predicates. Admin-only rules use `is_admin()` and no anonymous or PUBLIC mutation grant remains.

The candidate intentionally does not claim that policy design is product-complete: the human gate must verify routes, Data API exposure and cross-user negative tests.

## Storage and preserved domains

No candidate file deletes or moves Storage objects, buckets, Opositeca rows, OposiRed rows, PDF files, import staging rows or parser data. Phase 7 asserts protected buckets/tables only. Bucket policy/path consolidation is a later reviewed change.

## Dry-run and validation protocol

1. Run a fresh read-only snapshot and save counts, constraints, role codes, policies, grants, function definitions and Storage inventory.
2. Restore that snapshot to a disposable database.
3. Run files 001–007 in order; verify transaction success and rerun each idempotent file once.
4. Run SQL catalog checks for canonical constraints, UUID role helper definitions, policy coverage, grants and absence of `guiado/guiada` data.
5. Run the Android/Ktor/domain test matrix and OposiBot contract tests without modifying OposiBot.
6. Run authenticated negative tests: cross-user SELECT/UPDATE/DELETE, anonymous mutation, non-admin admin-table access and moderator/admin boundary.
7. Do not run file 008 until a separate approval records OposiBot compatibility, legacy dependency removal and backup/restore evidence.

## Rollback

Phases 1–7 are transactionally abortable. If a committed phase needs reversal, use a reviewed inverse migration, not `git` or an ad-hoc console edit. Phase 8 is irreversible at schema level: restore from backup or a point-in-time recovery plan is required. Dropping `public.usuario` must never use `CASCADE`.

## Proposed live order after approval

`001_preflight.sql` → `002_canonical_vocabulary.sql` → `003_identity_and_roles.sql` → `004_rls_and_grants.sql` → `005_security_definer_grants.sql` → `006_api_identity_support.sql` → `007_storage_and_preservation.sql` → post-deploy application/RLS verification → separately approved `008_legacy_cleanup_final_gate.sql`.
