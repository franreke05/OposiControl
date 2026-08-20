# OPC-20260819-001 — Second Brain + Hashira reorganization

Date: `2026-08-19`
Branch: `main`
Starting HEAD: `465c911e456e4263b51f3b9fb30cc3e2a8ee3f3d`
Owner: Documentation Hashira
Reviewers: Architecture, Quality, affected domain Hashiras

## Objective

Convert the exploratory audit into a governed Second Brain and Hashira routing model without changing functional Kotlin, DB, auth, UI, endpoints, Supabase or deployment.

## Protected starting state

`backend/ktor/build.gradle.kts`, `OposiBotsAuthGateway.kt`, `OposiBotsAuthRoutes.kt`, `.logs/`, `.mcp.json` and `AUDIT_OPOSICONTROL/` were preexisting protected changes/untracked work. No staging or destructive Git command was used.

## Decisions captured

Arturo is current client; Claudia/CaravanTruck is not mixed. Auth target is email/password + Google + Apple iOS. PDF contract is staged/waiting Arturo. Target exam types are INMERSIVOS/RAZONADOS/RAFAGAS; current verified names are rafaga/inmersivo. OCR is not required for current scope. Import is staged/reviewable, duplicates warn-and-decide/default-skip, publication explicit. News uses SPECIALTY. Support target workflow is recorded without replacing current enums. Admin role model remains pending.

## Files and knowledge

Created the requested `.claude` laws/routing and exactly ten agents, canonical `docs/` Second Brain sections, eight ADRs, registries, governance, ledger and archive manifests. Root `README.md` was replaced with a concise operational entry point; `AGENTS.md` was extended with startup/close protocol. Existing local `.claude/settings.local.json`, `.ai`, `.superpowers`, `.logs`, `.mcp.json` and audit source were preserved.

## Audit preservation

The original untracked `AUDIT_OPOSICONTROL/` remains intact. `docs/archive/audits/2026-08-19/` records the exact 41-file manifest and source path rather than moving/deleting protected evidence. This is a reversible no-loss archive reference.

## Verification

- Backend Ktor tests: PASS.
- Exams unit tests: PASS.
- Global test: FAIL at `features/audit/.../AuditViewModel.kt:35:49` during `compileDebugKotlinAndroid`, unresolved `System`; not fixed.
- `git diff --check`: required at close.
- Staging: must remain empty.

## Open risks and next gate

P1 risks remain: auth bypass, schema/migration drift, non-atomic import, global build failure, service-role concentration, legacy/V1 coexistence, Supabase runtime unknown and UI runtime unknown. Next gate is a reproducible baseline plus human decisions on roles, PDF contract and migration source. `REMEDIATION: PENDING`.
