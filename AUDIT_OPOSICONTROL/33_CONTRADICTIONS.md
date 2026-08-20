# Contradictions Register

| ID | Conflict | Evidence / impact |
|---|---|---|
| C-001 | DDL docs use singular English/int while current V1 code expects Spanish plural/UUID | alignment doc, gateways and AdminV1Routes; high contract risk |
| C-002 | `docs/admin_content_implementation_notes.md` describes fake/SupabaseContent wiring, current `DataModule` wires Ktor Admin V1 | stale documentation |
| C-003 | catalog docs call `/content/*` the current convention, while `BackendRoutes` and most current repos use `/api/v1/admin/*` | two active surfaces |
| C-004 | `CODEX_CONTEXT.md` says no KMP repos for admin V1; current code has them | context dated 2026-06-18 |
| C-005 | plan records direct production migration/smoke success, but this checkout/session cannot verify live DB | historical vs current runtime |
| C-006 | Roles UI describes CRUD/permissions, `RolesApi` comments that roles are read-only | product/API mismatch |
| C-007 | README says no JS/Wasm targets, repository contains `webMain` resources | resource presence is not a target; status needs build verification |
| C-008 | UI login exists but `TEMPORARILY_DISABLE_LOGIN` routes directly to dashboard | security behavior differs from implemented auth |

All conflicts remain documented; none were corrected.
