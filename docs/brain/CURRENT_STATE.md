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
| Desktop/JVM professional UI | IMPLEMENTED / VISUAL_REVIEW_PENDING_USER | Rama `feature/desktop-professional-ui` (5 commits desde `ae134c0`, pusheada). El escritorio tenía la app móvil renderizada en una ventana: escala tipográfica derivada de `min(ancho/390, alto/844)` — el divisor real acababa siendo la ALTURA de la ventana, dando H1 de 55sp en 2560×1440 y captions de 10sp en 1366×768, y redimensionando toda la letra en vivo; 21 de 26 items del sidebar usaban glifos Unicode (`☷`,`⚿`,`⌁`) renderizados como texto porque el mapa de iconos sólo cubría los 5 tabs del móvil; un único botón sin hover/foco/loading conviviendo con 10 botones inventados en pantallas y 19 Material sueltos; cero hover, foco, teclado, tooltips y scrollbars en TODO el repo; y la ventana arrancaba a 800×600, por debajo del umbral de 1040dp, así que la app de escritorio abría con la interfaz de móvil. Añadido: escala tipográfica fija de escritorio (móvil intacto), tokens de spacing/radius/sizing, breakpoints COMPACT/STANDARD/WIDE, 40 iconos Lucide + registro único, sistema de botones (5 variantes × 3 tamaños + icon button) con los 6 estados, shell con cabecera fija y sidebar plegable 244/68dp, scrollbars vía expect/actual, y sistema de ayuda contextual en 3 niveles (tooltip/popover/panel) con catálogo de textos. Corregidos en el proceso: `StatusChip` pintaba «activo» y «pendiente» del mismo color (éxito usaba el acento coral y pendiente `colorScheme.primary`, que ES ese coral; `success`/`warning` existían sin usarse) y ahora un estado desconocido cae en UNKNOWN, no en verde por descarte; `AsyncStatePane` resolvía `Error` con la tarjeta de «sin resultados» y el mensaje técnico crudo, sin reintento; `AdminTable` repartía el ancho como `peso*172dp` en un `horizontalScroll` permanente (en 1920 una tabla de 4 columnas ocupaba ~730dp); el Dashboard se vaciaba entero cada 5 minutos porque su refresco automático ponía `metrics` en `Loading`; y el Login medía 1872dp de ancho en un monitor de 1920. Verificado en ejecución a 1920/1600/1080 con capturas; 400 tests JVM en verde (0 fallos), `assembleDevDebug` correcto. Segunda fase: ayuda contextual COLOCADA (§15/§17) — `FormSection` y `AdminTableColumn` aceptan un `HelpTopic`, enganchada en saldo/movimientos de OT, liga/nota/evolución/posición en Rankings, rendimiento/competición/economía de la ficha de usuario, packs/servicios/promociones/reglas de pop-up en Market, y estado del sistema; panel de ayuda de 5 pasos en Importación (§61); ficha de usuario en pestañas (§32); Market y Analítica pasan de botones rellenos a pestañas; vocabulario canónico de estado Operativo/Degradado/Caído/Desconocido (§38), distinguiendo caído de degradado; confirmación de movimiento de OT con usuario/cantidad/motivo y rojo sólo al restar (§33). CONTRASTE MEDIDO con la fórmula WCAG en test: encontró que texto blanco sobre el coral de marca da 2,32:1 (por debajo incluso del 3:1 de elemento de interfaz) y el ámbar de aviso 2,55:1 como texto — `#FF8A5B` y «texto blanco» son incompatibles. `accent` se conserva intacto para identidad; sólo el relleno del botón primario usa `accentInk` #C2521F (4,6:1) y `warning` pasa a #A66A00 (4,4:1). DECISIÓN DE MARCA PENDIENTE DE CONFIRMAR POR EL USUARIO. Verificado en runtime con sesión y datos reales a 1920/1600/1500/1080 con capturas; Login verificado arrancando con un `APPDATA` temporal (sesión real del usuario intacta). 414 tests JVM, 0 fallos. NOT_VERIFIED: recorrido completo pantalla por pantalla de §64 (Especialidades, Exámenes, Preguntas, Recursos, Accesos, Suscripciones, Packs, Consumibles, OposiRed, Moderación, Reportes, OposiTester, Noticias, Soporte, Auditoría, Roles, Configuración no se han rediseñado individualmente; heredan los componentes compartidos). | `core/ui/`, `DesktopShell.kt`, `DesignTokens.kt`, `AppIcons.kt`, `ContextualHelp.kt` |
| Cierre pre-infraestructura | INTEGRATED / PASS_WITH_EXTERNAL_PENDING | Rama `integration/oposicontrol-pre-infra` (desde `feature/desktop-professional-ui`; QA móvil `99e41fb` integrado por cherry-pick, conflicto semántico resuelto: su doble de `ContentRepository` no compilaba porque el escritorio amplió la interfaz con capacidades de especialidad). **MOBILE P0 CERRADO**: no existía ningún `BackHandler` en el repositorio y el botón Atrás del sistema cerraba la app desde cualquier detalle. Solución canónica: un único enganche en `AppRoot` (`PlatformBackHandler` expect/actual, real sólo en Android), la pila manda y una tabla de padres cubre el caso de pila agotada; en pantalla raíz el handler se deshabilita y el gesto llega al sistema. VERIFICADO EN EMULADOR con sesión y datos reales: Importación→Atrás→Home (app sigue viva), Ajustes→Estado del sistema→Atrás→Ajustes, Home→Atrás→lanzador. **MOBILE P1 CERRADO**: Home convertía fuentes caídas en ceros (`getOrNull().orEmpty()`, `?: 0`, `getOrDefault(0)`); ahora cada fuente del cliente que falla se anota en `DashboardMetrics.unavailableMetrics` (claves nuevas COMMERCE/NEWS/FORUM_REPORTS/RESOURCE_AUDIT), la UI muestra «Sin dato» y Home sigue devolviendo `Success` — un catálogo comercial caído pierde su bloque, no la pantalla. Decisión extraída a `unavailableDashboardMetrics()`, pura y con tests. Corregida además una petición duplicada de `refreshCommerce()` por carga. DESKTOP: lista de Usuarios con búsqueda, filtros de estado y rol, paginación con total y columnas que se ocultan en ventana estrecha (especialidad y plan NO se muestran: no están en `User`, viven en `UserDetail`); Consumibles/Promociones/Pop-ups como entradas de menú que abren Market en su pestaña, sin duplicar backend; ayuda de capacidades en Especialidades; aviso de seguridad de publicación en Importación. CONTRASTE: `#FF8A5B` con texto blanco da 2,32:1 — el relleno del botón primario usa `accentInk` #C2521F (4,6:1) y el ámbar pasa a #A66A00 (4,4:1); **el cambio afecta también al botón primario de móvil**, pendiente de confirmación de marca. TESTS: 442 JVM + 280 backend = 722, 0 fallos. MSI regenerado: 83 MB en `composeApp/build/compose/binaries/main/msi/OposiControlAdmin-1.0.0.msi`, sin firma. NOT_VERIFIED: recorrido individual de Suscripciones, Packs, OposiRed, Moderación, Reportes, OposiTester, Exámenes, Preguntas, Recursos, Accesos, Noticias, Soporte, Auditoría, Roles; Analytics Study/Community/Economy/Marketing como pantallas propias. | `AppNavigator.kt`, `PlatformBackHandler.kt`, `ContentDashboardRepositoryImpl.kt`, `UsersListPane.kt` |
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

