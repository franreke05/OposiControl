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

## Plan de integración v2 (2026-10-19): develop@2cab55b + Bot corregido + panel@4d9dc1e — SIN crear ni fusionar

Análisis con `git merge-tree` (solo objetos, sin refs). Candidato previsto: `2cab55b` ← `86f0da9` ← `1ec2b37` (SEC-V1-05) ← `aa5e3ab` (SEC-V1-04/08) ← `aff478d` (plantillas) ← `4d9dc1e` (panel). `P2-jwtcompat` no aporta nada: 19 de sus 23 ficheros son idénticos a `2cab55b`, y los otros 4 solo sirven de referencia de resolución.

**Conflictos textuales al fusionar 2cab55b ← 86f0da9 (9 ficheros):**

| Fichero | Resolución |
|---|---|
| `OposiBotsAuthGateway.kt` `exchangeEmail` (**seguridad**) | Mantener las dos cosas **en este orden**: decodificar la sesión → comprobar el binding FA39 y revocar si no coincide → solo después `toSession(context, AccessRequestSessionGrant.EMAIL_LINK, decodedSession = s)`. La resolución de cb50ee3 llama a `toSession` primero, así que `enforceAccountGate` aplica sus efectos (neutralizar credencial, revocar sesiones) **antes** de detectar un binding falso |
| `OposiBotsAuthRoutes.kt` (**seguridad**) | Lado develop (sin `isValidEmail`) y la línea 411 de PKCE con `authEmailIssue(email) != null`, como en 4bda9e0 |
| `Application.kt` | Mantener los dos bloques: primero el job del reto diario, después el limitador admin |
| `assertions.sql` | Unión: el bloque Slice 3 de develop (P6 anulada) más los asserts de v1 ("every frozen question bound to a revision", archivada `QUESTION_NOT_IN_ATTEMPT`) más los bloques añadidos por 1ec2b37 y aa5e3ab |
| `db-pg17-gate.test.sh`, `verify-migrations-manifest.test.sh` | Lado v1 (superconjunto; con v1 las migraciones slice cuentan como ya aplicadas en MAIN) |
| `TECH_DEBT.md` | Colisión de ID DEBT-116: renumerar la de v1 a DEBT-160 (el panel usa 157..159) |
| `freeze-manifest.sha256`, `ops/inventory/undocumented-routes.tsv` | **Regenerar**, nunca fusionar |

**Roturas silenciosas, sin marcadores de conflicto:**
- `PENDING_MIGRATIONS.txt` adopta el significado de v1 pero le faltan `20261020100000`, `20261021100000` y `20261023100000`: tiene que tener 29 entradas.
- El gate v1 exige un bloque ROLLBACK en cada migración pendiente; 1020 y 1021 no lo tienen. Hay que portar 0b53da4, que añade los bloques ROLLBACK y además hace un cambio real de SQL: `revoke all on function public.auditoria_log_append_only() from anon, authenticated`.
- **El PREFLIGHT de 1023 falla en el gate v1**, porque el baseline v1 no tiene `noticia` ni `tipo_noticia`. Hace falta un baseline extra con su ACL y sus políticas de MAIN. En local se resolvió con un stub y así pasa.
- `DEPLOYMENT.md` tiene que citar todas las versiones (`verify-migrations-manifest.sh:133`).
- `ORDER_REFUND_PENDING` (lo lanza 102400) no existe en `BackendErrors` en ninguna rama, así que llega al panel como un 5xx. Lo añade E1 de Bot (409).

**Migraciones del candidato, por orden (29, todas con VERIFY):**
- `20261020100000` (expand) y `20261021100000` (contract: `auditoria_log` append-only).
- Las 24 `1022*`. Las contract: 100100, 100200, 100300 (revoke de escrituras de service_role en ledger y cartera), 100400, 101200, 101600 y 101700 (drop de columna y vista), 101900 y 102100.
- 102400 y 102500 (expand, compatible con rolling).
- `20261023100000` (contract) **la última**. Ninguna `1022*` toca `es_admin()`, las `*_admin_all` ni `perfiles_usuario_update_propio`.

**Orden de integración (lo ejecuta el propietario o lo autoriza):**
1. Rama desde 2cab55b, fusionar 86f0da9 con las resoluciones de arriba. Portar 0b53da4, añadir el baseline extra de noticias y montar PENDING con 29 entradas. Gates: `:backend:ktor:test`, `:backend:dbtest:test`, `db-pg17-gate.sh` + `.test.sh`, `verify-migrations-manifest`, `verify-ops-config`, `release.test.sh`.
2. Fusionar 1ec2b37, luego aa5e3ab (unión de manifiestos, PENDING y assertions) y luego aff478d (unión de la plantilla bot; `AUTH_PKCE_MODE` tiene que documentarse como `required`, no como "optional"). Repetir los gates.
3. Fusionar 4d9dc1e, añadir `ORDER_REFUND_PENDING` y pasar los tests del panel (`:features:control-v1`, `exams`, `imports`, `:data`, `:domain`, `:composeApp`, `verify-no-direct-supabase`, `verify-ci-jvmtest-coverage`).
4. Regenerar una sola vez los inventarios (`RouteInventoryTest` en local), el freeze-manifest y CURRENT_STATE. Repetir la lista completa de CI.
5. Despliegue: punto de recuperación de MAIN → las 29 migraciones por orden de nombre en una ventana tranquila (1023 la última y con su expediente) → imágenes bot y control del mismo commit con los env nuevos (`ADMIN_RATE_LIMIT_*` en control, `AUTH_PKCE_MODE=required` en bot). NOT_VERIFIED: qué imagen corre hoy en producción y su compatibilidad con las migraciones contract.

## Siguiente paso propuesto (requiere GO)

Nueva rama `integration/v1-candidate` = develop@7fb9000 + v1-server@86f0da9 (resolución auth de cb50ee3/4bda9e0) + panel@29a019f → regenerar inventarios → gates backend/data/features/PG17 → E2E local. No sustituye a la rama provisional ni se fusiona a develop sin autorización.
