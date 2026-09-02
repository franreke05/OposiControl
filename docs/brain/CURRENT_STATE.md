# Current State

Database Redesign V1 closure: controlled MAIN migrations 012, 013 and 014
are applied. The duplicate attempt index was removed; anonymous mutation
grants and mutation grants on public derived views were revoked. No table,
row, Storage object or legacy compatibility adapter was removed.

## Operational dashboard — 2026-08-21

| AREA | STATUS | CURRENT FACT | REFERENCE |
|---|---|---|---|
| Database | VERIFIED | Product Metrics/wallet migrations 002–010; canonical writers live | ../database/MIGRATION_STATE.md |
| Backend | VERIFIED / BACKEND_PASS | Ktor owns service-role mutations through canonical RPCs | ../backend/SERVICE_MAP.md |
| Authenticated JWT E2E | BLOCKED_EXTERNAL | No valid non-personal Auth fixture is available locally | ../registry/RISKS.md |
| Payments | WAITING_EXTERNAL_PROVIDER | Checkout/webhook provider contract is absent | ../product/COMMERCIAL_CODE_CONSUMER_MATRIX.md |
| Product policy | WAITING_PRODUCT_DECISION | Eight Product Metrics policy decisions remain open | ../product/CLIENT_DECISIONS_REQUIRED.md |
| UI runtime | M0-M1 IMPLEMENTED / NOT_VERIFIED | Mobile shell/Home and authenticated error states compile; Screens 01–10 are integrated and navigable from one app (`integration/mobile-finish`); physical/emulator runtime and valid admin JWT E2E remain unverified (no ADB device connected in any session so far) | ../engineering/TESTING.md |
| Desktop/JVM foundation | FIXED | JVM config now falls back to `.env` when Gradle-injected properties absent (verified with automated tests); desktop SQLite DB moved to per-OS app-data directory with best-effort legacy-file migration and unconditional parent-directory creation (fixes fresh-install crash, verified post-fix); Reports/Forum/Thread Detail/OposiTester/Content Upload now render on Desktop (`compactLayout=false`) via pure `resolveContentTarget()` resolver (unit-tested, mobile unchanged); Store (`tienda`) admin operations moved from direct Supabase (anon key + user JWT) to canonical Ktor boundary (`/api/v1/admin/tienda/*` with gateway auth). Final whole-branch review (post per-task reviews) found and fixed two cross-task defects: Store DTO wire-contract mismatch (backend response `Json` omits defaulted fields, client row DTOs required them with no default → `MissingFieldException` on ordinary rows) and a stale `AdminSection.MODERATION` reverse-mapping still pointing at `Community.OposiRed` after it was repointed to render `ForumScreen` (now `Moderation.Queue`, round-trip restored). Build suite: `composeApp:jvmTest` 37/37, `core:database:jvmTest` 9/9, `data:jvmTest` 3/3, `backend:ktor:test` 87/87 (136 total, 0 failures). Desktop window rendering/Login→Settings click-through NOT_VERIFIED (no native window screenshot capability in sandbox). NOT_VERIFIED: whether the repo's real `SUPABASE_URL`/`SUPABASE_ANON_KEY`/`BASE_URL` in `.env` are valid working credentials — the runtime-verification process inherited stale/placeholder values from a pre-existing long-lived Gradle daemon instead of picking up the repo's own `.env`, so the `.env`-fallback path was never actually exercised end-to-end at runtime (sandbox/daemon-reuse artifact, not a defect in the shipped config-resolution code, which has its own passing unit tests). | ../architecture/adr, `AppRoot.kt`, `AdminV1Routes.kt` |
| OposiTester mobile (Screen 06/07) | SCREEN_06 PASS / SCREEN_07 PASS_WITH_RUNTIME_DATA_PENDING | Lista recuperada y detalle implementado, integrado en `integration/mobile-finish`. `GET /api/v1/admin/feedback/preguntas/{id}` aditivo; acciones reales resolver/descartar sobre el contrato `.../resolver` existente. P1: verificación visual en runtime pendiente (ver RISKS.md UI-001). P2: contexto de examen no resuelto (relación M:N ambigua) y estado "revisado" no expuesto como acción propia. | ../engineering/TESTING.md |
| Screen 10 — Estado del sistema | IMPLEMENTED / NOT_CONNECTED | `AppRoute.System.Status` (no `Diagnostics`) integrado, opera con `NotConnectedSystemStatusRepository` — UI real preparada para overall status/Ktor/Supabase/worker/CPU/RAM/latency/errors/crashes/queue/updatedAt, sin backend real todavía y sin métricas fake. Bloqueada en `NOT_CONNECTED` hasta que exista el endpoint de agregación en Ktor. | ../product/SCREEN_REGISTRY.md |
| Backend observability foundation | INTEGRATED / PASS | Request correlation (`X-Request-Id` + MDC), logging estructurado, taxonomía de eventos estratégicos, `StatusPages` centralizado, `/health/live` y `/health/ready` (chequeo real de Supabase sin query pesada), umbral de slow-request configurable, redacción reutilizada. Integrado en `integration/mobile-finish`. Sentry/Prometheus: preparación de config solamente, sin SDK/infra real — ver filas siguientes. | ../backend/Observability.kt |
| HTTP timeouts (P1 cerrado) | FIXED | Los 7 `HttpClient` de Supabase (Admin, AdminV1, News, V1, TagSearch, App, Content/legacy y el verificador JWT) tenían `HttpClient.newHttpClient()` sin timeout — confirmado, ninguna llamada podía colgar el hilo de la request indefinidamente. Corregido: `connectTimeout` a nivel cliente + `.timeout()` por request, ambos configurables (`UPSTREAM_CONNECT_TIMEOUT_MS`, `UPSTREAM_REQUEST_TIMEOUT_MS`), con test que reproduce el escenario real. | ../backend/Observability.kt |
| Import atomicity + duplicados (P1 abierto) | DESIGNED / APPLY_PENDING_APPROVAL | `importarLote` real sigue sin ser atómico (bucle de REST calls por fila, puede quedar `parcial`) y sin detección de duplicados — deuda confirmada, NO cerrada en runtime. RPC candidata `admin_import_lote_preguntas` diseñada (transacción única, todo-o-nada, duplicados marcados para revisión — nunca auto-skip/auto-replace) pero SIN aplicar a ninguna base de datos: se escribió sin acceso al esquema real de Supabase en esta sesión, y la llamada al RPC atómico existente `admin_create_question` queda como TODO pendiente de confirmar su firma real antes de activarla. La ruta activa sigue siendo la actual (documentado el riesgo, no ocultado). | ../../supabase/canonical/content_import_atomicity_candidate_v1/README.md |
| Sentry | SENTRY_CONFIGURATION_PENDING | Backend: lectura de `SENTRY_DSN`/`SENTRY_ENVIRONMENT`/`SENTRY_RELEASE` preparada (`ObservabilityConfig`), puntos de integración documentados (`logStrategicEvent` + los 4 handlers de `StatusPages`); SDK NO añadido. Mobile (KMP): no se tocó en esta tarea. Sin DSN disponible en el entorno; el servicio Sentry reportó una incidencia el mismo día — no bloqueante. | ../backend/Observability.kt |
| Prometheus/Grafana | NOT_READY (documented) | Sin infraestructura real ni SDK. Puntos de instrumentación futuros documentados (request count/latencia p50-p99, 4xx/5xx, `/health/ready` como gauge, JVM). Contrato: logs/Sentry/Prometheus/health → futuro endpoint de agregación EN Ktor → Screen 10 (Screen 10 nunca lee logs/Prometheus directamente). | ../backend/Observability.kt |