## Preguntas + Importación — desktop redesign (2026-09-04)

UI/UX-only pass (Admin UI Hashira lens), uncommitted working tree, no branch created. `CONFIRMED_PRODUCT_DECISION` from the requesting session: both screens get dedicated desktop modules, isolated from `features:exams` and from `ContentRepository`/`ExamQuestion`, mirroring each other.

**Preguntas**: new `features:questions` module (`QuestionsScreen`/`QuestionsViewModel`/`KtorQuestionsRepositoryImpl`) renders on desktop (`!compactLayout`) instead of the legacy `QuestionsResourcesScreen` (`features:exams`, left in place, unreferenced). New domain types `QuestionListItem`/`QuestionDetail`/`QuestionDraft`/`QuestionExamLink*` (`domain/model/QuestionBank.kt`) and a `QuestionsRepository` interface — additive, `ExamQuestion` and `ContentRepository` are `0`-diff. Base-field edits (enunciado/tipo/fuente/explicación/activa) and exam association write for real through the existing `PATCH /api/v1/admin/preguntas/{id}`; create and option/correct-answer edits still fail live (`admin_create_question`/`admin_replace_question_options` RPC absent, per prior audit) — represented as honest backend errors, never simulated success. No reverse pregunta→exámenes query exists, so "Exámenes asociados" only shows relations known from context/this session, by design (`CONFIRMED_PRODUCT_DECISION`, no fan-out). No Tema field in schema — shown disabled/honest.

