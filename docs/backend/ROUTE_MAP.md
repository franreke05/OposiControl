# Route Map

## Canonical V1 reconciliation — 2026-08-20

The route families are classified as follows: `/content/*` and `/admin/*` are legacy compatibility surfaces; `/api/v1/app/*` and `/api/v1/admin/*` are canonical candidates; `/v1/*` is a gateway/compatibility surface; `/v1/auth/*` and OposiBot aliases are `UNKNOWN`/possible external consumers. The local source shows overlapping admin/content capability across `Application.kt`, `AdminGateway`, `AdminV1Routes` and `V1Routes`.

No route was deleted or changed. Any deprecation requires consumer evidence, parity tests, telemetry and human approval.

## Runtime profiles

| Profile | Observed surface | Readiness note |
|---|---|---|
| `combined` (default) | `/health`, public `/v1/*`, `/api/v1/app/*`, optional `/v1/auth/*`, admin `/api/v1/admin/*`, legacy `/admin/*` and `/content/*`, OposiBots admin | requires Supabase service-role gateways; auth feature depends on configuration |
| `consumer` | `/health/live`, `/v1/auth/*` | requires OposiBots auth configuration; admin/content/app surfaces are not mounted |
| other value | behaves as combined because only `consumer` is special-cased | deployment configuration footgun |

## Duplication and canonical candidates

| Capability | Overlapping surface | Contract/DB classification | Candidate canonical surface |
|---|---|---|---|
| Admin content | `/content/*` and `/api/v1/admin/*` | duplicate capability; legacy integer/singular expectations vs newer plural/UUID/V1 expectations | `/api/v1/admin/*` after compatibility evidence |
| Public exam read | `/v1/examenes*` and `/api/v1/app/examenes*` | different trust/read model; live schema not verified | `/api/v1/app/*` for authenticated consumer, `/v1/*` only as explicit public contract |
| Admin aliases | `/admin/*`, `/api/v1/admin/*` and Spanish/English aliases inside V1 | duplicate/legacy; exact data contract not unified | `/api/v1/admin/*` |
| Auth | `/v1/auth/*` | separate OposiBots consumer auth surface | `/v1/auth/*` |

No route was deleted or consolidated in this pass. Candidate canonical surfaces are migration targets, not completed decisions.

## CURRENT_IMPLEMENTATION

Las rutas Ktor se catalogan en `docs/integration/ENDPOINT_CATALOG.md` y en el código bajo `backend/ktor`. Este documento es el índice canónico de esa evidencia, no una lista de endpoints inventados.

Estado: contratos observados en código; Supabase live `READ_ONLY_VERIFIED` para la reconciliación, pero consumidores y runtime de rutas siguen sin handshake completo. Cambios de ruta son propiedad Backend/Ktor y requieren Architecture + Quality.

## POST /api/v1/admin/reportes/{id}/resolver (R2-API004, MASTER7-API-004)

- `accion` en el cable: `descartar`, `ocultar`, `restaurar`, `eliminar`, `advertir`, `bloquear_usuario` (valores del CHECK `acciones_moderacion.tipo_accion`) y los alias de los clientes vivos `avisar` -> `advertir`, `banear` -> `bloquear_usuario`, `resuelto_sin_accion` -> `descartar` (etiqueta neutra del CHECK; el estado del reporte es `resuelto`). Cualquier otro valor: 422 `REPORT_ACTION_INVALID` sin lecturas ni escrituras.
- `descartar` con `ticketsReporter > 0` es contradictorio: 422 `WALLET_AMOUNT_INVALID` (decision deliberada, sin efectos). La UI (lane OC) no debe ofrecer recompensa al descartar; hasta entonces el moderador recibe ese 422.
