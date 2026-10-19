# INTEGRATION_CONFLICTS — Bot ↔ Control

Fecha: 2026-10-19. Análisis con `git merge-tree` (sin tocar ramas). **Ningún merge realizado; los merges los autoriza el propietario.**

## Topología

| Rama | SHA | Base común | Nota |
|---|---|---|---|
| `develop` (local) | 7fb9000 | — | 2 commits por delante de `origin/develop` 21cf436 (E6 sin publicar) |
| `feature/december-v1-server` | 86f0da9 | f22644c con develop | +86 / −29 respecto a develop; **sin E6** |
| `feature/december-v1-control-panel` | 29a019f | 21cf436 con develop | +6 / −2 |
| `integration/bot-backend-on-develop` | cb50ee3 | 21cf436 | develop antiguo + v1-server + panel **501456d** (antiguo); sin E6 → **no candidata** |
| `feature/december-v1-security-server` | 88d0baf | f22644c | 31 commits no presentes en v1-server (harness E2); solape no analizado |

## Conflictos al fusionar develop@7fb9000 + v1-server@86f0da9 + panel@29a019f

| ID | Sev | Archivos | Problema | Resolución propuesta |
|---|---|---|---|---|
| IC-01 | P0 (si se resuelve mal) | `OposiBotsAuthGateway.kt` (~L209), `OposiBotsAuthRoutes.kt` (~L467) | develop aporta `AccessRequestSessionGrant` (351c093/b5a5913); v1-server aporta binding FA39 + `isValidEmail` (bf7dc2a). Elegir un lado elimina un control de seguridad | Reutilizar la resolución de cb50ee3/4bda9e0 y verificar ambos tests |
| IC-02 | P1 | `AppV1Routes.kt`, `PrometheusMetrics.kt`, `Application.kt` | E6 (aud/exp, /metrics, rate limit) frente a cambios Bot en los mismos puntos | Conservar E6 íntegro; test de guarda |
| IC-03 | P1 | `BackendErrors.kt` | Códigos interdependientes (CP-02); EXAM_COMMUNITY_REQUIRED definido dos veces con mensajes distintos | Unión sin duplicados; un único dueño (Bot); los códigos de Control en un mapa propio |
| IC-04 | P1 | `ops/deploy/release.sh`, `ci.yml` | v1-server borra `release.sh` y su test, y quita targets `:features:moderation:jvmTest`, `:features:news-types:jvmTest` | Mantener versión develop |
| IC-05 | P1 | `supabase/baseline/PENDING_MIGRATIONS.txt` | Significado distinto por rama (develop: lista del gate PG17 obsoleta; v1-server: delta real MAIN, 24) | Usar la versión de integración (26 en orden) y alinear la cabecera del gate |
| IC-06 | P1 | `ops/inventory/*.tsv`, `routes.combined.txt`, `freeze-manifest.sha256`, `undocumented-routes.tsv` | Ficheros generados en conflicto | Regenerar con `RouteInventoryTest` y test de freeze, nunca fusión manual |
| IC-07 | P1 | Orden de despliegue | El Ktor del panel necesita migraciones 101300-102300 antes; si no, 501/500 | Migraciones → imagen conjunta (ver RB-09) |
| IC-08 | P2 | `docs/registry/TECH_DEBT.md`, `scripts/verify-migrations-manifest.test.sh` | Conflicto textual | Manual |

## Propiedad de archivos (para evitar ediciones simultáneas)

**Bot (edición activa 10-17…10-19, no tocar desde Control):** `AdminV1Routes.kt` (importación, accesos, comunidad), `BackendErrors.kt`, `Application.kt`, `OposiBotsAuth*.kt`, `EmailCodeBinding.kt`, `RichMarkup.kt`, `RafagaParser.kt`, `Foro*.kt`, `Catalog*.kt`, `Attempt*.kt`, `SupabaseRequestTransport.kt`, migraciones `20261022*`, `openapi/*`, `ops/inventory/*`, `ops/local-exam/*`, `supabase/baseline/*`.

**Control (seguro):** `AdminControl*.kt` y sus tests, `data/`, `domain/`, `features/*`, `composeApp/`, `docs/audit-v1/*`, `.github/workflows/ci.yml`, `ops/deploy/*`, `ops/caddy/*`, nuevas migraciones con timestamp posterior a 20261022102300 propias de Control (p. ej. SEC-V1-01).

**Compartido con protocolo:** `BackendErrors.kt` (dueño Bot; Control propone), firmas RPC de `102300` (dueño Bot; cambios con visto bueno de Control), `Application.kt` (parche SEC-V1-02 de Control en develop: pequeño, Bot hace rebase).

## Siguiente paso propuesto (requiere GO)

Nueva rama `integration/v1-candidate` = develop@7fb9000 + v1-server@86f0da9 (resolución auth de cb50ee3/4bda9e0) + panel@29a019f → regenerar inventarios → gates backend/data/features/PG17 → E2E local. No sustituye a la rama provisional ni se fusiona a develop sin autorización.
