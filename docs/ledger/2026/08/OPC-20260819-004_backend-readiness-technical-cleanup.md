# OPC-20260819-004 — Backend Readiness & Technical Cleanup

Fecha: `2026-08-19`
Proyecto: `C:\Users\franr\AndroidStudioProjects\OposiControl`
Estado: `COMPLETED_WITH_NOT_READY_GATE`

## Starting state

The local Second Brain classified migration remediation as complete but technical baseline as pending. The global test graph was blocked by `AuditViewModel.kt`, the client panel login bypass was enabled, backend route generations coexisted, and Supabase live state was not verified.

## Protected changes

Git state was captured before edits at branch `main`, HEAD `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d`, with no staged changes. Preexisting protected tracked files were `backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt` and `OposiBotsAuthRoutes.kt`; `.logs/`, `.mcp.json` and `AUDIT_OPOSICONTROL/` remain protected local artifacts. No commit, push or staging occurred.

## Build blocker and fixes

- Corrected Audit from deprecated `kotlinx.datetime.Clock.System` usage to `kotlin.time.Clock.System`.
- Converted the News editor draft contract to an interface, retained the SQLDelight implementation, wired DI, and supplied an in-memory test double.
- Aligned `saveDraft()` with the tested local-draft contract so saving a draft does not invoke the backend.
- Disabled `TEMPORARILY_DISABLE_LOGIN` in `AppRoot`.

## Backend topology

`combined` mounts health, public V1 reads, app V1, optional OposiBots auth, admin V1, legacy admin/content and OposiBots admin. `consumer` mounts live health and OposiBots auth. Any other profile value falls through to combined behavior. Route duplication remains between legacy `/content/*`/`/admin/*`, `/api/v1/admin/*`, public `/v1/*` and authenticated `/api/v1/app/*`; no route was deleted.

## Auth and service-role findings

Frontend login entry is controlled and backend admin routes require bearer authentication. Authorization is `PARTIAL`: `hasAdminRole` checks any role assignment, not an explicit admin role. The role model remains a product decision. Service-role references were found in backend gateways only; no client service-role reference was observed. Static backend token is temporary/legacy, and dev user/token flags are restricted to dev/test in code.

## Supabase and schema

Supabase read-only verification was unavailable: the connector returned `USER_NOT_LOGGED_IN` and the local CLI is not installed. Project ref, live tables, columns, RLS, RPC, Storage, migration history and advisors are `NOT_VERIFIED / INFRASTRUCTURE_GAP`. Local inventory found 14 SQL files, 38 table definitions, 10 function definitions and 19 policy target tables, but these are not evidence of deployed state. No database or storage mutation occurred.

## Legacy/V1 and endpoint contract

Legacy singular/integer expectations and plural/Spanish/UUID V1 expectations coexist in code and SQL. Candidate canonical surfaces are `/api/v1/admin/*` for admin, `/api/v1/app/*` for authenticated consumer flows and `/v1/auth/*` for OposiBots auth, subject to compatibility evidence. This is a recommendation, not an architecture decision.

## Security and observability

Content negotiation, authentication and status pages are installed. OposiBots auth has custom rate limiting and request IDs. Global CallLogging, metrics/tracing, global rate limiting, verified CORS and verified HSTS are absent or not established in source. Logback is present; deployed dashboards/logs are not verified. PDF/admin code retains a documented non-atomic fallback risk.

## Verification

| Check | Result |
|---|---|
| `:features:audit:compileDebugKotlinAndroid` | PASS |
| `:features:news:testDebugUnitTest` | PASS |
| `:features:exams:testDebugUnitTest` | PASS |
| `:backend:ktor:test` | PASS |
| `test` | PASS |
| `:composeApp:compileKotlinJvm` | PASS |
| `:composeApp:compileKotlinIosSimulatorArm64` | PASS; compile only, no iOS runtime |
| `git diff --check` | pending final rerun |

## Risks, debt and ADRs

Resolved locally: global build blocker and client login bypass. Open P1: live Supabase gap, schema drift, partial admin authorization, service-role concentration, legacy/V1 compatibility, PDF atomicity, UI/runtime/deployment verification and temporary token. Registries were updated in `docs/registry/`. No ADR was created because no durable migration or canonical schema decision was justified by the evidence.

## Files changed by this pass

Functional: `composeApp/.../AppRoot.kt`, `data/.../DataModule.kt`, `data/.../NewsEditorDraftLocalDataSource.kt`, `features/audit/.../AuditViewModel.kt`, `features/news/.../NewsViewModel.kt`, `features/news/.../NewsViewModelTest.kt`.

Documentation: `docs/README.md`, `docs/brain/CURRENT_STATE.md`, backend/database/engineering/product documents, registries, and this ledger.

## Final state and next gate

Local build baseline: `GREEN`. Backend readiness: `NOT_READY`. `BACKEND_BASELINE_READY` is not declared. Next gate is an authorized read-only Supabase audit, explicit admin-role decision, canonical migration selection, schema/RLS reconciliation, security review and deployment/proxy validation.
