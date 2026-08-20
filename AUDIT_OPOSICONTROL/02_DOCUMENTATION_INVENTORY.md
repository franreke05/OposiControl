# Documentation Inventory

| Documento | Clasificación | Observación |
|---|---|---|
| `README.md` | CURRENT/PARTIAL | describe KMP base y comandos, no refleja todas las rutas actuales |
| `AGENTS.md` | CURRENT | reglas locales de UI, lectura previa, no cambios funcionales y validación |
| `CODEX_CONTEXT.md` | STALE/PARTIAL | contexto muy útil; fecha 2026-06-18, contradice cambios de agosto y contiene datos históricos |
| `OPOSICONTROL_AUDIT.md` | HISTORICAL | auditoría/fixes de 2026-06-24; no es estado actual |
| `docs/admin_catalog_modules.md` | USEFUL_EVIDENCE/STALE | documenta patrón Ktor y `/content`, pero reconoce drift de esquema |
| `docs/admin_content_implementation_notes.md` | STALE/CONTRADICTORY | afirma wiring fake/Supabase distinto del `DataModule` actual |
| `docs/supabase/oposicontrol_real_schema_alignment.md` | DOCUMENTED_ONLY | compara esquema recibido y base real histórica |
| `docs/supabase/*.sql` | DOCUMENTED_ONLY/HISTORICAL | DDL, RPC y políticas; no son migration chain completa |
| `docs/superpowers/plans/2026-08-01-oposibots-user-admin-crud.md` | HISTORICAL/USEFUL_EVIDENCE | registra inspección y smoke test de Supabase realizados en agosto |

## Source of truth

No existe un único source of truth verificable desde el checkout. Código actual, SQL documental, cuatro migrations y el plan de agosto describen familias de esquema diferentes. La BD live queda `NOT_VERIFIED` en esta sesión.