**Importación**: new `features:imports` module (`ImportScreen`/`ImportWizardScreen`/`ImportViewModel`) renders on desktop only; `!compactLayout` is the only new branch in `AppRoot.kt`'s `CONTENT_UPLOAD` target, `compactLayout` still renders the untouched `ContentUploadScreen` (`features:exams`). No new repository/RPC: reuses the exact `domain.usecase.content.*` use cases the legacy screen already called (`ListContentImportBatchesUseCase`, `UploadPdfToStagingUseCase`, `UpdateContentImportStagingUseCase`, `PublishContentImportBatchUseCase`, etc.) against the existing `ContentImportRepository`. One real new capability: a working JVM `JFileChooser` PDF picker (`ImportFilePicker.jvm.kt`) — `features/exams`'s `ContentFilePicker` JVM `actual` is a no-op stub today and was left untouched. Publish stays real but non-atomic (same `content_import_atomicity_candidate_v1/002`, `APPLY_PENDING_APPROVAL` gap as before) — the new screen states this next to the Publish button instead of hiding it. `PDF_FORMAT_CONTRACT` is still `docs/product/domains/PDF_IMPORT.md`'s territory (`WAITING_CLIENT_INPUT` there as of this write); this session's type-selection UI (Ráfaga/Razonado/Inmersivo, `ContentExamType`) and codes panel (`ID_GLOBAL`/`ID_EXAMEN`/`TEMA`/`ID_AUDIO`/`ID_VIDEO`/`ID_IMAGEN`) reflect the format a parallel session was asked to specify for the backend/parser side; no template file exists to serve, so "Descargar plantilla" renders honestly disabled.

Tests: `:features:questions:jvmTest` 13/13, `:features:imports:jvmTest` 10/10, `:domain:jvmTest`, `:data:jvmTest`, `:composeApp:jvmTest` all green; `:composeApp:compileKotlinJvm` and `:composeApp:compileDevDebugKotlinAndroid` green. `git diff --stat` confirms `features/exams/`, `domain/repository/ContentRepository.kt` and `data/repository/KtorAdminV1ContentRepositoryImpl.kt` at `0` lines changed throughout.

