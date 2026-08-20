# OPC-20260820-005 — Final Repository Cleanup, Consolidation & Dead-Code Removal

Fecha: `2026-08-20`
Proyecto: `C:\Users\franr\AndroidStudioProjects\OposiControl`
Verdict: `CLEANUP_PARTIAL`
Repository clean for backend work: `YES` (structural only; backend readiness remains `NOT_READY`)

## Starting state

Branch: `main`

HEAD: `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d`

Known baseline: local builds/tests green; Supabase `NOT_VERIFIED / INFRASTRUCTURE_GAP`; database `SEVERE_DRIFT`; PDF `STAGED / WAITING_CLIENT_INPUT`.

## Protected dirty work

Before cleanup, the working tree contained preexisting dirty tracked paths: `.gitignore`, `AGENTS.md`, `CODEX_CONTEXT.md`, `README.md`, `backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt`, `OposiBotsAuthRoutes.kt`, and the functional remediation files from the previous pass. Preexisting untracked/protected trees included `.claude/`, `AUDIT_OPOSICONTROL/` and documentation. `.env`, `.mcp.json`, `.logs/` and generated build/cache paths were not read or exposed.

The user-authorized cleanup touched the root `CODEX_CONTEXT.md` only under its safe-delete rule, and moved historical documents/skills into `docs/archive/`. Backend protected files and forensic audit source were not modified.

## Repository inventory

Inventory scope excludes `.git`, build output, Gradle caches, IDE state, local logs and secret configuration files.

| Metric | Before | After |
|---|---:|---:|
| Files in cleanup scope | 658 | 658 |
| Kotlin/Kotlin script files | 338 | 338 |
| Markdown files | 204 | 205 |
| Active Markdown outside archive/audit evidence | 76 | 76 |
| SQL files | 14 | 14 |
| JSON files | 6 | 6 |
| Gradle build scripts | 28 | 28 |
| Active Gradle modules | 26 | 26 |
| Feature modules | 17 | 17 |
| Resource files inspected | 24 | 24 |

The Markdown increase is two archive manifests minus the deleted root bridge; historical content itself was moved, not duplicated.

## Markdown cleanup matrix

| Path/group | Classification | Evidence/action |
|---|---|---|
| `AGENTS.md`, `docs/README.md`, `docs/brain/*` | `CANONICAL` | retained and synchronized |
| `docs/architecture/*`, `docs/backend/*`, `docs/database/*`, `docs/integration/*`, `docs/product/*`, `docs/engineering/*` | `ACTIVE_SUPPORTING` | retained under domain ownership |
| `docs/registry/*` | `CANONICAL` | retained and updated where cleanup changed state |
| `docs/ledger/*` | `LEDGER` | retained; this ledger added |
| `docs/architecture/adr/*` | `ADR` | retained; no new durable decision justified |
| `AUDIT_OPOSICONTROL/*` | `HISTORICAL / LOCAL_PROTECTED` | retained in place; 41-file forensic source |
| `OPOSICONTROL_AUDIT.md` | `ARCHIVE_CANDIDATE` | moved to `docs/archive/audits/2026-08-19/` |
| `docs/superpowers/plans/2026-08-01-oposibots-user-admin-crud.md` | `PLAN_COMPLETED / ARCHIVE_CANDIDATE` | moved to `docs/archive/plans/` |
| `docs/admin_*.md` | `HISTORICAL / ARCHIVE_CANDIDATE` | moved to `docs/archive/legacy/` |
| `docs/supabase/*.sql` and schema notes | `DOCUMENTED_ONLY / HISTORICAL` | retained as DB evidence; no SQL deletion |
| `CODEX_CONTEXT.md` | `SAFE_DELETE / COMPATIBILITY_BRIDGE` | deleted; sanitized digest exists in `docs/archive/deprecated-docs/` and no active refs remain |

Exact local Markdown link validation after moves: `0` broken relative links.

## AI and skills cleanup

Canonical active framework: `AGENTS.md`, `.claude/HASHIRA_LAWS.md`, `.claude/HASHIRA_ROUTING.md` and ten `hashira-*.md` agents. `.claude/settings.local.json` remains local-only/protected.

The former `.ai` framework had 123 files, including 23 `SKILL.md` files and 55 reference Markdown files. No active project/tool reference was found. It was moved intact to `docs/archive/legacy/ai-skills/`, marked `HISTORICAL / NON_CANONICAL`, and removed from active startup.

`.superpowers/sdd/.gitignore` contained only `*`, had no workflow/content references and was safe-deleted. The completed Superpowers plan was archived, not deleted.

## Kotlin dead-code inventory

The 338 Kotlin/Kotlin-script files were searched through source references, Gradle wiring, Koin modules, Ktor route registration, navigation, tests, resources, expect/actual pairs and configuration names. No file met every required `SAFE_DELETE` condition. The following were retained as `ACTIVE`, `ACTIVE_LEGACY`, `LEGACY_COMPATIBILITY`, `TEST_SUPPORT` or `UNKNOWN` as applicable:

- Legacy/V1 routes and DTOs: retained because external compatibility is not verified.
- OposiBots auth/user-admin code: retained because it is an explicit product boundary.
- PDFBox, Aiken/GIFT and staging import code: retained because PDF input is pending.
- Platform entry points and expect/actual pairs: retained after KMP checks.
- `core/api`: no source files; only generated ignored build output observed. Classified `FILESYSTEM_ONLY / GENERATED_ARTIFACT`, not removed as a tracked module because no active module exists.

