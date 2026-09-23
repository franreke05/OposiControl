# Source of Truth

The applied Database Redesign V1 closure is recorded in
`docs/database/MIGRATION_STATE.md`; the tracked package is
`supabase/canonical/database_redesign_v1/`. No table, row, Storage object or
legacy compatibility adapter was removed.

## Agent read path

1. AGENTS.md
2. .claude/HASHIRA_LAWS.md and .claude/HASHIRA_ROUTING.md
3. CURRENT_STATE.md
4. SYSTEM_MAP.md
5. The domain document listed below
6. The active registry only when the task is affected

## Authority by domain

| DOMAIN | AUTHORITATIVE LOCATION |
|---|---|
| Current operational state | CURRENT_STATE.md |
| Architecture and boundaries | SYSTEM_MAP.md and docs/architecture |
| Database and migrations | docs/database and tracked SQL |
| Backend routes and services | docs/backend and backend/ktor |
| Product scope and pending decisions | PRODUCT_SCOPE.md and docs/product/CLIENT_DECISIONS_REQUIRED.md |
| OposiBot compatibility | docs/integration/OPOSIBOT_CONTRACT.md and OPOSIBOT_CONSUMER_MATRIX.md |
| Active risks/debt/unknowns | docs/registry |
| Canonical migration candidates | supabase/canonical package files |

## Evidence rules

Runtime behavior and executed tests outrank configuration, read-only live evidence, source code, this Second Brain, ADRs and specialized documentation. An unverified claim remains NOT_VERIFIED; a target remains TARGET_REQUIREMENT.

MAIN Product Metrics/wallet evidence is verified for migrations 002–010, canonical writers and exercised invariants. This does not certify unrelated legacy schema, Storage, external providers or deployed OposiBot consumers.

## Protected compatibility

Do not remove public.usuario, /v1/auth/* or POST_OPOSIBOT_LEGACY_CLEANUP dependencies until the external consumer and UUID transition gates pass.