Runtime: live JVM window (real backend at `localhost:8080`, session pre-authenticated), screenshots captured for both screens' home/list states and the Preguntas editor's populated sections. `NOT_VERIFIED` in this pass: the Import wizard/review/staging-editor/publish screens at runtime (unit-tested only — opening them needs a real PDF through a native file dialog, deliberately not automated after an earlier incident this session where a mistimed scroll landed on the operator's own foreground browser tab instead of the app window); 1366/1920 breakpoints; Razonada/Inmersiva conditional sections at runtime for Preguntas.

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE` (all live calls made were reads or the operator's own prior writes through existing endpoints).

### Content backend — live read-only audit (2026-09-04, same session)

First audit of Exámenes/Preguntas/Importación performed **against the live database by
direct PostgREST introspection**, not inherited from a prior report. Environment confirmed
by the operator as **the real database with user data**, so: zero writes, zero migrations
applied. `DATABASE_APPLY_APPROVAL_REQUIRED: YES`.

`LIVE_VERIFIED` findings:

- Ktor `GET /health` `200`; `/health/ready` → `{"ktor":"up","supabase":"up"}`.
- Auth gate real: `/api/v1/admin/{especialidades,examenes,preguntas}` → `401` without JWT.
- Reads with the loopback dev token: especialidades `200` (3), examenes `200` (5),
  preguntas `200` (**0 active**).
- **`admin_create_question`, `admin_replace_question_options` and `admin_import_lote_preguntas`
  do NOT exist** in the database. Only `admin_grant_tickets` plus stats/roles/access RPCs are
  exposed. Question creation and option/solution replacement therefore fail live today; base-field
  edits do not, because `updatePregunta` PATCHes `preguntas` directly.
- **No Tema/topic table or relation exists** (0 of 90 exposed relations match `tema|topic`):
  `TOPIC_TABLE_EXISTS: NO`, `TOPIC_CODE_EXISTS: NO`, `QUESTION_TOPIC_RELATION_EXISTS: NO` —
  `SCHEMA_GAP` confirmed live, so the desktop UI's disabled/honest Tema block is correct.
- `preguntas` carries real `creado_en`/`actualizado_en`, and `lotes_importacion_preguntas`
  carries `creado_en`; **the Ktor row DTOs drop all three**, which is why the desktop UI shows
  "No disponible". Fixable in Kotlin, no migration needed.
- `recursos_pregunta.codigo_recurso` (NOT NULL) exists — human resource codes are modelled but
  not exposed by `AdminRecursoDto`.
- `preguntas_examen` has `numero_en_examen` NOT NULL; Kotlin's `upsertPreguntaExamen` does not
  send it, while `content_import_atomicity_candidate_v1/002` does. If the column has no DEFAULT,
  every exam association insert fails live. `NOT_VERIFIED` — needs one authorized write.
- `preguntas`: 1 row total, 0 active — the session's test question `TCAE-TEST-RAF-P001` exists
  but is archived (`activa=false`). Not hard-deleted; restoring it is a one-column update, not
  performed (write).
- `lotes_importacion_preguntas` and `preguntas_parseadas_staging`: **0 rows** — the import
  pipeline has never run against this database, so the CHECK values of `estado`/`estado_parseo`
  remain unobservable and must not be invented.

#### Live write verification (2026-09-04, authorized bounded fixtures)

The operator authorized bounded writes in production with clearly-marked fixtures.
Fixture `EX-TEST-20260904-01` was created, exercised and archived. The five pre-existing
exams were re-read afterwards and remain `activo=true`, untouched. `REAL_DATA_MODIFIED: NO`.

**Exámenes — `LIVE_VERIFIED` end to end**, each write followed by a verification read:

| Operación | Ruta | HTTP | Verificación |
|---|---|---|---|
| CREATE | `POST /api/v1/admin/examenes` | `201` | aparece en la lista |
| UPDATE | `PATCH /api/v1/admin/examenes/{id}` | `200` | título releído = editado |
| PUBLISH | `POST .../{id}/publicar` | `200` | `publicado_en` con timestamp real |
| UNPUBLISH | `POST .../{id}/despublicar` | `200` | `publicado_en` vuelve a vacío |
| ARCHIVE | `DELETE /api/v1/admin/examenes/{id}` | `200` | `activo=false`, fila conservada (no hard delete) |

**Preguntas — `LIVE_VERIFIED: BLOQUEADO`.** Un intento real de alta devolvió `500` con
`PGRST202: Searched for the function public.admin_create_question ... not found`, y el conteo
de `preguntas` permaneció en 1: el fallo es limpio, no deja estado parcial. El resto de la
cadena (detalle, opciones, correcta, solución, relación, posición, archivado) no puede
ejercitarse hasta que exista la RPC.

**Canal de migración: no disponible para este agente.** El único conector Supabase accesible
pertenece a otra cuenta (ve `RequenaDesk` y `AgendNotes`); contra `ndiooyyqtaeysnedywer`
responde `You do not have permission to perform this action`. Por tanto
`MIGRATION_001_APPLIED: NO` por falta de canal, no por decisión.

Cambios Kotlin acompañantes, `TEST_VERIFIED` (compilan, 280 tests backend verdes) pero **no**
`LIVE_VERIFIED` porque el servidor en marcha sigue sirviendo el binario anterior y la RPC no
existe: contrato de `admin_create_question`/`admin_replace_question_options` reescrito para
coincidir con la firma del candidato (la RPC genera los ids y devuelve el uuid; `razonamiento`
vacío ya no borra la explicación existente), y `upsertPreguntaExamen` ahora envía
`numero_en_examen` calculando el siguiente número real del examen.

Prepared, not applied: `supabase/canonical/admin_question_write_candidate_v1/`
(`001_admin_question_write_rpcs.sql` + README) — transactional `admin_create_question` and
`admin_replace_question_options`, `security definer`, `search_path = ''`, `service_role`-only,
with preflight and commented rollback, written against the columns verified above. It requires a
companion Kotlin change: the current `AdminCreateQuestionRpc` body shape does not match the new
parameter names.

## Dashboard V2 — escritorio (2026-09-10)

Rediseño completo del Dashboard de escritorio, en árbol de trabajo sin commit.
Coordinador + dos Hashira implementadores (Backend/Data y Admin UI/UX) + revisor
independiente, con dos rondas de revisión cruzada y una tercera de corrección.
`HUMAN_GATE: APPROVED` para las cuatro decisiones de arquitectura (D1–D4).

### Qué sustituye

`ContentDashboardRepositoryImpl` orquestaba **cinco repositorios desde el
cliente**, con dos dependencias bloqueantes —un fallo leyendo especialidades o
exámenes vaciaba la pantalla entera— y **una llamada HTTP por examen** para
auditar recursos, un coste que crecía con el catálogo. Derivaba además datos que
no eran lo que decían: `latestImportLabel` mostraba la fecha del último examen
bajo la etiqueta «importaciones», y los tres «eventos recientes» llevaban
`Clock.System.now()`, así que decían haber ocurrido a la vez y se rejuvenecían
solos cada cinco minutos.

**No se retira**: sigue alimentando el Home móvil (`MobileDashboardScreen`), que
conserva su contrato hasta que tenga uno propio. El camino compacto está a
0 líneas de diferencia, verificado.

### Contrato nuevo

`GET /api/v1/admin/dashboard/overview?sections=` bajo `authenticate("gateway-auth")`.
Seis franjas (`system`, `attention`, `pulse`, `content`, `activity`, `economy`)
con degradación independiente vía `failedBlocks`; `?sections=` permite refrescar
una sola franja. Una sección desconocida responde **400** nombrando las válidas.
La cronología viaja **dentro** del agregado: Ktor consulta `auditoria_log` y el
cliente no llama a `/auditoria` por su cuenta.

Coste real contado sobre el código, no estimado: **17 conteos exactos más la
sonda de Supabase y una lectura de auditoría, 19 peticiones upstream** por
refresco completo, para 15 cifras publicadas. Tres conteos están duplicados
entre franjas a propósito: compartirlos acoplaría el fallo de una a otra, que es
lo que la degradación por franja evita.

### Garantías con evidencia

| Garantía | Cómo está probada |
|---|---|
| Ningún fallo de lectura llega como `0` | Recorrido de tres métricas de punta a punta por el revisor; `AnalyticsMetricPolicy` falla cerrado; cero `?: 0`/`orEmpty()`/`getOrDefault` en el camino nuevo |
| Los filtros PostgREST son los reales | Los 12 conteos de filtro fijo afirman la **ruta completa**, no un `contains`. **Verificado por mutación**: alterar el filtro de `reportes_contenido` y la ventana de 30 días pone 2 tests en rojo |
| Las claves del cable no pueden derivar | Dos tests espejo con literales a mano en `:domain` y `:backend:ktor`. **Verificado por mutación** por el coordinador: renombrar una constante pone el espejo en rojo |
| Los timestamps son reales | `occurredAt` es `auditoria_log.creado_en`; fila sin fecha o sin id se descarta **y se cuenta** en `droppedActivityEntries`, para que «no pude leer» no se lea como «no ha pasado nada» |
| El vocabulario de auditoría existe | Test de **conjunto** contra las 24 acciones extraídas del código, no de predicado. Detectó y eliminó 5 traducciones inventadas |
| El contraste se mide, no se estima | `ContrastTest` compone el cristal al 55% sobre el extremo oscuro del degradado y **falla si `accentInk` sube de 4,5:1** |

### Decisión de producto aplicada

De la referencia visual se implementó la composición y **no** los datos que no
existen: sin sparklines (no hay serie temporal), sin deltas de comparación (no
hay periodo anterior en ningún endpoint), sin selector de ventana (las ventanas
están cocidas en cada query), y sin `Ingresos`/`Nuevas suscripciones`/`Conversión
a premium`. `grossRevenueCents` y `ticketsInCirculation` **viajan en el contrato**
como `NOT_INSTRUMENTED` con la vista concreta que haría falta, y
`DashboardBlockReport.renderable` las excluye del panel: `NO_DATA` sí ocupa sitio
(reintentar puede servir), `NOT_INSTRUMENTED` no.

Ventana de vencimientos partida por utilidad: `subscriptionsExpiring7d` es alerta
operativa en «Necesita tu atención»; `subscriptionsExpiring30d` es contexto en
Economía.

### Cerrado de paso

`features/settings` deja de inyectar `NotConnectedSystemStatusRepository` y
consume el endpoint real `GET /api/v1/admin/estado-sistema`, que existía y
funcionaba sin cliente desde 2026-09-02. `NotConnectedSystemStatusRepository` se
conserva: sigue siendo la respuesta correcta sin backend.

### Capacidad nueva del repositorio

`:features:dashboard` es el **primer módulo con tests de Compose UI**
(`jvmTest`, `compose.uiTest` + `compose.desktop.currentOs`). Validados por
mutación: el primer intento medía sólo el ancho y **pasaba con el defecto
puesto**, porque un `Row` desbordado no deja el hijo a cero — lo mide a tamaño
natural y lo dibuja fuera. Miden ahora ancho y posición.

### Pasada de fidelidad visual (2026-09-10, misma fecha)

El panel se rehízo visualmente contra una referencia de diseño. Conserva íntegros
el contrato agregado, la degradación por franja, la actividad real y la
navegación por `AppRoute`.

Lo que cambió, y por qué importa cada cosa:

- **Buscador retirado del Dashboard.** `KtorTagSearchRepositoryImpl` devuelve
  listas vacías incondicionalmente, así que el campo respondía «Sin resultados»
  escribieras lo que escribieras. Se condiciona por destino, **no se borra**: la
  infraestructura sigue montada para el resto de pantallas y hay test que falla
  si alguien la elimina en lugar de condicionarla.
- **Acción primaria «Actualizar»**, que faltaba: `primaryActionLabel` sólo se
  rellenaba para `LEGACY_SECTION` y el panel tiene destino propio.
- **Cristal en dos niveles**: `GlassSectionSurface` (nuevo, genérico) para los
  contenedores de sección; las superficies con cifra siguen opacas. **No se
  revoca** la decisión de `GlassSurface` de retirar el cristal en escritorio: se
  complementa.
- **Color semántico por función** (`SemanticRole`, `SemanticIconContainer`,
  tokens `insight` y `commerce`), en lugar de un coral único para todo.
- **Defecto tipográfico preexistente corregido**: `titleSmall` estaba **en uso y
  sin definir** en `desktopTypography`, así que Material lo resolvía con
  `FontFamily.Default` — la fuente del sistema en vez de Manrope — en el Dashboard
  y en otras nueve pantallas.

**Decisión de marca del propietario del producto**: durante la pasada se oscureció
`accentInk` de `#C2521F` a `#B04A1B` para dar holgura de contraste al enlace, y
**se revirtió**. Ese token es el relleno del botón primario de toda la
aplicación, móvil incluido, y el rediseño de una pantalla no lo justifica. La
consecuencia se conserva medida: sobre cristal el coral se queda en ~4,2:1, así
que el texto de los enlaces de sección va en `textPrimary` y el coral queda para
el chevron. Fijado en `ContrastTest`.

`1366_KPI_BELOW_FOLD: ACCEPTABLE_RESPONSIVE_DRIFT` — decisión explícita del
propietario. A 1366×768 la cabecera de «Pulso del producto» queda por encima del
corte y la primera línea de KPI no. Recuperar esos ~57 px exigía encoger
tarjetas, iconos, tipografía o paddings, y **no se sacrifica legibilidad para que
todo quepa en 768 px**: el panel tiene scroll y eso es correcto.

`TESTS: 675, 0 fallos` (`--rerun-tasks`, 622/622 tareas ejecutadas; un
`BUILD SUCCESSFUL` de caché no cuenta como evidencia).
`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE`.

### Estado de verificación — leer con cuidado

| Eje | Estado |
|---|---|
| `VISUAL_RENDER` | **VERIFIED** — capturas a 1920/1600/1366/1080 más el caso real de ceros, revisadas por un revisor independiente que midió luminancia y contraste sobre los píxeles |
| `AUTOMATED_CONTRACT` | **VERIFIED** — 675 pruebas, con mutación demostrada en filtros PostgREST, claves de contrato y test de UI |
| `LIVE_DATA_RUNTIME` | **NOT_VERIFIED** |

**`LIVE_DATA_RUNTIME` no debe convertirse en `LIVE_VERIFIED` por acumulación de
las otras dos.** El equipo del propietario no puede sostener Ktor y la app de
escritorio a la vez sin saturar la máquina, así que el backend **no se arrancó**.
Las capturas salen de un harness de fixtures deterministas alojado en
`features/dashboard/src/jvmTest/`, elegido ahí porque las fuentes de test no se
empaquetan y por tanto **no pueden alcanzar producción por construcción**. Ese
harness valida cristal, fondo, jerarquía, tamaños, color, iconografía, densidad y
responsive; **no valida ni un solo dato**. El panel real sigue mostrando su estado
de error si Ktor no está levantado, que es el comportamiento correcto.

Sin observar contra datos reales: un `droppedActivityEntries` distinto de 0, los
sufijos reales de `feedback_*`/`lote_*`/`reporte_*` en `auditoria_log`, y el
autorefresco de 60 s. La cobertura de tests de UI alcanza la franja de sistema;
las tarjetas de atención, la rejilla de KPIs y los tres paneles inferiores siguen
probados una capa por debajo del píxel.

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