Safe-deleted Kotlin files: `NONE`.

## Backend, UI and modules

Backend routes, gateways, DTOs and services were classified source-active or legacy-compatible. No route family was removed. Admin `/api/v1/admin/*`, app `/api/v1/app/*`, public `/v1/*`, legacy `/content/*`/`/admin/*` and OposiBots `/v1/auth/*` remain.

All 26 active Gradle modules remain wired; all 17 features are included in settings, app dependencies and/or bootstrap DI. No module was removed.

UI screens/components remain because navigation, DI, previews, platform targets or product registry references prevent a safe orphan conclusion. No UI behavior changed.

## Dependencies and resources

No dependency was removed. Lettuce is used by `OposiBotsAuthRateLimiter` via `RedisClient`; PDFBox, JWT, Ktor, Koin, SQLDelight and Compose dependencies remain justified by source/build wiring.

Twenty-four resources were inspected. No safe orphan was proven: Compose generated marker XML, launcher resources, logos, fonts, web resources and network security resources have build, manifest, generated-resource or runtime references. No resource was removed.

## Legacy/V1, PDF and OposiBot safety

Legacy/V1 contracts remain `KEEP_LEGACY / MIGRATION_CANDIDATE`. SQL and migrations remain `DOCUMENTED_ONLY / UNKNOWN` because live Supabase is not verified. PDF waiting input is preserved: `YES`. Potential externally consumed OposiBot code deleted: `NONE`.

## Build verification

No functional source was changed during this cleanup; previous technical remediation evidence remains valid. Final verification is rerun after documentation/archive changes:

| Check | Result |
|---|---|
| Audit compile | PASS from prior remediation |
| News unit tests | PASS from prior remediation |
| Exams unit tests | PASS from prior remediation |
| Backend tests | PASS from prior remediation |
| Global tests | PASS from prior remediation |
| Desktop/JVM compile | PASS from prior remediation |
| iOS simulator Kotlin compile | PASS; compile only, no runtime/Xcode |
| Markdown links | PASS; 0 broken local links |
| Git whitespace check | PASS; `git diff --check` exit 0 |

## Broken references

Code: no broken reference introduced by cleanup; no Kotlin deletion occurred.  
Markdown: `0` broken local links.  
Resources: no safe orphan.  
Modules: `core/api` has no active source/module registration; no active module broken.

## Files deleted

1. `CODEX_CONTEXT.md` — safe-delete compatibility bridge; canonical digest and active entry exist.
2. `.superpowers/sdd/.gitignore` — empty/placeholder-only competing framework artifact.

## Files archived

1. `OPOSICONTROL_AUDIT.md` → `docs/archive/audits/2026-08-19/OPOSICONTROL_AUDIT.md`.
2. `docs/superpowers/plans/2026-08-01-oposibots-user-admin-crud.md` → `docs/archive/plans/2026-08-01-oposibots-user-admin-crud.md`.
3. `docs/admin_catalog_modules.md` → `docs/archive/legacy/admin_catalog_modules.md`.
4. `docs/admin_content_implementation_notes.md` → `docs/archive/legacy/admin_content_implementation_notes.md`.
5. `.ai/` → `docs/archive/legacy/ai-skills/` — 123 files preserved.

## Files modified

- `docs/archive/README.md` and archive manifests/readmes.
- `docs/architecture/MODULE_MAP.md`.
- `docs/brain/CURRENT_STATE.md` and `docs/brain/SOURCE_OF_TRUTH.md`.
- This ledger.

No functional behavior changed. No Kotlin, Gradle dependency, resource, module, SQL or test deletion was made.

## Risks and debt

Resolved/mitigated: competing active AI framework removed from startup; stale root audit/plan/admin notes removed from active paths; obsolete root CODEX bridge removed; broken-link check passes. Remaining: Supabase verification, schema/RLS reconciliation, admin role authorization, static token retirement, legacy compatibility, PDF atomicity, UI runtime and deployment validation.

No P1 backend readiness risk is incorrectly marked resolved.

## Human review required

- Whether the archived `.ai` skill tree should eventually be deleted rather than retained.
- Whether `core/api` generated build artifacts should be cleaned by the normal build hygiene process.
- Any future legacy/V1 route or DTO consolidation.
- Any SQL/migration deletion or canonical-schema decision.
- OposiBot compatibility and admin-role authorization decisions.

## Final repository counts

After cleanup: 658 scoped files, 338 Kotlin/KTS, 205 Markdown, 14 SQL, 28 Gradle build scripts, 26 active Gradle modules, 17 feature modules, 24 inspected resources, 13 active canonical AI files plus 123 archived former-skill files.

## Second Brain sync

Updated `CURRENT_STATE`, `SOURCE_OF_TRUTH`, `MODULE_MAP`, archive manifests, registry context and this ledger. The active tree now has one AI governance entry and canonical documentation ownership; archive remains historical evidence only.

## Final Git validation

`git diff --check`: PASS, exit 0.  
Staging: `0` expected; no `git add`, commit, push, merge, rebase or force operation performed.

## Exact next backend gates

1. Authorized read-only Supabase inventory.
2. Canonical migration-source and schema-family decision.
3. Explicit admin-role authorization contract.
4. Service-role/static-token security review.
5. Legacy/V1 and OposiBot compatibility verification.
6. PDF atomicity/publication gate and deployment/proxy validation.
