# Route Map

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

Las rutas Ktor se catalogan en `AUDIT_OPOSICONTROL/11_ENDPOINT_CATALOG.md` y en el código bajo `backend/ktor`. Este documento es el índice canónico de esa evidencia, no una lista de endpoints inventados.

Estado: contratos observados en código; runtime live `NOT_VERIFIED`. Cambios de ruta son propiedad Backend/Ktor y requieren Architecture + Quality.