## Current architecture

ARTURO Compose Multiplatform client → domain/data → backend/ktor → Supabase service-role boundary. OposiBot is an external consumer boundary; its /v1/auth/* contract and public.usuario adapter remain protected.

Canonical backend writers: record_answer, record_event, admin_grant_tickets.

Authorization model: Ktor distinguishes superadmin/admin from moderador; canonical `/api/v1/*` routes require JWT role authorization, and static-token use is limited to local legacy compatibility surfaces.

Mobile rebuild M0-M1: verified JWTs are passed as Bearer tokens to Ktor; admin routes return 403 for a valid JWT without a canonical `admin`/`superadmin` role. Home mobile consumes the existing real dashboard repository and exposes loading, empty, error, offline and retry states. Runtime certification remains NOT_VERIFIED.

Canonical exam vocabulary is rafaga / razonado / inmersivo; broader app consumers and legacy aliases require compatibility review before removal.

## Mobile content upload — Screen 09

Status: `PASS_WITH_GAPS` — integrated into `integration/mobile-finish`.

The mobile `AppRoute.Content.Imports` flow: authenticated PDF upload to staging, review/edit, approve or skip, and explicit publish through the existing admin import boundary. PDF processing is digital/selectable-text only through PDFBox; OCR is intentionally unsupported. The flow does not create Storage buckets, migrations, RPCs, or MAIN seed data. Existing legacy `/api/v1/admin/rafagas/import` remains separate.

Implemented scope: specialty/exam selection, lote creation, multipart PDF upload, 20 MB limit, PDFBox extraction, staging, editing/review, approval, skip and explicit publication for `rafaga`, `razonado` and `inmersivo`.

The implementation still reports partial publication when row-level import errors or skipped staging rows exist — **confirmed, not yet closed in runtime**. No live MAIN write was executed while implementing or validating this branch; an explicit user publish action can write final content through the existing admin boundary. A candidate fix (atomic RPC + duplicate-candidate detection) is designed and tracked, `APPLY_PENDING_APPROVAL`, not applied to any database and not wired into the active route — see `../../supabase/canonical/content_import_atomicity_candidate_v1/README.md`. Asynchronous worker processing, resource association for immersive content, live Supabase/RLS compatibility, and runtime visual verification remain open evidence gaps.

- Branch/commit history: originally `feature/mobile-content-upload` (`9eea7da`, `3c09713`), merged into `integration/mobile-finish`.
- Backend/data: `POST /api/v1/admin/importaciones/preguntas/pdf` creates reviewable staging through Ktor; existing list, detail, staging PATCH and publish endpoints remain the integration boundary.
- Verification: full suite green post-integration (`:backend:ktor:test`, `:domain:jvmTest`, `:data:jvmTest`, `:core:ui:jvmTest`, `:features:reports:jvmTest`, `:features:forum:jvmTest`, `:features:opositester:jvmTest`, `:features:settings:jvmTest`, `:features:exams:jvmTest`, `:composeApp:jvmTest`, `:composeApp:assembleDevDebug`, `git diff --check`). No ADB device was connected in any session so far, so runtime remains `NOT_VERIFIED`.
- Priority state: P0 none; P1 atomic publication + duplicate detection (designed, `APPLY_PENDING_APPROVAL`, see candidate migration), live Supabase/RLS compatibility and runtime/JWT E2E; P2 asynchronous worker and immersive-resource association.
- CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: `NO`.
- Database/infra state: `DATABASE_CHANGES: NONE`; `MAIN_WRITES: NONE`; `MIGRATIONS: NONE (one LOCAL candidate, not applied)`; `STORAGE_NEW_BUCKETS: NONE`.
- Exact next step: a session with live Supabase schema access reviews and (if correct) applies `content_import_atomicity_candidate_v1/001_admin_import_lote_preguntas.sql`, confirms the real `admin_create_question` signature, then a small Kotlin change swaps the active route to call the new RPC. Runtime verification on any available emulator/device with a non-production admin JWT remains separately open.

## Mobile integration — final (2026-08-27)

All 10 required mobile screens (Home, Informes, Detalle informe, Foro, Detalle hilo, OposiTester, OposiTester Detail, Configuración, Subir contenido, Estado del sistema) are merged into `integration/mobile-finish`, along with the backend observability foundation. Four previously separate branches (`feature/backend-observability-foundation`, `feature/mobile-content-upload`, `recovery/mobile-opositester-06-07`, `feature/mobile-settings`) were merged with real conflicts resolved semantically (never blind ours/theirs) — see git log on `integration/mobile-finish` for the four merge commits plus follow-up fixes (HTTP timeout P1, a duplicate-test-name compile error left by two independent merges touching `AppNavigatorTest.kt`, Sentry/Prometheus prep). `git diff --check` clean; full requested test suite green; Android `assembleDevDebug` green; JVM/desktop target compiles. Runtime visual verification remains `NOT_VERIFIED` (no ADB device available in any session in this project so far). `OposiControl-Claude` (uncommitted, superseded by the recovery branch) and `OposiControl-Codex` (uncommitted, beyond `feature/mobile-settings`'s `9efd31b`) worktrees were left untouched, per instruction — they are not required for this integration and were not merged from their uncommitted state.

## Desktop Control Center — vertical 1 (2026-09-02)

Branch `feature/desktop-control-center`, based on `phase0/jvm-desktop-foundation`
(a strict superset of `integration/mobile-finish`; the JVM foundation work was
verified present and green before building on it, not assumed).

**First session in this project with live runtime evidence.** Ktor was started
from the branch with the repository's real `.env` and answered
`/health/ready` → `{"ktor":"up","supabase":"up"}`, so the repository's Supabase
credentials are confirmed working. This closes the long-standing
`NOT_VERIFIED` note about them — and corrects its stated cause: the placeholder
`another-modified-url.supabase.co` was not Gradle-daemon reuse but a **stub
`.env` inside the git worktree** (3 keys, placeholder URL, no service-role key).
Worktrees do not inherit the untracked `.env`, so anything run from one silently
gets broken configuration. The real `.env` was copied into the worktree (it is
gitignored; nothing was committed).

| AREA | STATUS | FACT |
|---|---|---|
| Admin authorization | `FIXED / RUNTIME_VERIFIED` | Both admin authorization rules were **written but never enforced**: they lived in an `intercept(ApplicationCallPipeline.Plugins)` inside the `authenticate` block that never rejected. With the server up, the static gateway token reached `GET /api/v1/admin/comercial/productos` and returned real rows. The pre-existing test passed throughout because it asserted only the path predicate, never a refusal. Moved to a route-scoped plugin on Ktor's `AuthenticationChecked` hook. Verified live: canonical+static `200 → 401`; legacy+static `200` (compatibility kept); no token `401`; `/health/ready` `200`. |
| Legacy wallet write | `CLOSED / RUNTIME_VERIFIED` | `POST /admin/ticket-wallets/{id}/transactions` did `saldo + amount` then a separate ledger insert — lost updates under concurrency, divergent balance/ledger on partial failure, no idempotency key, no `saldo_antes/después`. Now `410` (verified live), pointing at the canonical atomic `admin_grant_tickets`. Not auto-translated: legacy integer ids vs `auth.users` uuid would move tickets into the wrong wallet. Legacy reads untouched. |
| Admin dashboard | `FIXED` | Every metric ended in `catch { 0 }`, so "could not read" and "genuinely zero" were indistinguishable; counts fetched all rows (`select=id` + `.size`), silently truncated by PostgREST `max-rows`. Now `Prefer: count=exact` + `Range: 0-0`; error rate from two exact counts instead of an arbitrary 5000-row sample. Metrics needing DISTINCT/AVG/GROUP BY are reported in `unavailableMetrics` and rendered `NO_DATA`. |
| Specialty capabilities | `IMPLEMENTED` | `GET`/`PUT /api/v1/admin/especialidades/{id}/capacidades` over the existing `capacidades_especialidad`. No new schema. Missing row returns schema defaults flagged `configurado=false` (distinct from "configured with everything off"); missing table returns `SPECIALTY_CAPABILITIES_NOT_PROVISIONED`, never "all modes disabled". A specialty left with zero exam modes requires a limitation message. Desktop section with its own save. |
| Commercial catalog | `IMPLEMENTED / LIVE_READ_VERIFIED` | `GET`/`POST /api/v1/admin/comercial/productos` and `.../{id}/tarifas`. No new schema: `productos_comerciales` already had every field required to be configurable, and `tarifas_producto_comercial` already models `periodo_facturacion` × `fase_precio` (first-period vs renewal). **`productos_comerciales` is now `LIVE_VERIFIED`**: the endpoint returned real rows (`free`, `rafagas`, `tipo_producto=suscripcion`). Money is integer cents end to end. Desktop screen at `Commercial.Catalog`. |
| Users listing | `FIXED` | Was `limit=200` fixed, and enriched each page by fetching **every** row of `roles_usuario` and `carteras_usuario`. Now `limit`/`offset` with a hard cap, `total` nullable (unknown ≠ zero), and enrichment filtered to the page's ids. |

Tests: 151 backend (was 87), plus domain/data/composeApp/features suites — all
green. `git diff --check` clean.

Not verified: Desktop **visual** click-through. The JVM app launches cleanly with
correct configuration and no startup crash (so the Koin graph resolves), but this
environment cannot capture or drive a native window, so screen-by-screen visual
confirmation of the new panels remains open — as does any flow needing a real
admin JWT, which was not available.

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE` — every live call made was a read
or an authorization probe; no administrative write was executed.

## Desktop Control Center — vertical 2 (2026-09-02)

### Corrección importante del registro anterior

La sesión previa declaró `nota media`, `liga`, `ranking`, `evolución`, `racha`,
popup rules, marketing y consumibles como `NOT_INSTRUMENTED`. **Era incorrecto.**
Se dedujo de `estadisticas_usuario` en solitario, más documentación
desactualizada y un fixture de dry-run desechable. Una auditoría del esquema
**en vivo** (PostgREST OpenAPI, sólo lectura) devuelve **90 tablas**, y esas
métricas viven en tablas propias:

| Necesidad de producto | Dónde vive realmente |
|---|---|
| Nota media, evolución, ventana de cálculo | `estado_competitivo_usuario.nota_media_actual`, `nota_referencia_evolucion`, `evolucion_nota`, `preguntas_desde_ultima_evolucion`, `ventana_calculo` |
| Liga y ranking | `estado_competitivo_usuario.liga_nivel_id`, `ranking_liga_posicion`, `ranking_global_posicion`, `percentil_global`; catálogo en `ligas_nivel`, `ligas`, `ranking_liga` |
| Racha y racha máxima | `rachas_usuario.racha_actual_dias`, `mejor_racha_dias` |
| Perfil ampliado (edad, ciudad, franja…) | `perfiles_usuario_detalle` — contiene todos los campos del PDF |
| Debilidades por etiqueta | vista `v_usuario_debilidades_etiqueta` (ya existe) |
| Dificultad de preguntas | vista `v_preguntas_dificultad_global` (ya existe) |
| Reglas de pop-up | `reglas_popup_comercial` (17 columnas: disparador, pantalla, momento, plan mínimo, frecuencia, prioridad) |
| Consumibles | `servicios` + `activaciones_servicio_usuario` |
| Packs OT | `paquetes_opositickets` |
| Sesiones y tiempo de uso | `sesiones_estudio` |
| Marketing/atribución | `atribucion_usuario`, `invitaciones_referido`, `eventos_conversion_premium` |

Existen además 5 RPC de rollup (`recalcular_estadisticas_*`), que es el patrón
correcto para agregados y evita calcularlos por petición.

**`ligas_nivel` está sembrada exactamente con el modelo documentado**: Liga 50
(ventana 50) y 200/500/800/1000 (ventana 100). Las ligas ya son configuración
data-driven: no hacen falta ni decisión de producto ni migración.

Estado real de los datos: el esquema existe pero la mayoría de tablas están
**vacías** (`SCHEMA_EXISTS_NOT_POPULATED`). Con filas: `perfiles_usuario` (3),
`carteras_usuario` (2), `transacciones_cartera` (16, todas `ajuste`/`admin`),
`suscripciones` (1), `ligas_nivel` (5), `productos_comerciales`.

### Revisión de seguridad independiente

Un revisor que no implementó el código confirmó el arreglo de autorización
(**117/117** sondas canónicas devuelven 401; los intentos de bypass por
normalización de rutas, codificación y cabeceras fallaron todos) y confirmó que
existe **una sola** vía de mutación de saldo en todo el repositorio, a través del
RPC atómico e idempotente. Encontró además defectos reales, todos corregidos y
verificados en ejecución:

| ID | Sev | Defecto | Estado |
|---|---|---|---|
| F1 | P1 | `isLocalDevelopmentPeer` recibía `remoteHost`, que Netty deriva por **DNS inverso**: con un PTR propio hacia un dominio con A `127.0.0.1`, una petición remota pasaba por loopback. Aceptaba además toda la RFC1918. | FIXED — sólo IP literal de socket, sólo loopback |
| F2 | P1 | Las pruebas nuevas repetían el error que dejó pasar el bug original: comprobaban la función pura, no el cableado. | FIXED — `AdminAuthorizationWiringTest`, 11 casos por HTTP real |
| F3 | P1 | El 410 del wallet se saltaba por orden de evaluación (`call.receive()` primero) → 500 con cuerpos mal formados. Causa raíz más amplia: `BadRequestException` no extiende `IllegalArgumentException`, así que **cualquier** JSON inválido daba 500 filtrando el nombre de clase interna. | FIXED — la ruta no lee el cuerpo; handler 400 añadido |
| F4 | P2 | El guard fallaba **abierto** ante un principal de tipo inesperado. | FIXED — falla cerrado |
| F5 | P2 | `/dashboard/metrics`, `/tags`, `/search` pasaron a 401 con token estático. | INVESTIGADO — no es regresión: el cliente usa rutas canónicas y `/search` ya estaba desactivado en cliente |
| F7 | P2 | `/v1/examenes` público sin filtro de publicación: exámenes sin publicar visibles sin autenticar. | FIXED |
| F8 | P2 | Los cuerpos de error upstream se devolvían literales; el `hint` de PostgREST es un oráculo de esquema, alcanzable sin autenticar. | FIXED — se registra, se devuelve mensaje genérico + request id |
| F9 | P2 | `hasAdminRole` añadía dos viajes upstream sin caché por petición admin. | FIXED — caché concurrente, TTL 30 s |

**Pendientes registrados, no corregidos** (ver RISKS.md): F6 (la allow-list legacy
es un prefijo e incluye operaciones destructivas), F10 (instalar
`XForwardedHeaders` reabriría F1), F11 (las denegaciones se registran como fallo
de login), F12 (el token estático viaja compilado en la APK), y la posible
existencia de un overload legacy de 4 argumentos de `admin_grant_tickets` sin
clave de idempotencia.

### Entregado en esta vertical

| Área | Estado | Hecho |
|---|---|---|
| User detail | `IMPLEMENTED` | `GET /api/v1/admin/usuarios/{id}/detalle`: perfil, perfil ampliado, rendimiento, estado competitivo, racha, economía, suscripciones, OposiTester y debilidades. Secciones nullable con tres estados distintos (dato / NO_DATA / NO_DISPONIBLE). Las 10 consultas verificadas HTTP 200 contra la base real. |
| OT ledger | `ENRICHED` | Expuestos `saldo_anterior`, `saldo_resultante`, `referencia_tipo`, `referencia_id`, `idempotency_key`. |
| Market | `IMPLEMENTED` | `GET/POST /api/v1/admin/market/{packs,servicios,promociones,popups}` + pantalla Desktop de cuatro secciones. Sin esquema nuevo; las cuatro consultas verificadas HTTP 200. |

### Runtime verificado

Con Ktor real contra la base de datos, la app Desktop **autentica y obtiene 200**
en rutas canónicas: `/api/v1/admin/dashboard`, `/especialidades`, `/examenes`,
`/examenes/{id}/preguntas`, `/reportes`. Confirma la cadena completa
Desktop → Ktor → Supabase **con autorización aplicada**, y que el arreglo de
seguridad no rompe el cliente (presenta un JWT admin real, no el token estático).

Dos 500 observados son deriva legacy preexistente, no introducida aquí:
`/api/v1/admin/tienda/articulos` y `/content/noticias` apuntan a tablas
(`articulo_tienda`, `noticia`) que no existen en la base viva.

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE` — todas las llamadas en vivo fueron
lecturas, conteos o sondas de autorización.

## Desktop Control Center — cierre (2026-09-02)

### Verticales cerradas en esta fase

| Área | Estado | Nota |
|---|---|---|
| Planes de suscripción | `IMPLEMENTED` | `GET/POST /api/v1/admin/planes` y `.../{id}/beneficios` sobre `planes_suscripcion` + `beneficios_plan`. Modelo EAV: se exige **exactamente un** valor por beneficio (sin valor no dice nada; con dos, gana el que lea primero el consumidor). `false` y `0` cuentan como valor presente — son decisiones explícitas. Los precios NO están en el plan: viven en `tarifas_producto_comercial` por periodo y fase, y hay un test que lo defiende. |
| Concesión admin de OT | `IMPLEMENTED` | `POST /api/v1/admin/tickets/conceder`. No escribe saldo: delega entero en `admin_grant_tickets`. Clave de idempotencia y motivo obligatorios, cero rechazado, negativo permitido (corregir es legítimo), tope por operación como red contra el dedo gordo, y una recompensa de reporte no puede ser negativa. |
| Resumen OT | `IMPLEMENTED / PARCIAL_HONESTO` | Sólo conteos exactos server-side. OT en circulación / emitidos / consumidos exigen SUM, que PostgREST no expresa; se declaran no disponibles en vez de sumarse trayendo filas (se recortaría en `max-rows` y daría un total menor con pinta de correcto). |
| Estado del sistema | `CONNECTED (parcial)` | `GET /api/v1/admin/estado-sistema`. Cierra el contrato que Screen 10 esperaba. Informa de Ktor y Supabase (lectura mínima cronometrada); el resto se declara `notInstrumented` con `null`, no con `0`. Lo desconocido no pinta verde, y tampoco degrada el global — si no, nadie mira el color. |
| Shell / IA | `REDESIGNED` | De diez grupos (varios con un único elemento) a ocho: Dashboard, Contenido, Usuarios, Economía, Comunidad, Analítica, Operaciones, Sistema. Reports y OposiTester dejan de ser grupos propios de escritorio y pasan a Comunidad, **sin dejar de ser destinos de primer nivel en móvil**: `mobilePrimaryNavigation` ahora selecciona por ruta y no por grupo, justamente para que reorganizar el escritorio no toque la barra inferior. |
| Empaquetado Desktop | `PASS` | `packageDistributionForCurrentOS` genera `OposiControlAdmin-1.0.0.msi` (86 MB). Sin firmar: no hay certificado, y eso es `EXTERNAL_PENDING`, no un P1. |

### P1 encontrado y corregido: conceder tickets era imposible desde el cliente

`GrantTicketsBody` llevaba sólo `cantidad` y `motivo`. El backend exige clave de
idempotencia y responde 422 sin ella, así que la acción "conceder OposiTickets"
de la pantalla de usuarios **nunca pudo funcionar**.

El arreglo no es sólo mandar una clave: una generada al pulsar contentaría al
backend y seguiría permitiendo que un doble clic concediese dos veces. La clave
se acuña **al abrir el diálogo** y se conserva durante toda la operación, de modo
que reintentos y dobles clics reutilizan la misma; al cerrar se limpia, para que
la siguiente concesión legítima no choque con la anterior y desaparezca en
silencio.

### Import atomicity — candidato corregido

El candidato `001` se escribió sin acceso al esquema y delega en
`admin_create_question`, que **no existe** (lista de RPC verificada en vivo). Es
decir, nunca pudo aplicarse.

`002_admin_import_lote_preguntas_real_schema.sql` lo sustituye, escrito contra el
esquema verificado. Documenta además un problema peor que "lote parcial": una
pregunta ocupa **cuatro tablas** (`preguntas`, `opciones_pregunta`,
`soluciones_pregunta`, `preguntas_examen`), así que el bucle REST actual puede
dejar **preguntas malformadas** —opciones sin solución, o pregunta sin opciones—,
que es contenido corrupto servido a usuarios reales, no sólo una importación
incompleta.

La función corre en una transacción; aborta si detecta candidatos a duplicado
(mismo título normalizado en la misma especialidad) y los devuelve, de modo que
decide una persona; nunca auto-omite ni auto-reemplaza. No introduce ningún valor
nuevo en `estado_parseo`, porque el CHECK de esa columna es desconocido y un valor
inventado rompería la inserción.

`IMPORT_ATOMICITY: BLOCKED_ONLY_BY_DATABASE_APPLY_APPROVAL`.
`DATABASE_APPLY_APPROVAL_REQUIRED: supabase/canonical/content_import_atomicity_candidate_v1/002_admin_import_lote_preguntas_real_schema.sql`

### Externo pendiente

- **Sentry**: no hay `SENTRY_DSN` disponible en el entorno. Los puntos de
  integración siguen preparados. `EXTERNAL_PENDING`, no P1.
- **Prometheus/Grafana**: sin infraestructura. `EXTERNAL_PENDING`.
- **Firma del instalador**: no hay certificado. El MSI se genera sin firmar.
- **JWT admin no-admin para E2E**: sigue sin fixture (RISK-022); la distinción
  401/403 está cubierta por test de integración HTTP, no en vivo.

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE`.

### Rankings y Analítica (2026-09-02)

Ambas verticales se construyeron con revisión independiente y se integraron en el
shell.

**Rankings** (`/api/v1/admin/rankings/*`, módulo `features:rankings`). Solo
lectura de extremo a extremo: el gateway no expone ningún método de escritura y
hay un test que lo impone — una clasificación que se puede ajustar a mano deja de
significar algo. `RankingPageRequest` no es `data class` a propósito: un `copy()`
generado permitiría saltarse el tope de página. `percentil_global` se pinta
siempre con la etiqueta explícita de indicador comparativo interno, nunca como
probabilidad de plaza. Verificado en vivo: `ligas_nivel` coincide exactamente con
el modelo documentado; `estado_competitivo_usuario` y `ranking_liga` están vacías,
así que la pantalla muestra un cero real en vez de inventar clasificación.

**Analítica** (`/api/v1/admin/analytics/*`, módulo `features:analytics`). 63
métricas reales y 22 `NOT_INSTRUMENTED`, cada una respondiendo con el read model
concreto que necesita (AVG, SUM, GROUP BY, o percentil sobre diferencia de
timestamps). Distingue dos clases de "sin número": `unavailableMetrics` (la unión)
y `notInstrumented` (el subconjunto con su hueco nombrado). Los campos numéricos
no tienen default, de modo que una métrica no disponible viaja como `null`
explícito y no puede volver a rellenarse con cero aguas abajo.

Detalles que conviene no perder:
- Los usuarios activos se cuentan sin `DISTINCT` porque `rachas_usuario.usuario_id`
  es la clave primaria: una fila por usuario significa que contar filas **es**
  contar usuarios.
- La distribución por liga es un conteo exacto por fila de `ligas_nivel`, acotado
  por un catálogo de cinco filas y no por el volumen de usuarios.
- Las particiones no pierden filas en silencio: los intentos llevan un bucket
  derivado "otros" donde afloran las filas con vocabulario legacy, los movimientos
  de cartera se parten por el signo de `cantidad` y no por el texto de `tipo`, y
  las suscripciones tienen bucket para estados fuera de los cinco conocidos.
- Se comprobó la garantía de honestidad por mutación: reintroducir `catch { 0 }`
  hizo fallar cinco pruebas de inmediato.

### Estado final

`TESTS: 638, 0 fallos` (backend 280). `BUILD: verde`.
`DESKTOP_PACKAGE: OposiControlAdmin-1.0.0.msi` generado.
`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE`.

Rutas nuevas verificadas en ejecución (registradas y protegidas con 401):
`/api/v1/admin/{planes, tickets/resumen, tickets/conceder, estado-sistema,
rankings/*, analytics/*, market/*, comercial/*, especialidades/{id}/capacidades,
usuarios/{id}/detalle}`.

## Preserved boundaries

OposiBot, public.usuario, /v1/auth/*, Opositeca, OposiRed, PDF/import, exam-resources, news-assets and POST_OPOSIBOT_LEGACY_CLEANUP remain protected. The deferred cleanup has not executed.

Supabase/Storage outside the verified Product Metrics/wallet scope remains NOT_VERIFIED.

## Start here

- Architecture: SYSTEM_MAP.md
- Authority and routing: SOURCE_OF_TRUTH.md
- Database: ../database/MIGRATION_STATE.md
- Backend: ../backend/ROUTE_MAP.md
- Product decisions: ../product/CLIENT_DECISIONS_REQUIRED.md
- Active attention: ../registry/RISKS.md, ../registry/TECH_DEBT.md, ../registry/UNKNOWNS.md
