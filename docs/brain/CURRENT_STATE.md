# Current State

Fecha de corte: `2026-08-20`.

- Rama: `main`.
- HEAD capturado antes del pase: `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d`.
- `SECOND_BRAIN: ACTIVE`.
- `HASHIRA: ACTIVE`.
- `MIGRATION: COMPLETED`.
- `TECHNICAL_REMEDIATION: COMPLETED_FOR_LOCAL_BUILD_BASELINE`.
- `BACKEND_READINESS: NOT_READY`.
- `REPOSITORY_CLEANUP: COMPLETED_WITH_NO_KOTLIN_DELETIONS`.
- `LIVE_VERIFIED: NO` para Supabase, storage y despliegues.

## Topology

Active Gradle modules are `composeApp`, `core/common`, `core/network`, `core/database`, `core/ui`, `core/testing`, `domain`, `data`, `backend/ktor` and 17 features: `auth`, `dashboard`, `users`, `roles`, `settings`, `audit`, `specialties`, `communities`, `tags`, `news-types`, `news`, `exams`, `exam-access`, `store`, `tickets`, `support`, `moderation`.

## Backend readiness

The backend has `combined` and `consumer` profiles. Combined mounts public V1 reads, app routes, optional OposiBots auth, admin V1, legacy admin/content and OposiBots admin. Consumer mounts live health and OposiBots auth. Other profile values fall through to combined behavior.

Authentication entry is controlled: the client login bypass is disabled and gateway admin routes require bearer auth. Authorization remains partial because `hasAdminRole` accepts any role assignment; the explicit admin role model is still a product decision. Static backend token is temporary/legacy and dev flags are restricted in code to dev/test.

Service-role references are backend-only in the inspected source. The concentration of privileged gateway access, ownership enforcement and live RLS remain security gates.

## Database and live infrastructure

`DATABASE_MIGRATION_STATE: SEVERE_DRIFT`.

`LIVE_SUPABASE: NOT_VERIFIED / INFRASTRUCTURE_GAP` because the Supabase connector was not connected (`USER_NOT_LOGGED_IN`) and the CLI is not installed. No live project, table, RLS, RPC, Storage or applied migration history was read. No write occurred.

## PDF / Arturo

`PDF_FORMAT_CONTRACT: STAGED / WAITING_CLIENT_INPUT`.

PDFBox and staging routes exist, but atomic final persistence, publication boundary and the final real templates/grammar remain pending. This pass did not freeze a parser or mutate the schema.

## Build evidence

- `:features:audit:compileDebugKotlinAndroid`: PASS.
- `:features:news:testDebugUnitTest`: PASS.
- `:features:exams:testDebugUnitTest`: PASS.
- `:backend:ktor:test`: PASS.
- `test`: PASS.
- `:composeApp:compileKotlinJvm`: PASS.
- `:composeApp:compileKotlinIosSimulatorArm64`: PASS as Kotlin/Native compilation only; no iOS runtime, Xcode or signing verification.

## Working tree protection

Preexisting protected tracked changes remain untouched: `backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt` and `OposiBotsAuthRoutes.kt`. Protected local artifacts remain `.logs/`, `.mcp.json` and `AUDIT_OPOSICONTROL/`. No staging, commit or push was performed.

Functional files changed in this pass: `AuditViewModel.kt` Clock API, News draft interface/DI/test seam/save-draft behavior, and `AppRoot.kt` login flag. Documentation and ledger changes accompany them.

## Repository cleanup

The active AI entry is now `AGENTS.md` plus `.claude/HASHIRA_LAWS.md`, `.claude/HASHIRA_ROUTING.md` and the ten Hashira agents. The former root `CODEX_CONTEXT.md` was safe-deleted because its canonical digest exists and no active tooling references it. The empty Superpowers placeholder was safe-deleted. The former `.ai` skill tree, root audit, completed Superpowers plan and two legacy admin notes were moved to `docs/archive/` and marked historical/non-canonical.

No Kotlin file, Gradle module, dependency, SQL file, test or resource was physically deleted: repository-wide KMP/DI/route/expect-actual/reflection checks did not produce a candidate meeting every `SAFE_DELETE` condition. Legacy/V1 backend routes, auth foundations, PDF importers, OposiBot contracts and SQL evidence remain retained.

## Open P1 gates

Supabase live verification, canonical migration selection, schema/RLS reconciliation, explicit admin-role authorization, static-token retirement, legacy/V1 compatibility evidence, PDF atomicity, UI runtime verification and deployment/proxy validation remain open.

## Next gate

Connect the authorized Supabase project for a read-only audit, decide the admin role model, reconcile the canonical migration chain, then run security/integration/release review. Do not declare `BACKEND_BASELINE_READY` before those gates pass.
