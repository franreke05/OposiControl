# Endpoint Catalog

Current backend additions verified in source: `/api/v1/app/commercial/catalog`, `/api/v1/app/commercial/purchases` and `/api/v1/app/commercial/subscriptions` are read-only authenticated contracts. They use canonical internal commercial tables; provider checkout, webhooks and external writers remain `WAITING_EXTERNAL_PROVIDER`.

Catálogo activo reconstruido desde código Ktor; la evidencia histórica de auditoría ya no forma parte del working tree.

Reglas de verdad:

- `SOURCE_VERIFIED`: la ruta/familia aparece en código fuente activo.
- `DOCUMENTED_ONLY`: solo aparece en documentación histórica.
- `LIVE_READ_ONLY_VERIFIED`: Supabase catalog, data counts, functions, migration statements and Storage object counts were inspected without writes; HTTP runtime remains unverified.
- Los campos no demostrados se mantienen como `UNKNOWN`.
- `OposiBot consumer` se clasifica por evidencia en `docs/integration/OPOSIBOT_CONSUMER_MATRIX.md`.

Para evitar repetir afirmaciones, cada tabla declara los metadatos comunes aplicables a todas sus filas: DTOs, errores, consumidores y evidencia. No se inventan nombres de DTO ni respuestas live.

## Common contract fields

- Request DTO: `UNKNOWN` salvo que el nombre aparezca explícitamente en el route/source.
- Response DTO: `UNKNOWN` salvo que el nombre aparezca explícitamente en el route/source.
- Errors: status/JSON exacto `NOT_VERIFIED` en runtime; tests de endpoint existen en backend según auditoría, pero no se asigna cobertura por fila sin prueba específica.
- DB/RPC/Storage dependency: Supabase/gateway esperado; estado live `READ_ONLY_VERIFIED`, runtime/authorization behavior pending.
- Admin UI consumer: `PARTIAL / SOURCE_NOT_MAPPED` salvo evidencia de repositorio explícita.
- OposiBot consumer: `CONSUMED`, `NOT_CONSUMED`, `LEGACY` or `UNKNOWN` according to the consumer matrix.

## `/content/*` — legacy admin/content

