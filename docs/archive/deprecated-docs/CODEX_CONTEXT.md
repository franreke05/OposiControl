# Historical CODEX_CONTEXT digest

Status: `ARCHIVED_REFERENCE_ONLY`  
Source date: `2026-06-18`  
Canonical replacement: `AGENTS.md` + `docs/brain/` + `.claude/`

## Preservation decision

The former root `CODEX_CONTEXT.md` was an AI context document, not a reliable current-state source. It contained stale claims, historical plans and sensitive credential/token material. This archival digest preserves useful non-sensitive knowledge and intentionally excludes secrets and credential values.

## Knowledge migrated to active documents

- KMP + Ktor architecture → `docs/architecture/BACKEND_ARCHITECTURE.md`, `MODULE_MAP.md`, `SYSTEM_MAP.md`.
- Route families and ownership → `docs/integration/ENDPOINT_CATALOG.md`, `OPOSIBOT_CONTRACT.md`.
- Feature/screen structure → `docs/product/SCREEN_REGISTRY.md`, `docs/product/FEATURE_REGISTRY.md`.
- Ktor server trust boundary and no client service-role exposure → ADR-002/003 and `docs/engineering/SECURITY.md`.
- Legacy/V1 coexistence and schema uncertainty → `docs/database/MIGRATION_STATE.md`.
- Build/test truth and known global failure → `docs/engineering/TESTING.md`, `docs/brain/CURRENT_STATE.md`.

## Historical-only material not promoted

- JWT keys, test credentials, service-role/backend-token values, URLs and environment values.
- Historical live-schema assertions not reverified in this task.
- Old implementation plans, fixes, dashboard proposals and technical remediation instructions.
- Claims that conflict with the current source-of-truth hierarchy.

## Active-framework rule

The only active AI/project framework is `AGENTS.md`, `.claude/HASHIRA_LAWS.md`, `.claude/HASHIRA_ROUTING.md` and the ten `.claude/agents/hashira-*.md` files. This digest is evidence/history only.
