# Canonical V1 V2 execution plan

Status: `CANDIDATE_ONLY / NO DRY-RUN EXECUTED`

| Order | File | Applies before OposiBot changes? | Rollback |
|---:|---|---|---|
| 1 | `001_preflight.sql` | Yes, assertions only | Abort-safe |
| 2 | `002_canonical_vocabulary.sql` | Yes, canonical vocabulary and neutral content access classification | Transactional |
| 3 | `003_authorization_canonicalization.sql` | Yes, reuses existing UUID functions | Transactional |
| 4 | `004_rls_and_grants.sql` | Yes, removes direct authenticated writes | Transactional |
| 5 | `005_security_definer_grants.sql` | Yes, signature-specific hardening | Transactional |
| 6 | `006_storage_and_preservation.sql` | Yes, assertions only | Abort-safe |
| 7 | `007_plan_entitlements.sql` | Yes, guarded capability rename plus confirmed plan catalogue and benefits | Transactional |

## Authorization source

The live source is `public.es_admin()` and `public.es_moderador_o_admin()`. Both use `auth.uid()`, `roles_usuario`, `roles_app` and UUID role assignments; `es_admin()` already explicitly includes `superadmin`. V2 canonicalizes their `search_path` and grants without creating duplicate `is_*` functions. `can_manage_content()` and `can_moderate()` are wrappers only.

## RLS write policy

The execution architecture is `OposiBot -> Ktor -> service_role`. V2 revokes all `INSERT`, `UPDATE`, `DELETE` and table-level grants from `PUBLIC`, `anon` and `authenticated` for the 20 target tables, then adds only explicit `SELECT` grants for read-only state/catalog tables. The five sensitive tables with no evidenced direct client consumer receive no authenticated grant or policy.

## Deferred decisions and compatibility

`RAZONADO_ACCESS_POLICY = RAZONADOS_PLAN_CONFIRMED`. `planes_suscripcion` + `beneficios_plan` are the entitlement source of truth; `productos_comerciales` stores the confirmed EUR-cent monthly prices. `tipo_acceso` is retained as content/publication classification and no longer constrains exam type. No demo, ticket or usage-limit contract is introduced.

The `permite_guiados -> permite_razonados` rename is part of V2 because active
consumer search found no OposiControl/OposiBot consumer. `001` rejects an
ambiguous or missing initial state; `007` performs the guarded A/B transition
and verifies that only `permite_razonados` remains. The deferred cleanup package
does not repeat this rename.

## Explicitly excluded

- No `current_auth_user_id()` helper.
- No new authorization family.
- No `public.usuario` removal.
- No `oposibots_upsert_own_profile(...)` removal or adapter rewrite.
- No removal of `/v1/auth/*` routes.
- No Storage/object/bucket deletion or movement.
- No Opositeca/OposiRed/PDF/import destructive operation.

## Commercial verification

`007_plan_entitlements.sql` must be included in the disposable-clone sequence. Verify the capability-column postcondition, four plan prices and twelve entitlement rows after the transaction.

## Verification before any dry-run

Use a disposable PostgreSQL clone. Confirm exact function signatures and grants, run 001–007 in order, rerun idempotent files, verify no authenticated DML privileges, run negative RLS tests, verify OposiBot `/v1/auth/*` compatibility and inspect Storage/domain counts.
