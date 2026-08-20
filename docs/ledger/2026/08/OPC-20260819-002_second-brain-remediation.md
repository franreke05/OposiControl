# OPC-20260819-002 — Second Brain remediation pass 01

Date: `2026-08-19`
Branch: `main`
Starting HEAD: `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d`
Owner: Documentation Hashira
Reviewers: Architecture, Quality, Backend/Ktor, Content/Import, Database, Integration, Admin UI/UX

## Objective

Resolve migration P1 and safe documentation P2 findings from the post-migration audit. No technical remediation was authorized.

## Migration P1 addressed

- Endpoint catalog recovered from forensic source with all six required families and explicit source/live statuses.
- Screen Registry recovered with the 17 required sections, navigation keys, modules, files, reachability, current status, desktop/mobile, dependencies, issues and evidence.
- Migration state recovered with `SEVERE_DRIFT`, drift families/dimensions, evidence classes and no canonical migration source selected.
- `CODEX_CONTEXT.md` replaced by a `DEPRECATED / NON_CANONICAL` bridge; a sanitized historical digest is under `docs/archive/deprecated-docs/`.

## Migration P2 addressed

- Product Scope now separates `CURRENT_IMPLEMENTATION`, `TARGET_PRODUCT`, `CONFIRMED_PRODUCT_DECISIONS`, `PENDING_PRODUCT_DECISIONS` and `OUT_OF_SCOPE`.
- Support separates current from target and marks bidirectional support `TARGET_CONFIRMED`.
- PDF contract names Arturo/Client as owner of missing information without inventing grammar.
- Feature Registry records Store, Subscriptions and Notifications without claiming end-to-end implementation.
- Module Map lists the real active Gradle modules and 17 features.
- Hashira contracts now declare mission, implementation/review authority, reviewers, gates, validation, documentation ownership and overlap handling.
- Auth↔Integration, Admin UI↔Content/Import and Release/Infra↔Backend ownership is explicit.
- UI principle `STRUCTURE / USABILITY > PIXEL PERFECT` is explicit with usability constraints.

## Gitignore decision

`.logs/` is generated/local runtime material. `.mcp.json` is local connector configuration and may contain credentials. Both are now ignored. Their contents were not read or exposed. Hashira laws/routing/agents remain versionable; `.claude/settings.local.json` remains ignored.

## Knowledge-loss verification

All required endpoint families: `PRESERVED`. All 17 required screen sections: `PRESERVED`. Audit source count: 41; manifest count: 41; missing: 0. The archived CODEX context digest excludes secrets intentionally; useful non-sensitive knowledge is mapped to active docs.

## Protected dirty files

Preexisting functional changes remain protected: `backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt`, `OposiBotsAuthRoutes.kt`. Preexisting local/untracked material remains protected: `.logs/`, `.mcp.json`, `AUDIT_OPOSICONTROL/`. Migration documentation changes from the previous pass and this pass are also uncommitted and unstaged.

## Functional Kotlin changes

`NONE` introduced by this remediation. No Kotlin, KTS functional logic, routes, gateways, repositories, SQL, migrations, RLS, RPC or storage changed.

## Build/tests

Build/tests were not changed by this task. Existing truth remains: backend tests PASS, exams tests PASS, global test FAILS at `AuditViewModel.kt:35:49` with unresolved `System` in debug/release compilation.

## Remaining technical P1

Admin login bypass, schema/migration drift, non-atomic PDF import, global build failure, service-role concentration, legacy/V1 coexistence, Supabase runtime unknown and UI runtime unknown remain open. `BASELINE: NOT_ESTABLISHED`; technical remediation remains pending.

## Next gate

Re-audit the active Second Brain. After a clean re-audit, decide whether to begin technical remediation. Product decisions still pending: admin role model, Arturo’s final PDF format and final import/publication states.