Common: Domain `content`; Authorization `ADMIN_REQUIRED`; Route implementation `Application.kt`; Service `UNKNOWN`; Gateway `SupabaseContentGateway`; Request/Response DTO `UNKNOWN`; Errors `UNKNOWN`; Tests `SOURCE_DOCUMENTED / endpoint tests reported`; DB/RPC/Storage `Supabase expected, LIVE_NOT_VERIFIED`; Admin UI `PARTIAL`; OposiBot `NOT_VERIFIED`; Evidence `Application.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `CONTENT-001` | GET/POST/PUT/DELETE | `/content/especialidades[/{id}]` | `LEGACY` |
| `CONTENT-002` | GET/POST/PUT/DELETE | `/content/etiquetas[/{id}]` | `LEGACY` |
| `CONTENT-003` | GET/POST/PUT/DELETE | `/content/comunidades[/{id}]` | `LEGACY` |
| `CONTENT-004` | GET/POST/PUT/DELETE | `/content/solucionarios[/{id}]` | `LEGACY` |
| `CONTENT-005` | POST/DELETE | `/content/solucionarios/{id}/portada` | `LEGACY` |
| `CONTENT-006` | GET/POST/PUT/DELETE | `/content/solucionarios/{id}/preguntas[/{preguntaId}]` | `LEGACY` |
| `CONTENT-007` | PUT | `/content/solucionarios/{id}/preguntas/order` | `LEGACY` |
| `CONTENT-008` | GET/POST/PUT/DELETE | `/content/rafaga[/{id}]` | `LEGACY` |
| `CONTENT-009` | GET/POST/PUT/DELETE | `/content/rafaga/{id}/preguntas[/{preguntaId}]` | `LEGACY` |
| `CONTENT-010` | PUT | `/content/rafaga/{id}/preguntas/order` | `LEGACY` |
| `CONTENT-011` | POST/DELETE | `/content/preguntas/{preguntaId}/recursos/{resourceId}/upload` | `LEGACY` |
| `CONTENT-012` | POST/DELETE | `/content/preguntas/{preguntaId}/recursos[/{resourceId}]` | `LEGACY` |
| `CONTENT-013` | PUT | `/content/preguntas/{preguntaId}/recursos/order` | `LEGACY` |
| `CONTENT-014` | GET/POST/PUT/DELETE | `/content/noticias[/{noticiaId}]` | `LEGACY` |
| `CONTENT-015` | POST | `/content/noticias/assets` | `LEGACY` |
| `CONTENT-016` | GET | `/content/tags` | `LEGACY` |
| `CONTENT-017` | GET | `/content/search` | `LEGACY` |
| `CONTENT-018` | POST | `/content/search/reindex` | `LEGACY` |

Known storage limits preserved from evidence: portada/assets `10 MB`; resource upload `50 MB`. Exact enforcement and live bucket state are `LIVE_NOT_VERIFIED`.

## `/v1/*` — public read API

Common: Domain `public app read`; Authorization `PUBLIC`; Route implementation `V1Routes.kt`; Service `UNKNOWN`; Gateway `SupabaseV1Gateway`; Request/Response DTO `UNKNOWN`; Errors `UNKNOWN`; Tests `SOURCE_DOCUMENTED / endpoint tests reported`; DB/RPC/Storage `Supabase expected, LIVE_NOT_VERIFIED`; Admin UI `UNKNOWN`; OposiBot `NOT_VERIFIED`; Evidence `V1Routes.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `V1-001` | GET | `/v1/especialidades` | `SOURCE_VERIFIED` |
| `V1-002` | GET | `/v1/examenes` | `SOURCE_VERIFIED` |
| `V1-003` | GET | `/v1/examenes/{examenId}` | `SOURCE_VERIFIED` |
| `V1-004` | GET | `/v1/examenes/{examenId}/preguntas` | `SOURCE_VERIFIED` |

## `/api/v1/admin/*` — admin V1

Common: Domain `admin`; Authorization `ADMIN_REQUIRED`; Route implementation `AdminV1Routes.kt`; Service `UNKNOWN`; Gateway `AdminV1Gateway`; Request/Response DTO `SOURCE_CODE_OR_UNKNOWN`; Errors `SOURCE_CODE_OR_UNKNOWN`; Tests `backend tests / source documented`; DB/RPC/Storage `Supabase expected, LIVE_NOT_VERIFIED`; Admin UI `PARTIAL`; OposiBot `NOT_VERIFIED`; Evidence `AdminV1Routes.kt`, `BackendRoutes.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADMIN-001` | GET/POST/PATCH/DELETE | `/api/v1/admin/especialidades[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-002` | GET/POST/PATCH/DELETE | `/api/v1/admin/examenes[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-003` | GET | `/api/v1/admin/examenes/{id}/preguntas` | `SOURCE_VERIFIED` |
| `ADMIN-004` | PATCH | `/api/v1/admin/examenes/{id}/preguntas/orden` | `SOURCE_VERIFIED` |
| `ADMIN-005` | POST | `/api/v1/admin/examenes/{id}/publicar` | `SOURCE_VERIFIED` |
| `ADMIN-006` | POST | `/api/v1/admin/examenes/{id}/despublicar` | `SOURCE_VERIFIED` |
| `ADMIN-007` | POST/DELETE | `/api/v1/admin/examenes/{id}/portada` | `SOURCE_VERIFIED` |
| `ADMIN-008` | GET/POST/PATCH/DELETE | `/api/v1/admin/preguntas[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-009` | GET/POST | `/api/v1/admin/preguntas/{id}/recursos` | `SOURCE_VERIFIED` |
| `ADMIN-010` | PATCH/DELETE | `/api/v1/admin/preguntas/{id}/recursos/{recursoId}` | `SOURCE_VERIFIED` |
| `ADMIN-011` | GET/POST | `/api/v1/admin/importaciones/preguntas[/{loteId}]` | `SOURCE_VERIFIED` |
| `ADMIN-011A` | POST multipart | `/api/v1/admin/importaciones/preguntas/pdf` | `SOURCE_VERIFIED` — digital/selectable-text PDF to staging; 20 MB limit; no OCR |
| `ADMIN-012` | PATCH | `/api/v1/admin/importaciones/staging/{id}` | `SOURCE_VERIFIED` |
| `ADMIN-013` | POST | `/api/v1/admin/importaciones/{loteId}/importar` | `SOURCE_VERIFIED` |
| `ADMIN-014` | POST | `/api/v1/admin/rafagas/import` | `RETIRED` (DEC-IMP-05: publicaba sin staging ni revisión; el flujo canónico es ADMIN-011A + ADMIN-013) |
| `ADMIN-015` | GET | `/api/v1/admin/feedback/preguntas` | `SOURCE_VERIFIED` |
| `ADMIN-016` | POST | `/api/v1/admin/feedback/preguntas/{id}/resolver` | `SOURCE_VERIFIED` |
| `ADMIN-017` | GET | `/api/v1/admin/auditoria` | `SOURCE_VERIFIED` |
| `ADMIN-018` | GET/PATCH | `/api/v1/admin/usuarios[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-019` | POST | `/api/v1/admin/usuarios/{id}/roles` | `SOURCE_VERIFIED` |
| `ADMIN-020` | POST | `/api/v1/admin/usuarios/{id}/tickets` | `SOURCE_VERIFIED` |
| `ADMIN-021` | GET/POST | `/api/v1/admin/reportes[/{id}/resolver]` | `SOURCE_VERIFIED` |
| `ADMIN-022` | GET | `/api/v1/admin/roles` | `SOURCE_VERIFIED` |
| `ADMIN-023` | GET | `/api/v1/admin/tickets` | `SOURCE_VERIFIED` |
| `ADMIN-024` | GET/POST | `/api/v1/admin/tickets/{usuarioId}/transacciones` | `SOURCE_VERIFIED` |
| `ADMIN-025` | GET | `/api/v1/admin/soporte` | `SOURCE_VERIFIED` |
| `ADMIN-026` | GET/POST | `/api/v1/admin/soporte/conversaciones/{id}/mensajes` | `SOURCE_VERIFIED` |
| `ADMIN-027` | GET/POST | `/api/v1/admin/accesos` | `SOURCE_VERIFIED` |
| `ADMIN-028` | GET | `/api/v1/admin/moderacion`, `/reportes`, `/actions`, `/acciones` | `SOURCE_VERIFIED` |
| `ADMIN-029` | POST | `/api/v1/admin/moderacion/actions` | `SOURCE_VERIFIED` |
| `ADMIN-030` | GET | `/api/v1/admin/settings`, `/ajustes` | `SOURCE_VERIFIED` |
| `ADMIN-031` | GET/POST/PATCH/DELETE | `/api/v1/admin/etiquetas[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-032` | GET/POST/PATCH/DELETE | `/api/v1/admin/comunidades-autonomas[/{id}]` | `SOURCE_VERIFIED` |
| `ADMIN-033` | GET | `/api/v1/admin/dashboard` | `SOURCE_VERIFIED` |
| `ADMIN-034` | GET | `/api/v1/admin/feedback/preguntas/{id}` | `SOURCE_VERIFIED` |
| `ADMIN-035` | GET/POST | `/api/v1/admin/tienda/articulos` | `SOURCE_VERIFIED` — replaces Desktop's direct Supabase call from `SupabaseCommerceRepositoryImpl` |
| `ADMIN-036` | GET/POST | `/api/v1/admin/tienda/ofertas` | `SOURCE_VERIFIED` |
| `ADMIN-037` | GET/POST | `/api/v1/admin/tienda/recursos-temporales` | `SOURCE_VERIFIED` |
| `ADMIN-038` | GET/POST | `/api/v1/admin/tienda/rafagas` | `SOURCE_VERIFIED` |

## `/health/*` — public, unauthenticated

Common: Domain `observability`; Authorization `PUBLIC` (mounted outside `authenticate("gateway-auth")` on purpose — orchestrators/load balancers cannot send a Bearer token); Route implementation `Observability.kt` (`observabilityHealthRoutes`); Tests `ObservabilityTest`; DB/RPC/Storage `none for /health/live; a single lightweight GET to Supabase PostgREST root for /health/ready (no business query)`; Evidence `Observability.kt`, `ObservabilityTest.kt`.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `HEALTH-001` | GET | `/health` | `SOURCE_VERIFIED` — legacy, unchanged, kept for backward compatibility |
| `HEALTH-002` | GET | `/health/live` | `SOURCE_VERIFIED` — process alive, never depends on external state |
| `HEALTH-003` | GET | `/health/ready` | `SOURCE_VERIFIED` — checks Supabase reachability; `200` ready / `503` not_ready |

## `/api/v1/app/*` — authenticated app API

Common: Domain `app`; Authorization `JWT_REQUIRED`, with documented dev fallback controlled by `OPOSICONTROL_ALLOW_DEV_USER`; Route implementation `AppV1Routes.kt`; Service `UNKNOWN`; Gateway `AppV1Gateway`; Request/Response DTO `SOURCE_CODE_OR_UNKNOWN`; Errors `SOURCE_CODE_OR_UNKNOWN`; Tests `SOURCE_DOCUMENTED`; DB/RPC/Storage `Supabase expected, LIVE_NOT_VERIFIED`; Admin UI `UNKNOWN`; OposiBot `NOT_VERIFIED`; Evidence `AppV1Routes.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `APP-001` | GET | `/api/v1/app/home` | `SOURCE_VERIFIED` |
| `APP-002` | GET | `/api/v1/app/examenes` | `SOURCE_VERIFIED` |
| `APP-003` | GET | `/api/v1/app/examenes/{id}` | `SOURCE_VERIFIED` |
| `APP-004` | POST | `/api/v1/app/examenes/{id}/iniciar` | `SOURCE_VERIFIED` |
| `APP-005` | POST | `/api/v1/app/intentos/{id}/responder` | `SOURCE_VERIFIED` |
| `APP-006` | POST | `/api/v1/app/intentos/{id}/finalizar` | `SOURCE_VERIFIED` |
| `APP-007` | GET | `/api/v1/app/estadisticas` | `SOURCE_VERIFIED` |
| `APP-008` | GET | `/api/v1/app/reto-diario` | `SOURCE_VERIFIED` |
| `APP-009` | POST | `/api/v1/app/feedback/pregunta` | `SOURCE_VERIFIED` |
| `APP-010` | POST | `/api/v1/app/eventos` | `SOURCE_VERIFIED` |

## `/v1/auth/*` — OposiBots authentication

Common: Domain `OposiBots auth`; Authorization varies by handler: public/rate-limited for registration and credential exchange; bearer for protected operations; exact matrix `UNKNOWN` without runtime route test; Route implementation `OposiBotsAuthRoutes.kt`; Service `UNKNOWN`; Gateway `OposiBotsAuthGateway`; Request/Response DTO `SOURCE_CODE_OR_UNKNOWN`; Errors `SOURCE_CODE_OR_UNKNOWN`; Tests `backend auth tests / source documented`; DB/RPC/Storage `Supabase Auth expected, LIVE_NOT_VERIFIED`; Admin UI `UNKNOWN`; OposiBot `NOT_VERIFIED`; Evidence `OposiBotsAuthRoutes.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `AUTH-001` | POST | `/v1/auth/register` | `SOURCE_VERIFIED` |
| `AUTH-002` | POST | `/v1/auth/email/resend` | `SOURCE_VERIFIED` |
| `AUTH-003` | POST | `/v1/auth/email/exchange` | `SOURCE_VERIFIED` |
| `AUTH-004` | POST | `/v1/auth/login` | `SOURCE_VERIFIED` |
| `AUTH-005` | POST | `/v1/auth/refresh` | `SOURCE_VERIFIED` |
| `AUTH-006` | POST | `/v1/auth/recover` | `SOURCE_VERIFIED` |
| `AUTH-007` | POST | `/v1/auth/reset-password` | `SOURCE_VERIFIED` |
| `AUTH-008` | POST | `/v1/auth/oauth/google` | `SOURCE_VERIFIED` |
| `AUTH-009` | POST | `/v1/auth/oauth/apple` | `SOURCE_VERIFIED` |
| `AUTH-010` | POST | `/v1/auth/logout` | `SOURCE_VERIFIED` |
| `AUTH-011` | GET | `/v1/auth/me` | `SOURCE_VERIFIED` |
| `AUTH-012` | POST | `/v1/auth/register/profile` | `SOURCE_VERIFIED` |

## OposiBots users admin

Common: Domain `OposiBots user administration`; Authorization `ADMIN_REQUIRED`; Route implementation `OposiBotsUserAdminRoutes.kt`; Service `UNKNOWN`; Gateway `OposiBotsUserAdminGateway`; Request/Response DTO `SOURCE_CODE_OR_UNKNOWN`; Errors `SOURCE_CODE_OR_UNKNOWN`; Tests `OposiBotsUserAdminRoutesTest.kt`; DB/RPC/Storage `Supabase expected, LIVE_NOT_VERIFIED`; Admin UI `UNKNOWN`; OposiBot `NOT_VERIFIED`; Evidence `OposiBotsUserAdminRoutes.kt`, `OposiBotsUserAdminRoutesTest.kt` and backend source.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `BOT-ADMIN-001` | GET/POST | `/api/v1/admin/oposibots/usuarios` | `SOURCE_VERIFIED` |
| `BOT-ADMIN-002` | GET/PATCH/DELETE | `/api/v1/admin/oposibots/usuarios/{id}` | `SOURCE_VERIFIED` |

## Desktop Control Center — additive admin endpoints (2026-09-02)

Common: Authorization `ADMIN_REQUIRED` (enforced from the `AuthenticationChecked`
hook — see `AdminAuthorizationGuard.kt`); Route implementation `AdminV1Routes.kt`;
Gateway `AdminV1Gateway`; Admin UI Desktop.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADM-CAP-001` | GET | `/api/v1/admin/especialidades/{id}/capacidades` | `SOURCE_VERIFIED`, route reachable and auth-protected at runtime (`401` unauthenticated) |
| `ADM-CAP-002` | PUT | `/api/v1/admin/especialidades/{id}/capacidades` | `SOURCE_VERIFIED` |
| `ADM-COM-001` | GET | `/api/v1/admin/comercial/productos` | `LIVE_VERIFIED` — returned real rows from MAIN (`free`, `rafagas`) |
| `ADM-COM-002` | POST | `/api/v1/admin/comercial/productos` | `SOURCE_VERIFIED` (no write executed) |
| `ADM-COM-003` | GET | `/api/v1/admin/comercial/productos/{id}/tarifas` | `SOURCE_VERIFIED` |
| `ADM-COM-004` | POST | `/api/v1/admin/comercial/productos/{id}/tarifas` | `SOURCE_VERIFIED` (no write executed) |

Changed behaviour on existing endpoints:

| Endpoint | Change |
|---|---|
| `GET /api/v1/admin/usuarios` | Accepts `limit`/`offset` (clamped, hard max) and returns `total`/`limit`/`offset` alongside `data`. Additive: existing consumers read `data` and ignore unknown keys. |
| `GET /api/v1/admin/dashboard` | Adds `unavailableMetrics`; numeric fields for listed metrics are serialization filler, not data. |
| `POST /admin/ticket-wallets/{id}/transactions` | Now `410 Gone`. The non-atomic balance write is closed; use `POST /api/v1/admin/tickets/{usuarioId}/transacciones`. Legacy reads unchanged. |

## Desktop Control Center — vertical 2 (2026-09-02)

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADM-USR-010` | GET | `/api/v1/admin/usuarios/{id}/detalle` | `SOURCE_VERIFIED`; the 10 underlying queries each return HTTP 200 against MAIN |
| `ADM-MKT-001` | GET/POST | `/api/v1/admin/market/packs` | `SOURCE_VERIFIED`; `paquetes_opositickets` reachable (HTTP 200, empty) |
| `ADM-MKT-002` | GET/POST | `/api/v1/admin/market/servicios` | `SOURCE_VERIFIED`; `servicios` reachable (HTTP 200, empty) |
| `ADM-MKT-003` | GET/POST | `/api/v1/admin/market/promociones` | `SOURCE_VERIFIED`; `promociones` reachable (HTTP 200, empty) |
| `ADM-MKT-004` | GET/POST | `/api/v1/admin/market/popups` | `SOURCE_VERIFIED`; `reglas_popup_comercial` reachable (HTTP 200, empty) |

Changed behaviour:

| Endpoint | Change |
|---|---|
| `GET /api/v1/admin/tickets/{id}/transacciones` | Adds `balanceBefore`, `balanceAfter`, `originType`, `originId`, `idempotencyKey`. Additive and nullable — the legacy surface has none of them. |
| `POST /admin/ticket-wallets/{id}/transactions` | Now returns `410` regardless of the request body. It previously returned 500 when the body failed to deserialize, because the body was read before the rejection. |
| `GET /v1/examenes`, `GET /v1/examenes/{id}` | Now also require `publicado_en=not.is.null`, matching the authenticated route. Unpublished exams were visible unauthenticated. |
| Any endpoint, malformed JSON body | `400` with a generic message (was `500` leaking the expected internal class name). |
| Any endpoint, upstream failure | Generic message plus the request id; the upstream body (including PostgREST `hint`, which names tables) is logged server-side instead of returned. |

Runtime-verified end to end: the desktop client authenticates with a real admin
JWT and receives 200 from `/api/v1/admin/dashboard`, `/especialidades`,
`/examenes`, `/examenes/{id}/preguntas` and `/reportes`.

## Desktop Control Center — cierre (2026-09-02)

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADM-PLAN-001` | GET/POST | `/api/v1/admin/planes` | `SOURCE_VERIFIED`; `planes_suscripcion` reachable |
| `ADM-PLAN-002` | GET/POST | `/api/v1/admin/planes/{planId}/beneficios` | `SOURCE_VERIFIED`; `beneficios_plan` reachable |
| `ADM-OT-001` | GET | `/api/v1/admin/tickets/resumen` | `SOURCE_VERIFIED`, registered and 401-protected |
| `ADM-OT-002` | POST | `/api/v1/admin/tickets/conceder` | `SOURCE_VERIFIED`; delegates entirely to `admin_grant_tickets`, no write executed |
| `ADM-SYS-001` | GET | `/api/v1/admin/estado-sistema` | `SOURCE_VERIFIED`, registered and 401-protected. Closes the aggregation contract Screen 10 was waiting on |

Changed behaviour:

| Endpoint | Change |
|---|---|
| `POST /api/v1/admin/usuarios/{id}/tickets` | The client now sends `idempotencyKey`. It previously did not, so this endpoint could only ever return 422 — granting tickets from the panel was impossible. |

Client-side rule now enforced: the idempotency key is minted when the grant
dialog opens and held for the whole operation, so retries and double clicks reuse
it. A key generated per attempt would satisfy the endpoint and still allow a
double grant.

## Dashboard V2 — contrato agregado (2026-09-10)

Bajo `authenticate("gateway-auth")`, sólo lectura. Implementado en
`AdminDashboardOverview.kt` con gateway propio de superficie mínima, siguiendo el
precedente de `AdminAnalytics`/`AdminRankings` y no `AdminV1Gateway`.

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADM-DASH-001` | GET | `/api/v1/admin/dashboard/overview[?sections=]` | `SOURCE_VERIFIED` / `TEST_VERIFIED`; `LIVE_VERIFIED: NO` |

Forma del contrato:

- Seis franjas: `system`, `attention`, `pulse`, `content`, `activity`, `economy`.
- `?sections=` es CSV opcional, case-insensitive. Omitido = todas. Una sección
  desconocida responde **400** nombrando las válidas, no la ignora en silencio.
- **Franja ausente ≠ franja fallida.** Ausente = no se pidió; fallida = se pidió y
  Ktor no pudo componerla, y entonces aparece nombrada en `failedBlocks`. Nunca
  hay 500 porque una franja se rompa.
- `system.health` incluye `supabaseLatencyMs: Long?` — milisegundos de la sonda a
  Supabase, presente **sólo si la sonda se completó**. No es la latencia general
  de la API, que sigue declarada en `notInstrumented` como `apiLatencyMs`.
- `activity` incluye `droppedEntries: Int` — filas de `auditoria_log` leídas y no
  entregadas por `creado_en` ilegible o `id` vacío. Existe para que «no pude leer
  nada» no se lea como «no ha pasado nada». Una entrada exige id no vacío y fecha
  interpretable; sin ambas, se descarta y se cuenta.
- `ACTIVITY_LIMIT = 8` es deliberado y está protegido por test: cubre las 5 filas
  que pinta el panel más holgura para descartes, que ocurren **después** del
  `limit` de PostgREST.
- Los campos numéricos no llevan valor por defecto y el `Json` del servidor usa
  `explicitNulls = false`, así que **un campo ausente no significa cero**: la
  autoridad es siempre `unavailableMetrics`, nunca la presencia del campo.
- `grossRevenueCents` y `ticketsInCirculation` viajan siempre `NOT_INSTRUMENTED`
  con la vista concreta que haría falta (requieren `SUM`).

Métrica nueva de producto: `subscriptionsExpiring7d` (`suscripciones`,
`estado=eq.activa` y `fin_en` acotado por los dos extremos), distinta del
horizonte de contexto `subscriptionsExpiring30d` que ya existía en analítica.

Cambio de consumidor: `GET /api/v1/admin/estado-sistema` pasa de
`AVAILABLE_NOT_USED` a **consumido** por la pantalla de Estado del sistema
(`KtorSystemStatusRepositoryImpl`). `GET /api/v1/admin/dashboard` no se retira:
sigue alimentando el Home móvil.

Protección de contrato: las 17 claves de métrica y los 6 nombres de franja están
duplicados entre `:domain` y `:backend:ktor` —el backend no depende del dominio a
propósito— y se fijan con **dos tests espejo de literales escritos a mano**,
verificados por mutación en ambos lados.

## Knowledge-loss check

| Family | Result |
|---|---|
| `/content/*` | `PRESERVED` |
| `/v1/*` | `PRESERVED` |
| `/api/v1/admin/*` | `PRESERVED` |
| `/api/v1/app/*` | `PRESERVED` |
| `/v1/auth/*` | `PRESERVED` |
| OposiBots admin users | `PRESERVED` |
| `/health/*` | `PRESERVED` (`/health` unchanged; `/health/live` and `/health/ready` new, additive) |

Preserved means every family and route grouping from the forensic source is represented here. It does not mean runtime/live compatibility is verified.

## Rankings y Analítica (2026-09-02)

Todas bajo `authenticate("gateway-auth")`, verificadas registradas y con 401.

| Endpoint | Method | Path |
|---|---|---|
| `ADM-RNK-001` | GET | `/api/v1/admin/rankings/ligas` |
| `ADM-RNK-002` | GET | `/api/v1/admin/rankings/global` |
| `ADM-RNK-003` | GET | `/api/v1/admin/rankings/liga/{ligaNivelId}` |
| `ADM-RNK-004` | GET | `/api/v1/admin/rankings/usuario/{usuarioId}` |
| `ADM-RNK-005` | GET | `/api/v1/admin/rankings/usuarios?q=` |
| `ADM-ANL-001..006` | GET | `/api/v1/admin/analytics/{producto,estudio,retencion,comunidad,economia,marketing}` |

Rankings es **solo lectura por construcción**: el gateway no tiene ningún método
de escritura, y un test lo impone.

Analítica devuelve, además de los valores, `unavailableMetrics` (la unión de lo no
disponible) y `notInstrumented` (el subconjunto que nombra el read model concreto
que falta). Los campos numéricos no llevan default: una métrica no disponible
viaja como `null` explícito.

## Exámenes V2 — Información básica (2026-09-11)

| Endpoint ID | Method | Path | Status |
|---|---|---|---|
| `ADMIN-039` | DELETE | `/api/v1/admin/examenes/{id}/preguntas/{preguntaId}` | `SOURCE_VERIFIED` — desvincula la pregunta del examen; **no** la archiva (archivar es `DELETE /api/v1/admin/preguntas/{id}`) |

Changed behaviour:

| Endpoint | Change |
|---|---|
| `PATCH /api/v1/admin/examenes/{id}` (`ADMIN-002`) | Merge-patch (RFC 7396) **sólo** en `comunidad_id`, `anio` y `duracion_minutos` (lista cerrada `CLEARABLE_EXAMEN_COLUMNS`, `AdminV1Routes.kt`). Si la clave llega con `null`, la columna pasa a NULL; si la clave no llega, la columna no se toca. Antes, un `null` se omitía al reenviar a PostgREST y la columna no cambiaba: pasar un examen a Nacional, o vaciar el año o la duración, no se guardaba. El resto de columnas conserva el comportamiento anterior. `total_preguntas`, `portada_*` y `publicado_en` no se tocan aunque lleguen con `null`. Un cuerpo inválido devuelve el mismo `400` genérico. El único cliente que envía siempre las tres claves es el PATCH del editor de examen. El guardado de ráfaga no las envía, así que no las borra (ver EXAM-001 en `../registry/RISKS.md`). |

El merge-patch está cubierto por `AdminV1ExamQuestionsTest`, con mutación. Ninguna de las dos rutas se ha ejercitado contra un Ktor en marcha: `LIVE_DATA_RUNTIME: NOT_VERIFIED`.
