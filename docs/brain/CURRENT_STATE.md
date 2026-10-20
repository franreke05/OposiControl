# Current State

## Control panel E2E — 2026-10-20 (`develop` ae62375 + este ciclo, panel `c655116`)

Estado: **CONTROL_PANEL_READY_LOCAL (condicionado)**. Las 12 funciones admin están verificadas de punta a punta en la capa de datos (12/12) y hay pruebas de interfaz de login/roles, publicación, pedidos/reembolsos y auditoría (17, panel `95b5b1e`). Latencias en reposo: lecturas con p95 ≤ 67 ms y escrituras auditadas ≤ 63 ms, sin ningún cuello. Condiciones antes de pasar a READY:
(1) el propietario decide si el motivo de anulación es obligatorio (el acceso del moderador se DECIDIÓ el 2026-10-20: solo moderación del foro, implementado y probado en local, panel en la rama del panel);
(2) volver a ejecutar `ControlJourneysE2ETest` sobre el candidato integrado con develop actual (E7 `cb50ee3` es anterior al límite de mutaciones admin y a las migraciones `security_*`);
(3) las pruebas de interfaz usan repositorios falsos, así que la cadena interfaz→Ktor solo está cubierta por la capa de datos.
NO APTO PARA PRODUCCIÓN mientras sigan abiertos los RB P1 (MAIN sin tocar; ningún GO aplicado).
- **E2E de capa de datos (LIVE_VERIFIED en local aislado):** `ControlJourneysE2ETest` 12/12. Repositorios reales del panel → Ktor `control` real → PG17 (stack `oposi-control-e2e`, árbol E7 `cb50ee3`). Comprueba la base, `auditoria_log` y la matriz de roles. Evidencia: `docs/audit-v1/evidence/CONTROL-E2E/`.
- **Corregido en el panel:** 502 de `/especialidades` con `descripcion` NULL. Hacía fallar en el panel un guardado de examen que el servidor ya había hecho, y al reintentar se duplicaba.
- **MAIN, solo lectura (alcance autorizado):** RB-16 ampliada a 14 políticas (`news-images` vía `can_manage_content`). Nuevo RB-18 / SEC-V1-24: un moderador o admin bloqueado sigue siendo staff en RLS (migración local `20261023100300`). Las 21 tablas con RLS sin políticas son 21/21 server-only intencionales. Evidencia: `evidence/SEC-V1-01/MAIN-readonly-2026-10-20.md`.
- **P2 local:** `20261023100400` (search_path de los triggers de `noticia`) 4/4. El índice del FK `productos_comerciales_plan_fk` no se propone (sin beneficio medible).
- **Contrato Control↔Bot** de pedidos, reembolsos y accesos: `docs/audit-v1/CONTRACT_CONTROL_BOT_ORDERS_REFUNDS_ACCESS.md` (Q1-Q3 respondidas por Bot; la política P2 de reembolso la decide el propietario).
- Moderador: DECIDIDO 2026-10-20 (solo moderación del foro) e implementado en local; lista cerrada de rutas en Ktor y panel reducido. Pendiente del propietario: ¿el motivo de anulación es obligatorio? Packs de Market (§15.1).
- Pruebas: dbtest 296/0 + 4 (noticia); panel `:backend:ktor:test` 2129/0 y `:data:jvmTest` 298/0.

## Auditoría V1 ciclo 1 — 2026-10-19 (`develop` 7fb9000, v1-server 86f0da9, panel 29a019f)

Estado de salida: **NO APTO PARA PRODUCCIÓN**. Entregables en `docs/audit-v1/` (SECURITY_AUDIT, API_PERFORMANCE_AUDIT,
CONTROL_PANEL_V1_AUDIT, INTEGRATION_CONFLICTS, CLEANUP_INVENTORY, RELEASE_BLOCKERS). Ciclo estático + catálogo MAIN en
solo lectura; sin cambios de código, sin escrituras en MAIN. Tests ejecutados en `develop`: `:backend:ktor:test` 2049 y
`:backend:dbtest:test` 279, 0 fallos. P0: ninguno confirmado. P1 bloqueantes: RB-01…RB-13 (destacan: `authenticated`
con INSERT/UPDATE/DELETE en 12 tablas de contenido en MAIN — LIVE_VERIFIED; rate limit admin E6 inoperante en perfil
`control`; E6 ausente en v1-server/integración; reembolso fuera de orden acredita; CI no corre en `develop`).
La línea «Bloqueantes de release» de 2026-10-15 queda superada por `docs/audit-v1/RELEASE_BLOCKERS.md`.
Ciclo 2 (mismo día, sin commit, MAIN sin tocar): SEC-V1-01 con migración `20261023100000` lista y probada en local
(PG16 8/8, PG17 apply/rollback); SEC-V1-02 corregido y verificado con Ktor real `control` + Valkey; Prometheus sin
`--web.enable-lifecycle`. Nuevos bloqueantes preexistentes: RB-14 (control no arranca en producción con las plantillas)
y RB-15 (gate PG17 de develop falla en `assertions.sql`).
Ciclo 3 (sin commit tras `2cab55b`): retests independientes de Bot. SEC-V1-05 PASS (`1ec2b37`). SEC-V1-04 PASS y SEC-V1-08 mitigado con residual (`aa5e3ab`). Expediente MAIN de SEC-V1-01 en `supabase/canonical/sec_v1_01_main_intervention/`, con preflight y checkpoint validados en PG17. Evidencia SEC-V1-02 en `docs/audit-v1/evidence/`. Plan de integración v2 y requisitos de auditoría de pagos de tienda. La recreación de la BD QA de las 09:33 fue un `up.mjs --reset` manual sugerido por la sesión COORD.

## Alpha close — 2026-10-15 (rama `develop`)

`develop` es la Alpha y la base de trabajo: `main` (`cfa2e14`: Sistema rehecho, Usuarios, Catálogo,
OposiMarket, Noticias) + solicitudes de acceso con verificación de email (`feature/oposibot-consumer-backend`)
+ revisión funcional de controles visibles (bloques E1–E5). Modelo de ramas y destino de cada rama antigua:
`../engineering/BRANCH_CONSOLIDATION_2026-10-14.md`. Nada desplegado; MAIN no se ha tocado.

| Módulo | Estado Alpha | Nota |
|---|---|---|
| Usuarios (lista, ficha, roles, estado de cuenta) | CONNECTED / TEST_VERIFIED | Diálogo muerto de conceder tickets quitado; filtros en cliente sobre el censo completo (DEBT-135) |
| OposiTickets / cartera | CONNECTED / TEST_VERIFIED | Único sitio para ajustar saldo, con clave de idempotencia |
| Accesos | CONNECTED / TEST_VERIFIED | Selector de personas sobre `GET /usuarios?q` |
| Catálogo / OposiMarket | CONNECTED / TEST_VERIFIED | Vaciar opcionales persiste (DEBT-089); Market solo muestra; ruta de packs inalcanzable eliminada |
| Noticias / Tipos | CONNECTED / TEST_VERIFIED | Tipos por ruta canónica; relectura y capas ilegibles avisan; media huérfana = DEBT-091 |
| OposiRed | EXPLICIT_ALPHA_BLOCKER (solo lectura) | No existe acción de moderar hilos; filtro de categoría limitado (DEBT-136) |
| Moderación | CONNECTED (solo «Advertencia») | Otras medidas PRODUCT_BLOCKED (DEBT-130) |
| Soporte | CONNECTED / TEST_VERIFIED | «Asignado a» y filtro de estado inexistentes quitados |
| Informes / OposiTester | CONNECTED / TEST_VERIFIED | Paginación real, notas y recompensa; errores con reintento |
| Analytics | CONNECTED / honesto | Rachas y activos de Retención «Sin instrumentar» (`rachas_usuario` sin escritor) |
| Solicitudes de acceso | CONNECTED / TEST_VERIFIED | Verificación de email antes de aprobar (migración 20261020100000 pendiente de despliegue) |
| Sistema (Estado, Administradores, Auditoría) | CONNECTED / TEST_VERIFIED | `auditoria_log` append-only (20261021100000 pendiente de despliegue) |
| Comunidades / Etiquetas | CONNECTED | Ahora alcanzables desde Contenido |

Gate 2026-10-15 en `develop`: 4306 tests JVM (panel + `:backend:ktor:test` + `:backend:dbtest:test`), 0 fallos;
`verify-ci-jvmtest-coverage`, `verify-no-direct-supabase`, `verify-migrations-manifest`, `verify-ops-config`,
`verify-android-flavor-config` y `git diff --check` en verde. NOT_VERIFIED: E2E contra base real (el stack local
vive en las ramas `feature/december-*`, excluidas por el owner), recorrido en ejecución con sesión real, gitleaks
(no instalado; escaneo heurístico del diff limpio).

Bloqueantes de release (no de Alpha): DEBT-151 (sin límite de mutaciones en el plano admin), DEBT-095 a/b (JWT
`aud`/`exp`, token de `/metrics`), DEBT-156 (puerta PG17 sin las migraciones `security_*` en `PENDING_MIGRATIONS.txt`).
Despliegue conjunto obligatorio: el backend devuelve 410 a escritores legacy que un panel antiguo aún usa.

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
| Screen 10 — Estado del sistema | CONNECTED / TEST_VERIFIED | `AppRoute.System.Status` consume `GET /api/v1/admin/estado-sistema` mediante `KtorSystemStatusRepositoryImpl`. Conserva health Ktor/Supabase y muestra CPU/RAM Node Exporter, p95 HTTP Ktor 5m, 5xx/24h y timestamp real; ausencia se distingue de cero. Worker/crashes/queue siguen sin fuente y no se inventan. | ../product/SCREEN_REGISTRY.md |
| Backend observability foundation | INTEGRATED / PASS | Request correlation (`X-Request-Id` + MDC), logging estructurado, `StatusPages`, `/health/live`, `/health/ready`, Sentry y métricas Micrometer/Prometheus en `/metrics`. El endpoint de métricas es de scrape Docker y debe quedar bloqueado en Caddy público. | ../engineering/OBSERVABILITY.md |
| HTTP timeouts (P1 cerrado) | FIXED | Los 7 `HttpClient` de Supabase (Admin, AdminV1, News, V1, TagSearch, App, Content/legacy y el verificador JWT) tenían `HttpClient.newHttpClient()` sin timeout — confirmado, ninguna llamada podía colgar el hilo de la request indefinidamente. Corregido: `connectTimeout` a nivel cliente + `.timeout()` por request, ambos configurables (`UPSTREAM_CONNECT_TIMEOUT_MS`, `UPSTREAM_REQUEST_TIMEOUT_MS`), con test que reproduce el escenario real. | ../backend/Observability.kt |
| Import atomicity + duplicados | CLOSED 2026-09-18 (vigente con M10, 2026-09-19) | La publicación es UNA RPC atómica e idempotente, `admin_import_lote_preguntas` (IM3 `20260918113100`, rehecha por M8 y M10), aplicada a MAIN y probada en vivo con E2E de 74/74 checks; los duplicados del banco se detectan por TIPO y decide una persona (vincular sin duplicar u omitir). El estado `parcial` ya no existe. Texto previo de esta fila (candidato sin aplicar, `APPLY_PENDING_APPROVAL`) SUPERADO; véase «Importaciones final + pregunta anulada (M10)» al final. | ../../supabase/migrations/20260919122139_annulled_questions_in_exams.sql |
| Sentry | INTEGRATED / SMOKE_VERIFIED | Backend y Desktop inicializan el SDK sólo con DSN configurado, identifican environment/release y desactivan PII por defecto. Backend captura únicamente excepciones inesperadas; Logback aporta breadcrumbs y eventos `ERROR` sin duplicar el handler genérico. | ../engineering/OBSERVABILITY.md |
| Prometheus | INTEGRATED / TEST_VERIFIED | Fuente reproducible en `ops/monitoring`: Prometheus, Node Exporter, cAdvisor y target Ktor; retención 15d/5GB y volumen persistente. Ktor expone Micrometer/JVM en `/metrics`; el Admin API agrega sólo CPU/RAM, p95 5m y 5xx/24h para Screen 10. Grafana queda fuera de alcance. **LIVE_VERIFIED 2026-09-23** sobre `v1.1.0-observability` (`5626ab5`) en Hetzner: 4/4 targets UP (prometheus, node, cadvisor, ktor); `/metrics` interno 200 desde `oposicontrol-deploy_default` y público 404 en Caddy antes del `reverse_proxy`; `/health/live`, `/health/ready` y `/health` en 200; CPU 2,91 %, RAM 13,16 % y p95 HTTP 3,6 ms medidos por PromQL. 5xx/24h devuelve vector vacío — `null`, «No disponible» — porque el job `ktor` no acumula aún una ventana de 24 h; es la semántica correcta, no un fallo. Volumen `oposicontrol-monitoring_prometheus-data` preservado (20,3 h de TSDB). NOT_VERIFIED: render de Screen 10 con sesión administrativa real (sin JWT admin en la sesión de despliegue; el endpoint responde 401 sin token, como debe). | ../engineering/OBSERVABILITY.md |

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

**[Actualizado 2026-09-19]** La publicación ya NO es parcial: es una RPC atómica e idempotente (`admin_import_lote_preguntas`), aplicada a MAIN y probada en vivo; el candidato `content_import_atomicity_candidate_v1` es histórico y no se aplica. Siguen abiertos, como evidencia: el procesamiento asíncrono (PDF-006), la asociación de recursos inmersivos (DEBT-051) y la verificación visual/runtime en dispositivo de la pantalla compacta. Texto original de 2026-08-27, ya superado: _la implementación reportaba publicación parcial y el candidato estaba `APPLY_PENDING_APPROVAL`_.

- Branch/commit history: originally `feature/mobile-content-upload` (`9eea7da`, `3c09713`), merged into `integration/mobile-finish`.
- Backend/data: `POST /api/v1/admin/importaciones/preguntas/pdf` creates reviewable staging through Ktor; existing list, detail, staging PATCH and publish endpoints remain the integration boundary.
- Verification: full suite green post-integration (`:backend:ktor:test`, `:domain:jvmTest`, `:data:jvmTest`, `:core:ui:jvmTest`, `:features:reports:jvmTest`, `:features:forum:jvmTest`, `:features:opositester:jvmTest`, `:features:settings:jvmTest`, `:features:exams:jvmTest`, `:composeApp:jvmTest`, `:composeApp:assembleDevDebug`, `git diff --check`). No ADB device was connected in any session so far, so runtime remains `NOT_VERIFIED`.
- Priority state (2026-08-27, superado por 2026-09-19): P0 none; la publicación atómica + detección de duplicados ya está cerrada (DEBT-006B); abiertos P2 worker asíncrono (PDF-006) y recursos inmersivos (DEBT-051); ver TECH_DEBT.md DEBT-051..065.
- CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: `NO`.
- Database/infra state (de la rama Screen 09, 2026-08-27; histórico): `DATABASE_CHANGES: NONE`. Las migraciones IM1/IM3/M8/M9/M10 se aplicaron después a MAIN (véase `docs/database/MIGRATION_STATE.md`).
- Next step (2026-08-27) SUPERADO: la RPC atómica ya está aplicada y cableada en `importarLote`. Lo pendiente es la confirmación del formato PDF por el cliente y la verificación de la pantalla compacta en dispositivo.

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
| Estado del sistema | `CONNECTED / OBSERVABILITY_V1` | `GET /api/v1/admin/estado-sistema`. Ktor y Supabase conservan sus sondas reales; Prometheus aporta CPU/RAM del host, p95 HTTP Ktor 5m y 5xx/24h. Cada serie puede ser `null` sin ocultar las demás; cero sólo significa una medición real. Worker, crashes y cola permanecen `notInstrumented`. |
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

### Import atomicity — candidato corregido (SUPERADO 2026-09-18: la RPC real `admin_import_lote_preguntas` está aplicada en MAIN; lo de abajo es historia)

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

`IMPORT_ATOMICITY: CLOSED 2026-09-18` (antes: `BLOCKED_ONLY_BY_DATABASE_APPLY_APPROVAL`).
`content_import_atomicity_candidate_v1/002` NO se aplica: sustituido por IM3 (`supabase/migrations/20260918113100_import_lote_rpc.sql`).

### Externo pendiente

- **Sentry**: superado por la integración y smoke tests posteriores de backend y Desktop; los DSN permanecen fuera del repositorio.
- **Prometheus**: superado por Observability V1; Grafana continúa fuera de alcance.
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

**Importación**: new `features:imports` module (`ImportScreen`/`ImportWizardScreen`/`ImportViewModel`) renders on desktop only; `!compactLayout` is the only new branch in `AppRoot.kt`'s `CONTENT_UPLOAD` target, `compactLayout` still renders the untouched `ContentUploadScreen` (`features:exams`). No new repository/RPC: reuses the exact `domain.usecase.content.*` use cases the legacy screen already called (`ListContentImportBatchesUseCase`, `UploadPdfToStagingUseCase`, `UpdateContentImportStagingUseCase`, `PublishContentImportBatchUseCase`, etc.) against the existing `ContentImportRepository`. One real new capability: a working JVM `JFileChooser` PDF picker (`ImportFilePicker.jvm.kt`) — `features/exams`'s `ContentFilePicker` JVM `actual` is a no-op stub today and was left untouched. [SUPERADO 2026-09-18: la publicación ya es atómica, RPC IM3 aplicada] Publish stays real but non-atomic (same `content_import_atomicity_candidate_v1/002`, `APPLY_PENDING_APPROVAL` gap as before) — the new screen states this next to the Publish button instead of hiding it. `PDF_FORMAT_CONTRACT` is still `docs/product/domains/PDF_IMPORT.md`'s territory (`WAITING_CLIENT_INPUT` there as of this write); this session's type-selection UI (Ráfaga/Razonado/Inmersivo, `ContentExamType`) and codes panel (`ID_GLOBAL`/`ID_EXAMEN`/`TEMA`/`ID_AUDIO`/`ID_VIDEO`/`ID_IMAGEN`) reflect the format a parallel session was asked to specify for the backend/parser side; no template file exists to serve, so "Descargar plantilla" renders honestly disabled.

> **SUPERSEDED en parte (2026-09-12) — ver «Importación V2 — workspace de escritorio».** `ImportWizardScreen` ya no existe y el panel de códigos se retiró: se comprobó contra `ContentImportStagingItem` que **ninguno** de `ID_GLOBAL`/`ID_EXAMEN` por fila/`TEMA` como código/`ID_AUDIO`/`ID_VIDEO`/`ID_IMAGEN` llega al staging, así que anunciarlos hacía preparar PDF con columnas que el servidor ignora. Lo demás de este párrafo —módulo propio, reutilización de casos de uso, selector JVM real, publicación no atómica— sigue vigente.

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

[SUPERADO 2026-09-17/18: la RPC real `admin_create_question` (Q2, `20260917164138`) ya está aplicada; este paquete es histórico y no se aplica] Prepared, not applied: `supabase/canonical/admin_question_write_candidate_v1/`
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

## Exámenes V2 — escritorio (2026-09-10 / 2026-09-11)

Workspace de Exámenes en tres columnas (catálogo · editor con pestañas · rail de resumen) en lugar de la pantalla antigua. **Sólo la pestaña «Información básica» está cerrada y certificada.** Configuración quedó conceptualmente cerrada antes. Preguntas, Recursos, Acceso y Publicación **no se han rediseñado**, por decisión explícita del propietario. Nada de esto está commiteado todavía: el HEAD sigue en `02ff80b`.

### Cerrado antes en la vertical (no reabrir salvo regresión)
`razonado` visible en el catálogo · `total_preguntas` ya no se sobrescribe al guardar · desvincular ≠ archivar pregunta · `DELETE /api/v1/admin/examenes/{id}/preguntas/{preguntaId}` · N+1 de preguntas a coste constante · `Ráfaga/Razonado/Inmersivo` canónico (`guiado` sólo alias de lectura) · Recursos = recursos de las preguntas, nunca del examen · sin `publishFrom` en el workspace de escritorio · no existen la selección automática por criterios, el estado «En revisión» ni la preview de un borrador en el catálogo.

### Información básica — dominio corregido
- **El PATCH de examen no borraba** (P0): pasar a Nacional, vaciar el año o la duración no se guardaba y el editor volvía al valor anterior sin avisar. Ahora `PATCH /api/v1/admin/examenes/{id}` aplica merge-patch (RFC 7396) **sólo** en `comunidad_id`, `anio` y `duracion_minutos` (lista cerrada `CLEARABLE_EXAMEN_COLUMNS`, `AdminV1Routes.kt`): clave presente con `null` = se borra; clave ausente = no se toca. El resto de columnas conserva el comportamiento anterior. Limitado a propósito: el guardado de ráfaga de móvil hace PATCH sin esas claves (con `null` explícito general las borraría) y el cliente de escritorio omite los valores por defecto (un merge-patch general dejaría de guardar «Oficial = false»). Sólo el PATCH del editor de examen envía las tres claves siempre.
- Corregido además: la portada marcaba cambios sin guardar falsos (se actualizaba `examsFlow`; el VM observa `allExamsFlow`) · la especialidad se preseleccionaba en silencio (la primera del catálogo, inactivas incluidas) · al cambiar de examen se pintaba el recuento del anterior · el tipo cambiaba solo al abrir · publicar, despublicar y reintentar perdían ediciones sin confirmar · carrera en `saveExam` · comunidades sin reintento. Tras la revisión independiente: guarda de código duplicado en `saveExam`, confirmación «Cambios guardados.» visible (antes se borraba al reabrir), duración ≤ 0 rechazada al guardar y un error de guardado que se retira al corregir el campo.
- Cada corrección tiene test y **mutación roja → verde** con la salida literal.

### Información básica — decisiones de producto aplicadas (con evidencia, sin escalar)
- Recuento de preguntas mientras carga: el último recuento de la sesión o, si no lo hay, el servido (`total_preguntas`), **siempre marcado «sin confirmar»** y nunca como dato. El badge de la pestaña y las comprobaciones no lo usan; un error = sin cifra. Ver EXAM-002.
- Código del examen **editable opcional** (el servidor genera `EX-xxxxxxxx` si llega vacío); nunca un código predicho ni el UUID. Duplicados bloqueados contra el catálogo (EXAM-005 cubre el residual).
- Portada: **SUPERSEDIDO por «Exam cover V1.1» (final de este documento)**: ya no hay subida ni selección; la portada se deduce del tipo y de la comunidad.
- `community unavailable ≠ Nacional`: Nacional sólo para `comunidad_id == null` de un registro cargado o del default de un nuevo. Un fallo del catálogo dice «No disponible». «Comunidad no encontrada» no ofrece limpiar el vínculo.
- «Sin duración» en lugar de «Sin límite de tiempo» (nada en el repo aplica la duración como límite) · se mantiene el título «Antes de publicar» (decisión del propietario), sin que ninguna fila afirme que el servidor lo exija · acción de página «Nuevo examen» y primario de la barra «Crear examen».

### Tema oscuro y contraste (afecta a toda la app)
- `AdminTheme` provee `LocalContentColor = textPrimary` en su raíz. Antes, todo `Text` sin color heredaba **negro**: los títulos de `FormSection` estaban a 1,72:1 en oscuro, Configuración incluida.
- `ContrastTest` compone el cristal sobre **las dos** paradas reales del fondo (`GlassBackdrops.kt`) y mide **la misma función de color que usa cada componente**, nunca literales. Un test de pares de tokens no detecta qué color elige un componente.
- El tema oscuro es nuevo (en `02ff80b` no existía): `DarkGlassTokens` con `textSecondary`, `sectionGlass` (navy al 80 %), `glassFillStrong` y las familias semánticas ajustados al contraste medido. Los menús de Material se mapean a la paleta (`surfaceContainer*`).
- **Cambios en claro, fuera de la marca**: token `fieldBorder` (borde de campo a ≥3:1, WCAG 1.4.11) y tintas `successInk`/`warningInk`/`dangerInk`/`infoInk` para el texto e icono de los chips de estado (2,83–3,68 → 4,64–4,97) y el icono de éxito. `accentInk` sigue en `#C2521F`. **Visible fuera de Exámenes**: en el Dashboard V2 en claro cambian sólo los iconos de éxito (2,90 → 4,97; el resto es idéntico píxel a píxel frente a `02ff80b`); bordes de campo en Configuración y buscadores; el texto de un botón fantasma deshabilitado pasa de `#9AA5B1` a `#6B7684`. Los componentes son compartidos con móvil, donde no está verificado (DEBT-031).

### Responsive
En STANDARD (1366–1600) la franja de resumen mide 40 dp fijos y abre el detalle superpuesto. El formulario tiene alto mínimo y la rueda pliega cabecera y franja. La barra de guardar está siempre visible. Antes, a 1366×768, un examen nuevo dejaba el formulario a 0 px; ahora mide 182 px en reposo y 427 px al desplazar.

`DATABASE_CHANGES: NONE` (el PATCH cambió en código Ktor, no en la base). `MAIN_WRITES: NONE`.

### Estado de verificación — leer con cuidado

| Eje | Estado |
|---|---|
| `VISUAL_RENDER` | **VERIFIED** — 65 capturas del harness de jvmTest (fixtures sólo en test), claro y oscuro, de 1080 a 2560, medidas sobre el píxel por un revisor independiente con renders propios |
| `AUTOMATED_CONTRACT` | **VERIFIED** — `core:ui` 79 · `features:exams` 173 · `features:dashboard` 56 · `composeApp` 116 · `backend:ktor` 328 · `data` 63 · `domain` 72; 0 fallos con `--rerun-tasks`; compilan JVM y Android |
| `LIVE_DATA_RUNTIME` | **NOT_VERIFIED** |

Revisión independiente (Hashira C): la primera revisión **no** certificó (2 P0 + 3 P1: títulos negros en oscuro, formulario a 0 px a 1366×768, franja que crecía con la primera tecla, contraste bajo sobre cristal en oscuro, código duplicado anunciado como servidor caído). Todos se cerraron y se volvieron a medir. Certificación final: **`READY_FOR_BASIC_INFO_REVIEW`** (LIGHT/DARK/RESPONSIVE PASS, FUNCTIONAL_DRIFT NONE, VISUAL_DRIFT ACCEPTABLE, P0 y P1 NONE), con 10 P2 registrados en DEBT-036 y DEBT-039.

**`LIVE_DATA_RUNTIME` no se ha convertido en verificado por acumulación de los otros dos.** No se arrancó Ktor: el propietario no puede sostener Ktor y la app de escritorio a la vez. Ninguna captura dice nada sobre datos reales. Sin verificar además: teclado (foco bajo el pliegue, Escape en el detalle de la franja), lector de pantalla y todo el camino móvil.

### Abierto
P0-4 → EXAM-008 (los recursos guardan la ruta local) · P0-5 → EXAM-007 (RPC de preguntas ausentes en vivo) · EXAM-001 (la ráfaga de móvil borra código, descripción y «oficial») · EXAM-002 (`total_preguntas`) · EXAM-003 a EXAM-006 · DEBT-021 a DEBT-039.

### Batch 02 — workspace completo (2026-09-12)

Completadas las cinco pestañas restantes en modo **UI FACTORY** (implementar → renderizar → test dirigido → revisión ligera), con **una sola revisión independiente del conjunto** al final. Información básica quedó cerrada antes (`BASIC_INFO_UI_FOUNDATION = PASS`) y **no se re-auditó**: se comprobó que este batch no la rompe, con 49 de 49 capturas idénticas byte a byte.

**Estado por pestaña**: CONFIGURATION · QUESTIONS · RESOURCES · ACCESS · PUBLICATION = **UI_COMPLETE**. Revisión independiente: `EXAMS_WORKSPACE_UI_FOUNDATION_READY`, con P0 y P1 en NONE y cinco P2 registrados como deuda (DEBT-041 a DEBT-044 y el KDoc ya corregido).

**Configuración.** Tres tipos canónicos (Ráfaga, Razonado, Inmersivo) con su compatibilidad real por especialidad y el `mensaje_limitacion` del contrato, dicho una sola vez. La disponibilidad por tipo tiene **tres** estados, no dos: permitido, no permitido y **sin comprobar** — con las capacidades cargando o caídas, antes se afirmaba «No permitido», que es decidir por un servidor que no ha contestado. Duración y «oficial» editables; el número de preguntas es de sólo lectura y sale de las preguntas vinculadas. El precio vive sólo en Acceso.

**Acceso.** Separa **visibilidad** de **acceso**: el tipo de acceso no decide si el examen se ve, eso es Publicación. Dos afirmaciones honestas salidas de verificar el backend: la consola **no calcula** qué usuario puede abrir un examen (`canUserAccessExam` es de backend remediation) y el precio en OposiTickets **se guarda pero hoy no desbloquea nada**, porque ninguna compra de examen suelto lo consulta. Las ayudas que afirmaban lo contrario se corrigieron. Sin sistema de entitlement nuevo.

**Publicación.** Cuatro zonas separadas: estado actual, avisos, acciones inmediatas y resultado. «Publicar ahora» (no hay publicación programada en escritorio), «Despublicar» y «Archivar», con confirmación y aplicadas al momento, fuera de «Guardar cambios». Si hay cambios sin guardar, se avisa de que publicar publica la versión guardada. «Antes de publicar» es orientación: el servidor no exige nada para publicar. **Desarchivar no existe**: publicar un examen archivado lo reactiva ya publicado, y el botón lo dice.

**Preguntas.** Listado con posición, enunciado, tipo y las acciones que existen. Ordenar, desvincular y archivar son **inmediatas** y se avisa de ello; desvincular deja la pregunta en el banco y archivar la retira de todos los exámenes. Crear preguntas y editar opciones sigue **bloqueado por P0-5/EXAM-007**: el botón está deshabilitado con el motivo al lado, nunca un clic que sólo puede acabar en error. Vincular una pregunta existente no tiene ruta y no se inventa.

**Recursos.** Vista agregada de sólo lectura: los recursos pertenecen a la pregunta, nunca al examen. **Sólo las preguntas inmersivas pueden llevarlos**; el backend no lo impone, así que un recurso colgado de otra cosa se enseña como anomalía diciendo que **no consta** como inmersiva. Un recurso cuya URL es una ruta local se marca «Archivo no disponible: se guardó una ruta local en lugar de subirlo» **sin enseñar nunca la ruta**: es la cara visible de **P0-4/EXAM-008**. Subir sigue bloqueado y no se ofrece.

**Contrato de cliente**: `ExamQuestion` gana `type: QuestionType?`, mapeado con `QuestionType.fromExternal`; un valor desconocido se enseña como «Tipo sin identificar», nunca disfrazado. Límite registrado en DEBT-040. **Backend, Supabase, migraciones y RPC: sin tocar.**

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE`.

| Eje | Estado |
|---|---|
| `VISUAL_RENDER` | VERIFIED — capturas del harness de jvmTest por pestaña, claro y oscuro, de 1080 a 2560, medidas por el revisor independiente |
| `AUTOMATED_CONTRACT` | VERIFIED — `features:exams` 254/0 · `core:ui` 79/0 · `data` correcto; compilan JVM y Android; `git diff --check` limpio |
| `LIVE_DATA_RUNTIME` | **NOT_VERIFIED** — no se arrancó Ktor |

**Encontrado y no corregido aquí**: EXAM-009 (un Ráfaga gratuito en borrador sirve sus preguntas sin sesión a quien sepa su id).

## Noticias V2 — editor de escritorio (2026-09-12)

`NEWS_EDITOR_UI_FOUNDATION_READY`. `NEWS_EDITOR=PARTIAL` para la funcionalidad completa: la base visual está implementada y revisada, pero la integración remota no está cerrada.

Biblioteca con búsqueda, filtros por nombres y estados de carga/error/vacío; editor separado en Contenido, Clasificación, Imagen y estilo y Capas visuales; composición con imágenes reales y ampliación; publicación/programación y zona de riesgo; barra fija de guardado. Tres zonas en ancho amplio, dos columnas con preview debajo en estándar y biblioteca navegable en compacto. Tokens existentes de claro/oscuro; sin datos de ejemplo en producción ni cambios al diseño global.

Operaciones existentes: borrador local (solo último snapshot, sin fecha programada), publicar por upsert, programar por fecha/estado, archivar versión del servidor y eliminar. Selección/carga de imágenes local y subida mediante el contrato existente al publicar/programar. No se añadieron resumen, formato enriquecido, despublicar ni guardado remoto que preserve el estado. La vista previa describe la composición, no certifica el render consumidor de OposiBot. Archivar confirma explícitamente el descarte de la copia local.

`VISUAL_RENDER=VERIFIED`: harness Compose real en `features/news/src/jvmTest`, fixtures exclusivamente en test, capturas en `features/news/build/reports/news-ui/`; 1920 claro/oscuro/existente/nueva/capas/imagen, 1600 programada, 1366 existente y 1080 error/biblioteca. No es una captura del shell completo ni evidencia de datos reales. Revisión independiente: sin P0/P1 UI pendientes; P2 de densidad de biblioteca a 1366.

`AUTOMATED_CONTRACT=VERIFIED`: 33 tests JVM de Noticias, 0 fallos; `:composeApp:compileKotlinJvm` y `:composeApp:compileDevDebugKotlinAndroid` correctos. Incluye errores de refresco/guardado local, clasificación sin selección inventada, archivo de versión remota, formato de fecha y tamaños responsive. No certifica runtime Android/iOS ni publicación E2E.

`LIVE_DATA_RUNTIME=BLOCKED`: salida del Ktor previamente arrancado mostró `GET /content/noticias` fallando con `PGRST205 public.noticia` (2026-09-12). No hubo nueva inspección directa de Supabase ni reparación de backend. Se conserva error con reintento y publicación deshabilitada mientras la biblioteca no se actualice correctamente. Próximo paso separado: remediar el contrato remoto y verificar lectura/publicación/subida con autorización; revisar la persistencia local y la activación automática de programadas. Ver DEBT-NEWS-001/002.

`DATABASE_CHANGES: NONE`. `MAIN_WRITES: NONE`. Sin SSH, infraestructura, migraciones ni commits en esta tarea. Cambios ajenos de Exámenes/Preguntas/Importación y componentes compartidos protegidos.

## Importación V2 — workspace de escritorio (2026-09-12)

`IMPORT_UI_FOUNDATION_READY`. La base visual está implementada y revisada; lo que queda abierto lo limita el **contrato**, no la pantalla.

**Flujo en cuatro etapas** —tipo · archivo · revisión · publicación— con indicador permanente que lleva el dato real de cada una (tipo elegido, nombre del archivo, número de filas, estado del lote) y estado en texto además de color. Dos zonas en ancho amplio: flujo a la izquierda, rail de guía y resumen a la derecha; apilado por debajo de 1040dp de contenido. Al abrir un lote las tarjetas de tipo desaparecen y su sitio lo ocupa la revisión.

**Los tres tipos, con el contrato REAL de importación.** Ráfaga admite enunciado, opciones A–D, correcta y tema, y rechaza explicación, fuente y recursos. Razonada añade explicación y fuente. Inmersiva admite lo mismo que Razonada y **declara que los recursos hay que vincularlos después desde Preguntas**: el staging no tiene ningún campo donde ponerlos. El alias legacy `guiado` se lee como Razonada y nunca se ofrece ni se escribe.

**Lo que el contrato NO tiene, y la pantalla dejó de fingir.** `ContentImportStagingItem` no trae `ID_GLOBAL`, ni `ID_EXAMEN` por fila, ni código de tema, ni `ID_AUDIO`/`ID_VIDEO`/`ID_IMAGEN`. El examen destino es **del lote** y se elige en la pantalla, no en el PDF; el tema llega como texto libre y no se resuelve contra ningún catálogo. Tampoco existe código humano de lote (`IMP-000042`): el lote se identifica por su archivo, nunca por su identificador interno. El panel de códigos anterior anunciaba esos seis campos con ejemplos, lo que hacía preparar PDF con columnas que el servidor ignora; se sustituyó por «Qué espera el PDF», que enumera sólo lo que se lee.

**Parser y staging.** El PDF lo analiza el servidor al subir; el cliente no parsea, no revalida y no puede mostrar etapas intermedias inventadas. La clasificación de cada fila (lista · omitida · duplicada · error) llega del backend, y el motivo de un error se enseña **literal**, sin reescribirlo; si el servidor marca error sin explicar, se dice así. Editar filas en staging sí es real (`updateStaging` valida las cuatro opciones y que la correcta sea A, B, C o D) y no toca contenido publicado.

**Publicación.** Acción explícita, bloqueada mientras haya errores, con el motivo escrito bajo el botón; las filas omitidas no bloquean. **[SUPERADO 2026-09-19: la publicación ES atómica (RPC `admin_import_lote_preguntas`); el aviso «no atómica» y el estado `parcial` ya no existen en la UI.]** Texto de 2026-09-12: _la atomicidad no existía y se avisaba antes de pulsar_. DEBT-006B, PDF-002/PDF-003 e IMP-001 están cerrados.

| Operación | Nivel |
|---|---|
| Seleccionar tipo · examen destino · PDF · revisar y editar staging · omitir duplicado · publicar lote · ver recientes | REAL |
| Parsear PDF · validar filas · detectar duplicado | PARTIAL (lo hace el servidor; el cliente sólo presenta) |
| Publicar atómicamente | REAL (2026-09-19; antes BLOCKED) |
| Arrastrar y soltar · examen o tema por fila · recursos · ver la pregunta duplicada existente · descargar plantilla · descartar lote | NOT_FOUND |

| Eje | Estado |
|---|---|
| `VISUAL_RENDER` | VERIFIED — 18 capturas del harness de jvmTest, 1080–1920, claro y oscuro, revisadas una a una; corregidos en esa revisión la concordancia («1 preguntas») y el apilado de las tarjetas a 1366 |
| `AUTOMATED_CONTRACT` | VERIFIED — `:features:imports:jvmTest` verde (tests de presentación nuevos + los 10 de ViewModel intactos); `:composeApp:compileKotlinJvm` y `:composeApp:compileDevDebugKotlinAndroid` correctos; `git diff --check` limpio |
| `LIVE_DATA_RUNTIME` | **NOT_VERIFIED** — no se arrancó Ktor |

`DATABASE_CHANGES: NONE` (en esa entrega de 2026-09-12). `MAIN_WRITES: NONE`. Sin migraciones, SQL, infraestructura ni commits. Exámenes, Preguntas, Noticias y Dashboard sin tocar; el único cambio fuera del módulo es el subtítulo de la ruta en `AppNavigationCatalog`.

**Siguiente paso** (2026-09-12; la publicación atómica ya se aplicó, 2026-09-18): decidir/confirmar el contrato de plantilla PDF con el cliente. Cuando exista, la UI ya lo declara desde `importCapabilities()` — cambia esa matriz y la pantalla la sigue.

## Usuarios V2 — escritorio (2026-09-12)

`USERS_UI_FOUNDATION_READY`. La pantalla dejó de afirmar cosas que el backend no hace. Lo que queda abierto lo limita el **contrato**, no el diseño.

**La pregunta que abrió la vertical, respondida: `USER_POPULATION_SCOPE = AMBAS, MEZCLADAS`.** Un administrador de OposiControl no es otra cosa que una fila de `perfiles_usuario` con un rol `admin`/`superadmin` enlazado en `roles_usuario`; esta pantalla lista la tabla entera, así que opositores y administradores conviven en ella. Ahora hay columna **Población** y filtro, y el rol se reconoce por su **código canónico** (`CanonicalAuthorization.adminRoleCodes`, espejado en `ADMIN_ROLE_CODES`) y no por su nombre, que es una etiqueta reescribible. Existe además una **segunda población rival** —`public.usuario`, con CRUD propio en `/api/v1/admin/oposibots/usuarios`— que esta pantalla no consume y que sigue siendo territorio protegido.

**Tres mentiras retiradas.** (1) `User.isActive` **no es** activo/bloqueado: el backend lo calcula como `!es_invitado` (`AdminV1Routes.kt:2837,2858`), así que distingue invitado de registrado; la lista decía «Activo/Bloqueado» y la ficha «invitado/registrado» —dos significados del mismo bit en la misma pantalla—. Ahora es **Registrado/Invitado** en los dos sitios. (2) `User.email` **no es** el correo: `perfiles_usuario` no tiene esa columna y el backend rellena el campo con `username`; era editable y obligatorio para guardar, y el `PATCH` lo descartaba. Ahora es de sólo lectura, se llama «Nombre de usuario» y se dice dónde vive el correo real. (3) «Bloquear», «Activar» y «Eliminar» devolvían éxito sin persistir nada —`updateUsuario` escribe **sólo** `nombre_mostrar`— y se han retirado, en escritorio y en el panel móvil.

**Dos P0 de datos corregidos de raíz.** `observeUsers()` pasa de `Flow<List<User>>` a `Flow<AppResult<List<User>>>` en sus cuatro declaraciones: el origen convertía cualquier fallo en `emptyList()`, así que una caída del backend se pintaba como censo vacío con su botón de crear. Y la lista se quedaba en los primeros 50 (`AdminPageRequest.DEFAULT_LIMIT`) **sin decirlo**, porque el cliente descartaba el `total` que el backend sí devuelve; ahora se avisa encima de la tabla. El backend ya paginaba bien: el defecto era sólo del cliente.

**Identidad y protección.** Se retiró el `Field("ID", userId)` que pintaba el UUID de Supabase como identidad principal. Cambiar de usuario con cambios sin guardar pregunta antes de descartarlos. El cambio de roles avisa cuando **concede o retira** acceso administrativo de verdad —de admin a superadmin no avisa—. Hoy ese aviso es la única protección en ejecución: ver «Guarda de sincronización de roles» más abajo. [SUPERADO 2026-09-21: la guarda está instalada en MAIN (`20260921071916 role_sync_guard`); el aviso de la interfaz ya no es la única protección. Ver «Pre-server: guarda de roles instalada + smoke Users/Competición/Accesos».]

**Guarda de sincronización de roles (SEC-006) — ESCRITA, NO APLICADA.** [SUPERADO 2026-09-21: INSTALADA en MAIN como instalación LIMPIA, versión `20260921071916 role_sync_guard`; además, la premisa de abajo era incompleta: `admin_sync_roles` NO existía en MAIN, no «reemplazaba la lista sin comprobar». Ver la sección fechada 2026-09-21.] `admin_sync_roles` reemplaza la lista entera sin comprobar nada —un array vacío borra todos los roles, y nada impide que desaparezca el último superadministrador—. ~~**Eso sigue siendo cierto en ejecución**: el candidate está en `APPLY_PENDING_APPROVAL` y hasta que se apruebe y aplique, la única protección real es el aviso de la interfaz.~~ (SUPERADO 2026-09-21). Lo que sí está hecho es la garantía, escrita **dentro de la operación**, que es el único sitio donde protege: la RPC es alcanzable con la clave de `service_role` sin pasar por Ktor, así que una comprobación en el servidor de aplicación habría protegido a este cliente y a nadie más. Dos invariantes: un usuario administrativo no puede quedarse sin roles, y ninguna operación puede **provocar** la transición de ≥1 a 0 superadministradores —pasar de superadmin a admin con otro vivo sigue siendo legítimo—. Las tres comparaciones que deciden esas invariantes pasan por `admin_role_code_canonical`, que recorta el mismo conjunto de espacios que `trim()` de Kotlin —`Char.isWhitespace()`, una veintena de caracteres, no los cuatro de un `btrim` corriente— y baja a minúsculas con `collate "C"` para no depender de la intercalación: sin eso, un código `"superadmin"` seguido de U+000C era administrativo para Ktor e invisible para la guarda. El paquete incluye rollback (`002_rollback_admin_sync_roles.sql`), que declara en su cabecera que **revertir reabre SEC-006**. Serializadas con `pg_advisory_xact_lock` tomado antes de la primera lectura, porque la invariante es global y dos transacciones concurrentes no se ven entre sí; comprobadas después de escribir, de modo que el `raise` deshace la operación entera. Vive en `supabase/canonical/role_sync_guard_candidate_v1/` como `APPLY_PENDING_APPROVAL`: **no aplicado a MAIN**. Los cuatro códigos se traducen a errores humanos tipados en `BackendErrors.kt`, y `NetworkResultAdapter` dejó de sepultarlos: un 409/422 mostraba la cadena cruda entera —ruta, UUID del usuario y JSON— porque caía en la rama genérica.

**Reset de la arquitectura de verificación (2026-09-13).** Cinco revisiones independientes tumbaron el método con el que se certificaba este candidate. Se verificaba leyendo el **texto** del SQL con expresiones regulares, y cada ronda encontró otra forma sintáctica de dejar la guarda muerta con todo en verde: `raise notice` por `raise exception`, un `case when false` de señuelo que satisfacía los tokens del cuerpo, un `exception when others then null` que se tragaba el aborto dejando confirmada la escritura, una condición imposible añadida al `if`, una segunda definición en MAYÚSCULAS que pisaba a la guardada, y un abre-bloque de comentario dentro de un `--` que hacía que el propio limpiador se comiera el neutralizador antes de buscarlo. La lección no era «añade otra expresión regular»: era que la autoridad estaba en el sitio equivocado. Ahora `tests/verify_candidate.ps1` sólo responde del **paquete** —y lo declara imprimiendo su propio `DOES_NOT_PROVE:`—, `RoleSyncInvariantsTest` es **espejo y detector de deriva**, y la autoridad sobre las invariantes vive en `:backend:dbtest`, que levanta un PostgreSQL real y desechable, aplica **este mismo fichero** del repositorio y comprueba el estado de las tablas.

**El P0 que sólo se vio ejecutando.** Al pasar a `security definer` la RPC corre como su propietario, y `roles_usuario` tiene RLS. Con un propietario sin `BYPASSRLS` al que la política filtra la lectura —medido en PostgreSQL 16.2— los dos recuentos leían **0**, el `delete` tampoco veía filas, el `insert` entraba, y retirar `superadmin` dejaba al usuario en `[admin, superadmin]` **devolviendo éxito**: revocar un privilegio no hacía nada y nadie se enteraba. Cerrado con `set row_security = off`, que no concede acceso sino que convierte el filtrado silencioso en error duro. Ninguna revisión por lectura lo habría confirmado: la primera reproducción falló *cerrada* por un motivo incidental y sólo la configuración que permite escribir pero filtra al leer expuso el fallo abierto.

**Cambio de contrato hacia arriba.** `Role` gana `code`: el backend ya lo enviaba y `RoleMapper`/`RolesApi` lo descartaban, así que el cliente no podía distinguir un rol de administración de uno de usuario. Aditivo, con valor por defecto, y los dos únicos sitios que construyen `Role` usan argumentos nombrados. `UpsertUserUseCase` deja de validar `"@" in email`: validaba un `username` que el backend descarta, así que impedía guardar el nombre de cualquiera cuyo usuario no pareciese un correo.

| Operación | Nivel |
|---|---|
| Editar nombre · asignar roles · conceder OposiTickets | REAL |
| Advertir | PARTIAL (escribe auditoría; no llega ningún aviso al usuario) |
| Cambiar el nombre de usuario · crear usuario · eliminar usuario | BLOCKED |
| Bloquear o desbloquear · invitar por correo · ver el último acceso | NOT_FOUND |

| Eje | Estado |
|---|---|
| `VISUAL_RENDER` | VERIFIED — 23 capturas del harness de jvmTest, 1080–1920, claro y oscuro, revisadas una a una. En esa revisión se corrigieron cuatro defectos que ni el compilador ni los tests veían: el chip «Moderador» escrito en vertical (un `Row` que no envuelve), las columnas decididas con el breakpoint de VENTANA en un panel de 525dp, el recuento y el aviso de truncamiento empujados fuera del viewport por `AdminTable`, y un botón «Nuevo usuario» que el harness pintaba y la pantalla real no tiene. Quedan **dos P2 abiertos** vistos en esa misma revisión: el estado de carga pinta una tarjeta vacía, sin esqueleto ni texto (`users-loading.png`), y el diálogo del alta ofrece «Cerrar» y «Entendido», dos botones con el mismo efecto, porque `ConfirmDialog` no tiene variante de un solo botón |
| `AUTOMATED_CONTRACT` | VERIFIED — `:features:users:jvmTest` verde (23 tests nuevos de la capa pura + los 10 que había que preservar); `:composeApp:jvmTest`, `:core:network:jvmTest` y `:backend:ktor:test` (351 tests) verdes; `verify_candidate.ps1` → `CONTRACT_TEST_PASSED`; `git diff --check` limpio. **Diez** mutaciones ejecutadas de verdad y revertidas, con verde final comprobado. (A) quitar la comprobación del último superadmin y (C) dejar la RPC sin guarda con la protección sólo en Kotlin → rojo en `laGarantiaDefinitivaSigueEnElSql` y `losCodigosDelSqlYLosDeLaEspecificacionSonLosMismos`; (B) permitir la lista vacía → rojo en `unUsuarioAdministrativoNoPuedeQuedarseSinRoles`; (D) comentar la guarda con `--` y (E) envolverla en `/* … */` → rojo en los mismos dos tests y en el verificador, con el código aún presente. **Las cinco siguientes las construyó la tercera revisión independiente, y todas pasaban las guardas anteriores**: (G) vaciar la guarda y dejar los tokens en un comentario al FINAL de línea → `laGarantiaDefinitivaSigueEnElSql`; (H) des-normalización **parcial**, quitando el helper sólo de los dos recuentos de superadmins y dejándolo en el tercero → `lasTresComparacionesNormalizanPorElMismoCamino`; (I) `and 1=0` en el recuento previo → `ningunaGuardaQuedaPresenteYMuerta`; (J) una segunda definición de la función que pisa a la guardada → `hayUnaSolaDefinicionDeCadaFuncion`; (K) un carácter no ASCII en el SQL ejecutable, que es como entraría un homóglifo en un literal de rol → `elSqlEjecutableEsAsciiYConservaLosLiteralesCanonicos`. La H es la más instructiva: comprobar que la forma normalizada aparece «alguna vez» en el fichero dejaba pasar una guarda a medio normalizar. Ahora se cuenta **por comparación**, no por fichero |
| `ROLE_SYNC_GUARD` | ~~**DESIGNED / APPLY_PENDING_APPROVAL**~~ **SUPERADO 2026-09-21: INSTALADO en MAIN (`20260921071916 role_sync_guard`, VERIFIED_LIVE)** — texto histórico: escrito, verificado estáticamente y probado por mutación, pero **NO aplicado a MAIN**. ~~Hasta que se apruebe y aplique `role_sync_guard_candidate_v1`, la única protección en ejecución sigue siendo el aviso de la interfaz.~~ Cuatro revisiones independientes: la tercera devolvió NOT_READY con 6 P1 y la cuarta con 2 P0 + 6 P1, todos cerrados después. Lo que encontraron no era la lógica de la guarda sino **sus verificadores**: contar los usos del canonicalizador sin aseverar su cuerpo, y aseverar los tokens `ROLE_SYNC_*` sin exigir `raise exception`, permitían vaciar el helper o degradarlo a `raise notice` con todo en verde. Ahora se asevera el cuerpo (`regexp_replace`, `lower`, `collate "C"` y los diez tramos de la clase de espacios), el `raise exception` de cada código, la condición exacta de la invariante B, y el preflight del ACL **aborta** la migración en vez de sugerir una comprobación manual |
| `ROLE_SYNC_409_SPOOF` | CLOSED — el 409 de la guarda era falsificable desde el cliente: `mapSupabaseDomainError` busca sus códigos en TODO el cuerpo de Supabase, así que un `POST /usuarios/ROLE_SYNC_LAST_SUPERADMIN/roles` devolvía 409 con el mensaje de la guarda sin que ésta se ejecutara. Se validó primero sólo el cuerpo —dejando abierto el path, y con un comentario que afirmaba falsamente que estaba cerrado— y después también el path. Cerrada además la clase entera: `mapSupabaseDomainError` sólo traduce respuestas ≥400 y exige que el código **encabece** el campo `message`. `raise exception 'CODIGO'` lo encabeza; un eco de la entrada del cliente lo lleva incrustado. Quedan ~20 interpolaciones de id sin validar en otras verticales, fuera de alcance: DEBT-046 |
| `LIVE_DATA_RUNTIME` | **NOT_VERIFIED** — no se arrancó Ktor |

`DATABASE_CHANGES: NONE` (en esa entrega de 2026-09-12). `MAIN_WRITES: NONE`. Sin migraciones, SQL, infraestructura ni commits. Exámenes, Preguntas, Importación, Noticias y Dashboard sin tocar; fuera del módulo sólo se tocaron el mapeo de `Role`, el caso de uso de guardado, la cadena de `observeUsers` y el panel móvil de usuarios, que repetía las mismas mentiras.

**Siguiente paso** [SUPERADO 2026-09-21 en lo relativo a la guarda: ya aplicada; sigue abierta la decisión sobre `public.usuario`]: aprobar y aplicar `role_sync_guard_candidate_v1` —confirmando antes contra el esquema vivo que `roles_app.codigo` no tiene más roles privilegiados que `superadmin` y `admin`—, y decidir si el panel debe consumir la segunda población (`public.usuario`) o declararla fuera de alcance. La UI ya lo declara desde `userCapabilities()`: cambia esa matriz y la pantalla la sigue.

## INICIO + CONTENIDO closure — Dashboard, Exámenes, Preguntas, Importación (2026-09-18)

`CONTENT_VERTICALS_CLOSED`. The four verticals — Dashboard (INICIO) and Exámenes/Preguntas/Importación (CONTENIDO) — are closed end-to-end against MAIN (`ndiooyyqtaeysnedywer`): UI → ViewModel → domain → data → Ktor → Postgres, with live HTTP and SQL proof, not just green unit tests. Users, Noticias, OposiRed, Moderación, Soporte, Monetización and Sistema were explicitly out of scope and untouched.

**Six migrations applied to MAIN**, sequentially, each with preflight/review/apply/live-verify/tests before the next: `20260918095440_exams_questions_many_to_many` (M2, drops the old one-question-one-exam unique constraint), `20260918095539_exams_questions_parent_guards` (M3, blocks changing an exam's/question's type or specialty while linked), `20260918095646_exams_questions_order_and_link_rpcs` (M4, the three RPCs — `admin_link_question_to_exam`, `admin_unlink_question_from_exam`, `admin_reorder_exam_questions` — that are now the only door for the M:N relationship), `20260918110809_exams_codigo_examen_not_blank`, `20260918111232_draft_content_rls` (M7, closes EXAM-009: draft exam content is no longer readable by non-admins), `20260918112140_import_staging_states` (IM1, adds the human-review states `aprobada`/`omitida` to staging), and `20260918113100_import_lote_rpc` (IM3, the atomic all-or-nothing publish RPC for an import batch, reusing M4's link RPC and Q2's question-creation RPC inside the same transaction).

**Governance note, for anyone reading git history and wondering about the gap:** M5/M7/IM1/IM3 were actually applied to MAIN by an external process outside this session's own review pipeline (referred to as "Codex" in the session), including IM3, which the owner's authorization at the time did not cover. Discovered mid-session (a live test fixture this session had just created and confirmed vanished without this session deleting it). The owner was informed, ratified IM3 after an independent retroactive audit proved it technically sound, and confirmed sole coordinator/writer status going forward. All seven migrations were then independently re-verified by this session against live MAIN data before proceeding — see `scratchpad/COORDINATOR_DECISIONS.md` if that file still exists in whatever produced this session's temp directory (it does not live in the repo).

**Live E2E loop, all four verticals, real HTTP + SQL, not mocked:** Dashboard 9/9 steps pass. Exámenes 80/80 (create/edit/publish/unpublish/archive lifecycle, the full M:N link/unlink/reorder surface, exam cover upload with Storage, EXAM-009 proven closed via real anonymous and non-admin PostgREST calls returning nothing for a draft exam). Preguntas: full create/edit/archive/restore/link/unlink/resource-upload surface green. Importación: real PDF uploads through PDFBox parsing → staging → human review → atomic publish, with IM3's atomicity properties each proven against real data, not simulated — a forced mid-publish failure left zero rows written, calling the publish RPC twice was a true no-op on the second call, and two genuinely overlapping publish calls on the same batch (one held open for 3 seconds specifically to force the race) produced exactly one result with zero duplication.

**Two real bugs found live and fixed, both outside what any unit test had exercised:**
- The bulk staging-row insert during PDF import (`crearLoteDesdePdf`) broke with a PostgREST `PGRST102 "All object keys must match"` 500 whenever a real multi-question PDF produced rows with different null patterns (the normal case — a row with a parser warning has different populated fields than one without) because the shared JSON serializer omits null fields per-row, making the array heterogeneous. Fixed with a dedicated `sbJsonBulk` (`explicitNulls = true`) used only for that one bulk-array call site.
- `AdminPreguntaDto`/`AdminLoteDto`/`AdminStagingItemDto` were silently omitting several fields (`activa`, `tipoPregunta`, `tipoExamen`, `estado`, `totalImportadas`, `duplicadosComprobados`, `estadoParseo`) from every response whenever the value happened to equal the field's own Kotlin default — because those fields lacked `@EncodeDefault`, which this codebase already established as the fix pattern for `AdminExamenDto`. Same fix applied here (see also DEBT-040, now closed, which had already flagged the `AdminPreguntaDto` half of this from an earlier session but with a since-stale line reference).

**Nuclear Review (correctness/security) found 2 issues, 1 fixed:** `uploadExamenPortada`/`clearExamenPortada` read-then-wrote an exam's cover unconditionally with no optimistic-concurrency check, so two concurrent calls on the same exam would silently last-write-wins and leave the loser's freshly-uploaded Storage object permanently orphaned. Fixed with a conditional PATCH (`portada_storage_path=eq.<value read>`) and a new `EXAM_COVER_CHANGED_CONCURRENTLY` 409 on conflict, verified both mechanically (a mismatched PostgREST condition matches zero rows) and through the real endpoint (happy-path upload→reupload→clear cycle still works, zero Storage residue). The second issue — import lote creation has no idempotency key and no delete/archive route, so a retried upload permanently duplicates a batch — was deliberately left unfixed (real published-content duplication is already prevented by IM3's publish-time dedup lock; the actual fix is a small product decision, not a bug fix) and is tracked as DEBT-047.

**Code Quality Review found 7 issues, 5 fixed, 2 deferred:** fixed — `AdminAnalyticsGateway` still used raw `System.err.println` instead of the established `Logger.warn` + `logStrategicEvent` pattern (the documented F-12b sweep had missed this file); four hand-rolled `BackendDomainException` construction sites replaced with the centralized `domainError()` lookup; the three exam-lifecycle client methods (`publishExam`/`unpublishExam`/`archiveExam`) now reuse the `postExamState` helper instead of duplicating its shape; an orphaned KDoc comment was moved to the declaration it actually describes; and `DeleteQuestionUseCase` plus its dead `ContentRepository.deleteQuestion` method (zero real callers, confirmed by repo-wide grep, three production implementations and five test fakes existing only to satisfy the interface) were deleted outright. Deferred — a large `ExamsViewModel.saveExam()` decomposition (DEBT-048) and missing UI-test coverage on the V1 dashboard screens, which are confirmed still wired into `AppRoot.kt` and not dead code (DEBT-049), both real but out of proportion for a fix-only pass.

Full regression after every fix: `:backend:ktor:test` (710 tests) plus `:domain:jvmTest`, `:data:jvmTest`, `:features:dashboard:jvmTest`, `:features:exams:jvmTest`, `:features:questions:jvmTest`, `:features:imports:jvmTest`, `:features:specialties:jvmTest`, `:core:common:jvmTest`, `:core:network:jvmTest`, `:core:ui:jvmTest` all green, zero failures. Mutation testing earlier in the same effort: 88 mutations across the foundation, Dashboard, Exámenes, Preguntas and Importación, 0 survivors after fixes.

`DATABASE_CHANGES: 7 migrations applied to MAIN (listed above)`. `MAIN_WRITES: none outside the applied migrations and their live verification (all test data created was cleaned up; MAIN's real row counts are unchanged from before this session)`.

## Bugfix — Preguntas → Vincular a examen (2026-09-18)

Reported: opening a question and clicking "Vincular a examen" showed the picker (exam selector, position field, "Vincular" button) but linking never worked, screenshot subject PREG-000001 (rafaga at the time).

**Root cause was NOT the link mechanism** — live reproduction against MAIN (`ndiooyyqtaeysnedywer`) traced the full chain (UI → ViewModel → repository → Ktor → Postgres/RPC) and found `questionLinkableExamOptions` (the compatible-exam filter), `refreshExamCaches()`, and `GET /api/v1/admin/examenes` all correct and unfiltered — rafaga is not excluded, no `publicado_en` gating, correct rafaga/razonado/inmersivo↔rafaga/razonada/inmersiva mapping. PREG-000001's actual specialty (`b250a94f-b0a9-4f5d-af78-408f46198eed`) genuinely has zero exams of any type in MAIN — a real data fact, not a bug. The real, user-visible bug was in `QuestionLinkExamDialog` (`features/questions/.../QuestionsScreen.kt`): it rendered the identical empty dropdown whether the exam catalog was still loading, had failed to load, or had genuinely loaded with zero compatible candidates — three distinct facts collapsed into one confusing state, with "Vincular" still clickable.

**Fix**: new sealed interface `QuestionLinkableExamsView` (`Loading`/`Unavailable`/`NoCandidates`/`Options`) and pure function `questionLinkableExamsView(exams, type, specialtyId)` in `QuestionsPresentation.kt`; `QuestionLinkExamDialog` now branches on it with distinct copy per state (including the required "No hay exámenes compatibles disponibles." for the genuine-empty case) and disables "Vincular" when there's nothing to pick. New non-tautological unit test in `QuestionsPresentationTest.kt` covers all four branches. Files: `features/questions/src/commonMain/kotlin/com/oposicontrol/admin/features/questions/presentation/QuestionsPresentation.kt`, `.../QuestionsScreen.kt`, `features/questions/src/commonTest/kotlin/com/oposicontrol/admin/features/questions/presentation/QuestionsPresentationTest.kt`.

**Live-verified against MAIN**, full TEST1-7 matrix from the bugfix brief, using disposable `E2E_TEST_LINK_*` fixtures (a rafaga question in specialty TCAE): link succeeds for a compatible rafaga exam; re-linking the same pair is idempotent (no duplicate row); linking a razonado exam is rejected (422 `question_exam_type_mismatch`); linking a rafaga exam in a different specialty is rejected (422 `question_exam_specialty_mismatch`); linking a second compatible exam produces true M:N coexistence (both links returned together); unlinking one leaves the other intact and the question `activa=true` globally; position with no explicit value appends at the end of that exam's own question list. All fixtures deleted afterward, zero residue in `preguntas`/`examenes`/`preguntas_examen`.

**Independently certified** by a separate reviewer subagent (not the implementer) who re-read the diff, re-ran a fresh set of its own `E2E_REVIEW_*` fixtures against MAIN, and re-ran `:features:questions:jvmTest` itself after finding the implementer had accidentally cited a regression log that omitted that module: ROOT_CAUSE_CONFIRMED=YES, RAFAGA_PICKER=PASS, DRAFT_ADMIN_PICKER=PASS, LINK=PASS, MANY_TO_MANY=PASS, UNLINK=PASS, ERROR_NOT_SILENT_EMPTY=PASS, REGRESSION=PASS. Full regression green: `:features:questions:jvmTest`, `:features:exams:jvmTest`, `:data:jvmTest`, `:backend:ktor:test`, `:composeApp:compileKotlinJvm` (Desktop).

**Disclosed side effect**: cleanup needed to unlink the fixture from a pre-existing labeled test exam `TCAE-TEST-RAF-001` (id `22222222-2222-2222-2222-222222222222`), which was blocked by the (correct) `exam_published_would_be_empty` guard because its only other linked question (`TCAE-TEST-RAF-P001`) was already `activa=false` before this session. Unpublishing it via the real admin API to remove the fixture link left it `despublicado`; it could not be republished afterward because it now has zero active linked questions — a pre-existing gap in that fixture's data, not something this session introduced. Not production content, but flagged here since it's a net change from this session's starting state; owner may want to either activate `TCAE-TEST-RAF-P001` or link a real question to republish it.

**Governance note**: the independent reviewer found PREG-000001's `tipo_pregunta` is now `inmersiva` in MAIN, not `rafaga` as in the owner's original screenshot — a drift that happened between this session's own earlier read and the reviewer's later one. Doesn't affect the diagnosis (its specialty has zero exams of any type regardless), but is a fresh instance of the concurrent-MAIN-writer risk already logged above (2026-09-18 governance note) — worth the owner's attention if another agent session is active in parallel.

`DATABASE_CHANGES: none (no migrations; only disposable E2E_TEST_LINK_*/E2E_REVIEW_* rows, all deleted)`. `MAIN_WRITES: TCAE-TEST-RAF-001 left despublicado, see disclosed side effect above — the only non-reverted change to pre-existing MAIN data`. Commit `87f347f`, pushed to `integration/oposicontrol-pre-infra`.

## Working-tree preservation snapshot — Users, Noticias, Especialidades, role sync (2026-09-19)

**Certification status, stated plainly so "in GitHub" is not read as "certified":**
- **Inicio + Contenido (Dashboard, Exámenes, Preguntas, Importación): CERTIFIED** — see the closure and bugfix sections above.
- **Users, Noticias, Especialidades: PRESERVED IN GITHUB, certification still pending.** Their unit tests and desktop compile are green, but they have not had the live end-to-end pass against MAIN that Inicio + Contenido had.
- **`supabase/canonical/role_sync_guard_candidate_v1/` (SEC-006): PRESERVED, `APPLY_PENDING_APPROVAL`, NOT applied to MAIN.** Its behavior is proven only against a disposable embedded PostgreSQL (`:backend:dbtest:test`), never against MAIN. **[SUPERADO 2026-09-21: aplicada a MAIN como `20260921071916 role_sync_guard` y probada en vivo; ver la sección 2026-09-21.]**

Branch `integration/oposicontrol-pre-infra`. Commits: `7973a5d` users, `8e1eb3f` news, `8f4170e` specialties (also updates `SpecialtyCapabilitiesViewModelTest`, left stale by the unified `Save` intent), `b282d6e` role-sync invariants + new `:backend:dbtest` module, `94a4a21` role-sync SQL candidate, `b76a777` `dry_run_fixture/002_content_live_shape.sql` (kept: `ContentDbHarness.FIXTURE` hardcodes its path), `468fc9b` CRLF fix below.

**Lesson from verifying in a clean worktree:** `core.autocrlf=true` and no `.gitattributes` means a fresh checkout gets CRLF while blobs are LF. `RoleSyncMutationTest` matches multi-line patterns with a pure LF, so three mutations failed with "la mutacion no cambio nada" on any fresh checkout while passing in the original working tree, where the file had never been through a checkout. Fixed in `CandidateFiles.leer()` by normalizing CRLF. Any new test that string-matches multi-line repo files needs the same care.

`DATABASE_CHANGES: none`. `MAIN_WRITES: none`. Role sync NOT applied to MAIN. (SUPERADO 2026-09-21: aplicada, ver abajo.)

## Contract correction — questions do not belong to a specialty (2026-09-19)

**Decision, in the owner's words: PREGUNTAS NO PERTENECEN A ESPECIALIDADES. La especialidad pertenece al EXAMEN. La compatibilidad Pregunta ↔ Examen depende del tipo.** Question `rafaga` ↔ exam `rafaga`, `razonada` ↔ `razonado`, `inmersiva` ↔ `inmersivo` (legacy `guiado/guiada` read-only, never written). The relation stays M:N (position belongs to `preguntas_examen`); one question can sit in exams of different specialties, in draft or published. Linking needs only compatible type + no existing relation + ids + optional position (empty = end). A question can be created with no exam.

**Root cause.** The earlier contract (KQ0-5/KQ2-4/M3) copied a specialty rule from exams onto questions: `preguntas.especialidad_id NOT NULL`, `QUESTION_EXAM_SPECIALTY_MISMATCH` in `validar_pregunta_examen`/`admin_link_question_to_exam`/`admin_create_question`, a mandatory "Especialidad" selector when creating a question, a specialty filter in the bank, and a link picker filtered by the question's specialty — so a question in one specialty could never reach an exam of another.

**What changed.**
- **DB (MAIN)** migration `20260919085404_questions_without_specialty` (M8) — see `docs/database/MIGRATION_STATE.md`. Final state of `preguntas.especialidad_id`: **removed** (only a trigger, an FK and an index depended on it). Backup + exact previous definitions in `private.*`, rollback in `supabase/canonical/questions_without_specialty_m8/ROLLBACK.sql`.
- **Ktor** (`AdminV1Routes.kt`, `BackendErrors.kt`): `AdminPreguntaDto/Row`, `AdminCreateQuestionRpc`, list query and columns lose `especialidadId`; `GET /preguntas` ignores an old `especialidadId` param (no 400, no filter); `POST /preguntas` ignores a stray `especialidadId`; error codes `QUESTION_EXAM_SPECIALTY_MISMATCH`, `QUESTION_SPECIALTY_REQUIRED`, `QUESTION_SPECIALTY_NOT_FOUND` retired; feedback detail no longer resolves a question specialty.
- **Client**: `QuestionsRepository.createQuestion` and `QuestionListQuery`/`QuestionDetail` lose the specialty; the exam editor no longer sends one when it creates a question.
- **UI (Preguntas)**: creation form now shows only "Examen inicial (opcional)" with "Sin examen inicial"; the exam picker (creation and "Vincular a examen") lists ALL exams of the compatible type — any specialty, draft/published/archived, labelled with specialty and state — excluding only incompatible types and exams the question already belongs to; loading / real empty ("No hay exámenes compatibles disponibles.") / error are distinct states; the bank has no specialty filter.

**Live verification against MAIN** (Ktor real, fixtures `E2E_TEST_M8_*`, all deleted; MAIN back to 3 questions / 3 exams): 21/21 checks PASS — question created with no specialty and no exam; linked to a draft rafaga exam of TCAE and to one of another specialty (M:N across specialties); rafaga→razonado exam rejected `question_exam_type_mismatch`; razonada→razonado and inmersiva→inmersivo linked; repeating a relation is idempotent (no duplicate row); create with initial exam creates the relation; explicit position inserts and shifts, explicit reorder works; unlinking removes only that relation (question stays active, exam and the other link remain); list/search/type filter/edit/archive work and type change stays locked while linked. Import RPCs exercised on MAIN: the duplicate was found across specialties (bank dedupe by type), publishing with it undecided raised `IMPORT_DUPLICATES_UNRESOLVED`, and with "link" decided created 1 new question (no specialty, linked) and linked 1 existing question of another specialty.

**Tests.** `:backend:ktor:test`, `:data:jvmTest`, `:domain:jvmTest`, `:features:{questions,exams,imports,specialties,users,news,dashboard}:jvmTest`, `:backend:dbtest:test`, `:composeApp:compileKotlinJvm`: green. New/rewritten: creation without specialty is valid (VM, save bar, Ktor, data), specialty param/field ignored, candidates by type across specialties and states, already-linked excluded, error ≠ empty, initial exam travels in the same call.

**M9** (`20260919090808_exams_specialty_change_unlocked`): a review found the exam-side twin of the old rule — `trg_examenes_bloquear_cambio_tipo` also blocked changing an exam's SPECIALTY while it had questions. Specialty no longer takes part in linking, so the trigger now guards `tipo_examen` only (type stays locked; live-proven: specialty change on an exam with questions → 200, type change → 409). Error text updated; rollback `ROLLBACK_M9.sql`.

**Rollback proven**: `ROLLBACK.sql` was executed inside a transaction on MAIN (then rolled back): column restored `NOT NULL` with the 3 rows, 13-arg signature and ACL back, 5/5 functions and the trigger byte-identical to the stored definitions. Preflight added after review: it aborts if a question already sits in exams of different specialties (the old rule cannot represent that).

**Independent review (4 reviewers, read-only; findings validated by trace before fixing).** Hashira A (UI/VM/contract): P0/P1 0; two real P2 fixed — the link dialog offered exams the question was already in while its links loaded/failed (now `questionLinkDialogView` waits for the links and has its own "links unavailable" state) and a capability matrix that contradicted the code (Crear/Vincular/Desvincular). Hashira B (Ktor/SQL): P0/P1 0; MAIN function bodies md5-equal to the migration file; two P2 — the exam trigger (fixed, M9) and no in-repo SQL harness for M8 (accepted as DEBT-050, mitigated by the live transactional rollback test and the live E2E). Thermos nuclear: P0/P1 0; P2 = BD/backend deploy skew (inherent to applying a schema change: a Ktor from before M8 gets PGRST202 on create and 400 on list until it is replaced — the running Ktor was rebuilt and restarted; nothing else serves MAIN in this pre-infra phase); P3 rollback preflight (fixed). Thermos quality: P0/P1 0; KDocs that lied about `especialidadNombre`, a stale PATCH KDoc, a dangling rollback reference, duplicated note mapping in the dialog, an unused test helper — all fixed. Decision kept on purpose: the picker does NOT hide archived exams (the brief says to exclude only incompatible types and already-linked exams); they are labelled "Archivado".

**Branch `integration/oposicontrol-pre-infra`, commits:** `320a0cd` (M8+M9 migrations, rollbacks), `95efbe5` (Ktor), `39204a0` (Preguntas client), then this docs commit. Final independent verification (Hashira C): root cause confirmed, no real P0/P1/P2 left. Full regression green with `--rerun-tasks`: `:backend:ktor:test`, `:domain:jvmTest`, `:data:jvmTest`, `:features:{users,news,specialties,questions,exams,imports,dashboard}:jvmTest`, `:backend:dbtest:test`, `:composeApp:compileKotlinJvm`.

**Remaining debt:** DEBT-050 (`docs/registry/TECH_DEBT.md`).

## Importaciones final + pregunta anulada (M10) — 2026-09-19

`IMPORT_PDF_FINAL_CLOSED_WITH_DEBT`. Cierre de la vertical Importaciones sobre el contrato definitivo de preguntas. Esta sección **corrige** lo que las anteriores decían falso: la publicación NO es «no atómica», el candidato `content_import_atomicity_candidate_v1` NO está pendiente de aplicar (superado por IM3, aplicada 2026-09-18) y `DEBT-006B`/PDF-002/PDF-003/IMP-001 están CERRADOS. Detalle operativo en `docs/product/domains/PDF_IMPORT.md`, `docs/database/MIGRATION_STATE.md` y `docs/integration/ENDPOINT_CATALOG.md`.

**Contrato de producto (M8/M9).** Las PREGUNTAS no tienen especialidad: la especialidad es del EXAMEN; la compatibilidad pregunta↔examen es sólo por TIPO y la relación es M:N (`20260919085404_questions_without_specialty.sql`, `20260919090808_exams_specialty_change_unlocked.sql`).

**M10 — pregunta anulada** (`supabase/migrations/20260919122139_annulled_questions_in_exams.sql`, aplicada en MAIN `ndiooyyqtaeysnedywer`; respaldo `private.m10_previous_definitions_20260919`; rollback y preflight en `supabase/canonical/annulled_questions_m10/`).
- La anulación por el tribunal pertenece a la relación `preguntas_examen` (`anulada boolean not null default false`; `motivo_anulacion text`, sólo con anulada, ≤ 500) y también a `preguntas_parseadas_staging`; **nunca a `preguntas`**. La misma pregunta puede estar anulada en un examen y normal en otro.
- Cambian: `admin_link_question_to_exam` (5 args), `admin_create_question` (14), `admin_import_lote_preguntas` (sigue siendo UNA transacción; devuelve `anuladas`; pide confirmación si cambia una relación de un examen publicado), `record_answer` (una anulada NO puntúa: no guarda respuesta y devuelve `es_correcta` NULL con la opción correcta) y el trigger de staging inmutable.
- **Orden de despliegue: M10 primero, Ktor después.**

**Puntuación (Ktor, `AppV1Routes.kt`).** `examenDetalle` sirve todas las preguntas activas en su posición con `anulada`/`motivoAnulacion` y `totalEvaluables`; `iniciar` dimensiona el intento con las evaluables (activas y no anuladas); `responder` devuelve `anulada=true` (`esCorrecta=false`) sin puntuar; `finalizar` excluye respuestas de anuladas y escribe `total_sin_responder = max(0, evaluables − respondidas)`. Fórmula del producto sin cambios: porcentaje = correctas/respondidas, puntuación = correctas. `examenes.total_preguntas` sigue siendo posiciones activas. **Contrato para la app móvil externa: leer `anulada` antes de `esCorrecta`.** El endpoint público de Ráfaga (`SupabaseV1Gateway`) también expone `anulada`/`motivoAnulacion`.

**Formato del PDF (parser real `RafagaParser.kt`, endurecido).** Separador ÚNICO `--- PREGUNTA ---` en línea propia (sensible a mayúsculas); un separador parecido abre una fila de ERROR y nunca fusiona preguntas; el texto anterior al primero es preámbulo ignorado (AVISO si tenía forma de pregunta); campo repetido = ERROR; claves exactas en mayúsculas `TITULO, A, B, C, D, RESPUESTA, RAZONAMIENTO, FUENTE, ETIQUETA` + `ESTADO`/`MOTIVO_ANULACION` opcionales; `ESTADO: ANULADA` anula, ausencia = normal, cualquier otro valor (incl. `ESTADO: NORMAL`) o `ESTADO ANULADA` sin dos puntos = error; claves mal escritas = error; marcador de página suelto = advertencia. Razonada/Inmersiva exigen `RAZONAMIENTO`; Ráfaga con `RAZONAMIENTO`/`FUENTE` queda `advertencia`. PDF digital ≤ 20 MB, sin OCR, máx. 200 preguntas por examen. Ejemplos copiar/pegar por tipo y con anulación en `PDF_IMPORT.md`. El cliente (Arturo) aún debe CONFIRMAR la plantilla.

**Estados vivos.** Lote `pendiente|parseado|revisado|importado|fallido`; fila `ok|advertencia|error|importada|aprobada|omitida` (la anulación es ortogonal al parseo). La publicación es atómica e idempotente (lote importado ⇒ no-op `ejecutado:false`; claim `FOR UPDATE` + advisory lock por tipo). Omitir una fila no exige completitud y borra el puntero de vínculo. Vincular a existente reutiliza la pregunta y crea sólo la relación.

**UI de escritorio (Importaciones).** Estados `importado`/`importada` correctos; preflight que espeja la RPC (errores, advertencias, duplicados sin resolver, incompletas, razonamiento, tipo, tope de 200 conservador, examen publicado con confirmación, lote pendiente/importado); «Vincular a existente»; chip «Anulada»; historial con `resumen` del servidor (sin ceros falsos); sin copy «no atómica».

**Tests (2026-09-19, contados por XML, 0 fallos/errores/omitidos).** `:backend:ktor` 804, `:domain` 81, `:data` 104, `:features:imports` 105, `:features:exams` 313, `:features:questions` 72; 13 PDF binarios reales en `backend/ktor/src/test/resources/pdf/` pasan por PDFBox → parser. **E2E LIVE contra MAIN** vía Ktor real (PDF → lote → staging → revisión → publicación → BD; usuario admin E2E desechable borrado, datos `E2E_TEST_M10_*` borrados; MAIN vuelve a 3 preguntas / 2 vínculos / 3 exámenes / 0 lotes): **74/74 checks** — tres tipos con anulada+motivo; reutilización (1 fila en `preguntas`, 2 relaciones); puntuación 5 preguntas/1 anulada (responder la anulada no puntúa: 4 evaluables, 3 respondidas, 2 correctas, 1 fallada, 1 sin responder, 66,67 %); atomicidad (fila en error ⇒ 409 y 0 escrituras; omitir sin completar ⇒ publica); retry (no-op) y dos publicaciones simultáneas (1 ejecutada + 1 no-op); omitir borra el vínculo. Dos revisiones independientes de sólo lectura (parser, datos, atomicidad, contrato, scoring, cliente, tests) sin P0/P1 abiertos. Thermos: `NOT_AVAILABLE` (skill inexistente en la sesión).

**Deuda real restante** (detalle y severidad en `docs/registry/TECH_DEBT.md` DEBT-051 a DEBT-066 y `docs/registry/RISKS.md` IMP-002, IMP-003, INT-001, STAT-001, TEST-001): P2 — `IMMERSIVE_RESOURCE_IMPORT` NOT_IMPLEMENTED (DEBT-051); PATCH de staging reemplaza la fila entera y un cliente antiguo la desanula (DEBT-055, aceptado); app móvil externa antigua que no lea `anulada` (DEBT-059); estadísticas agregadas no excluyen anulaciones posteriores (DEBT-060); sin test SQL reproducible de las RPC (DEBT-062). P3 — `ETIQUETA` no llega a `etiquetas_pregunta`, decisión de producto (052); mismo PDF dos veces = dos lotes (053); `crearLote` no transaccional (054); cliente nuevo contra BD sin M10 (056); tope de 200 del preflight conservador (057); pantalla compacta sin diálogo de examen publicado (058); escrituras directas a anuladas (061); drift de historial de migraciones, 18 versiones sin archivo (063); anular sólo vía importación (064); auditoría de `importarLote` sólo si `ejecutado=true` (065). Cerrado: examen con `descripcion` NULL rompía listado/importación (066).

`DATABASE_CHANGES: M10 aplicada a MAIN (una migración)`. `MAIN_WRITES: sólo la migración y el E2E live, con datos de prueba borrados`. `CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: NO` hasta que el cliente confirme el formato de plantilla.

## Pre-server: guarda de roles instalada + smoke Users/Competición/Accesos (2026-09-21)

**Certificación pre-servidor: NO quedan P0.** El P1 de `admin_sync_roles` y el P1 de Accesos están cerrados. Esta sección **corrige** lo que las anteriores decían falso o desfasado sobre role sync (marcado SUPERADO en su sitio: Usuarios V2, tabla de estado `ROLE_SYNC_GUARD` y preservation snapshot).

**Guarda de roles INSTALADA en MAIN** (`ndiooyyqtaeysnedywer`), instalación LIMPIA: versión `20260921071916 role_sync_guard` (`supabase/migrations/20260921071916_role_sync_guard.sql`, origen `supabase/canonical/role_sync_guard_candidate_v1/001`). **Corrección de la premisa:** antes de hoy `public.admin_sync_roles` NO EXISTÍA en MAIN (sólo `has_role`/`has_any_role`); `AdminV1Gateway.syncRoles` llamaba a una RPC inexistente, así que «guardar roles» de Usuarios fallaba con PGRST202. Lo que estos docs llamaban «`succeeds today on MAIN`» / `APPLY_PENDING_APPROVAL` era falso o está superado (la fila «asignar roles = REAL» de Usuarios V2 sólo es cierta desde hoy). Ahora: `admin_sync_roles(uuid,uuid[])` `SECURITY DEFINER`, `search_path=""`, `row_security=off`, volátil, ACL `{postgres, service_role}` (anon/authenticated sin EXECUTE, comprobado) + helper `admin_role_code_canonical(text)` (inmutable, ejecutable por PUBLIC a propósito). Invariantes: `ROLE_SYNC_INVALID_USER`, `ROLE_SYNC_UNKNOWN_ROLE`, `ROLE_SYNC_EMPTY_ROLES` y `ROLE_SYNC_LAST_SUPERADMIN` (sólo bloquea la transición antes>0 → después=0; MAIN hoy: 0 superadmins, 1 fila en `roles_usuario` con rol `admin`). Rollback exacto: `drop function public.admin_sync_roles(uuid,uuid[]); drop function public.admin_role_code_canonical(text);` (`002_rollback` restaura la versión sin guarda de `docs/supabase/20260624`, que NO es lo que había en MAIN). Detalle en `docs/database/MIGRATION_STATE.md`.

**Evidencia.** `backend/dbtest` (RoleSync*, PostgreSQL 16 embebido) 45/45 tras `cleanTest` (9 suites). SMOKE LIVE contra Ktor real con usuarios E2E desechables (`E2E_TEST_SMOKE_*`, borrados; MAIN vuelve a 1 fila en `roles_usuario`, 0 superadmins, 3 preguntas): asignar rol OK; array vacío a un administrativo → 422 `role_sync_empty_roles` con roles intactos; rol inexistente → 422 `role_sync_unknown_role`; usuario inexistente → 422 `role_sync_invalid_user`; dos degradaciones SIMULTÁNEAS de los dos únicos superadmins → 1×200 y 1×409 `role_sync_last_superadmin`, queda 1 superadmin; retirar el último superadmin → 409; con otro superadmin vivo degradar SÍ se permite; RPC directa denegada a anon (401) y a autenticado (403). Residual: una escritura directa sobre `roles_usuario` con la clave de `service_role` no pasa por la guarda.

**Smoke live Users/Competición/Accesos/OT: 39/39 checks PASS.** Usuarios (listado/get/ficha/404/403 sin rol admin); competición (catálogo de 5 niveles de liga, ranking global, ranking de liga, posición de usuario sin datos inventados, búsqueda, id no UUID → 400); OpositTickets (dos concesiones con la MISMA `idempotencyKey` → mismo saldo y UN movimiento en `transacciones_cartera`; clave nueva suma; cantidad 0 → 422).

**BUG P1 de Accesos encontrado y corregido.** El escritorio (`features/exam-access`) mandaba `accessType="manual"` por defecto, pero la CHECK viva `contenido_desbloqueado_usuario_motivo_check` sólo admite `premium|compra|regalo|admin|promocion`: conceder con la acción por defecto daba SIEMPRE 422 `check_violation`. Corrección: `upsertExamAccess` normaliza (`manual`/vacío → `admin`; otro valor → 422 `EXAM_ACCESS_GRANT_TYPE_UNKNOWN`, sin escritura) y el cliente usa un selector cerrado con defecto `admin` (Administración/Premium/Compra/Regalo/Promoción). Tests: `AdminV1ExamAccessGrantTest` (8) + `ExamAccessViewModelTest` (7). Deuda P2 abierta (DEBT-067 / ACC-001): `findPerfil`/`findExamen` buscan por nombre/título cargando TODAS las filas (no escala; ambiguo con homónimos).

**Hallazgos abiertos (todos P2, no bloquean el cierre P0/P1).**
- Ktor invoca 3 RPC que NO existen en MAIN: `admin_replace_search_index` y `admin_upsert_search_entry` (`TagSearchGateway.kt:144,188`, sólo definidas en `docs/supabase/20260624_admin_atomic_operations.sql`; búsqueda de etiquetas/contenido) y usa 4 tablas ausentes: `articulo_tienda`, `campana_rafaga`, `oferta_tienda`, `recurso_temporal_tienda` (Tienda/Market; MAIN tiene `productos_store`, etc.). Decisión pendiente antes del servidor: provisionar o declarar fuera de alcance (DEBT-068 / LEG-003). La RPC legada `rpc_grant_exam_access(integer,integer,...)` sigue en MAIN con ids enteros; Ktor no la usa.
- OposiBot (repo separado): su único código de red es `RealAuthApi` → `/v1/auth/*`; no existe consumidor de `/api/v1/app/examenes|intentos` y no lee `anulada`. INT-001/R9 no es un defecto de código sino un contrato sin consumidor; cuando se construya el corredor de exámenes debe leer `anulada` ANTES de `esCorrecta`. Acción mínima futura, NO hecha aquí: registrar la regla en OposiBot `docs/integration/OPOSICONTROL_CONTRACT.md`.
- `OPOSICONTROL_ALLOW_DEV_USER` DEBE estar a false/ausente en servidor (con `APP_ENV` dev/test acepta `X-Usuario-Id` como identidad en `/api/v1/app/*`); SEC-002/SEC-005 (token estático) siguen abiertos (DEBT-069 / SEC-007).
- EXAM-008 revisado contra el código: el servidor ya rechaza la ruta local (422 `QUESTION_RESOURCE_UPLOAD_REQUIRED`) y expone `POST /preguntas/{id}/recursos/archivo`, pero el cliente (`uploadResource`) sigue mandando JSON y no sube bytes: queda PARTIALLY_RESOLVED (cliente pendiente).

**Tests hoy.** `features:users` 46, `features:exam-access` 7, `backend:ktor` 812, `backend:dbtest` RoleSync 45, y `:composeApp:compileDevDebugKotlinAndroid` BUILD SUCCESSFUL (Android compila tras los cambios de `commonMain` de Importaciones/M10 y Accesos).

`DATABASE_CHANGES: una migración (20260921071916 role_sync_guard) aplicada a MAIN`. `MAIN_WRITES: sólo esa migración y el smoke con datos E2E desechables, borrados`.

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

## Noticias V2 + esquema real de noticias (2026-09-23)

`NEWS_SCHEMA_CREATED`. La vertical de Noticias estaba construida entera —58 intents,
casos de uso, repositorio, rutas Ktor y subida multipart— sobre tablas que **nunca
existieron en MAIN**. Verificado por SQL contra `ndiooyyqtaeysnedywer`: `noticia` y
`tipo_noticia` no aparecían en ningún esquema, y ninguna de las 29 migraciones del
repositorio las mencionaba. El síntoma visible era «No se pudo actualizar la
biblioteca» en el panel: no era un fallo de red, era que `GET /rest/v1/noticia` no
tenía contra qué resolver. Hasta esta fecha, **Noticias no guardaba nada en el
servidor**; lo único que funcionaba era el borrador local.

Cuatro defectos, no uno:

1. `noticia` no existía.
2. `tipo_noticia` no existía.
3. `NewsGateway` consultaba `especialidad` (singular); la tabla real es
   `especialidades`. El fallo caía dentro de un `runCatching` y salía como «No
   existe especialidad», escondiendo la causa.
4. `SupabaseNoticiaRow.especialidadId` se declaraba `Int` contra una base cuyas
   claves primarias son `uuid`. Ni con las tablas creadas habría guardado: el
   modelo de tipos era incompatible con la base.

`DATABASE_CHANGES`: `20260923103000_news_canvas_schema.sql`, **APLICADA a MAIN**.
Aditiva, sin tocar tablas vivas. Crea `tipo_noticia` (uuid, `slug`/`nombre` únicos,
sembrada con los cuatro tipos que ya valida `ValidateNewsDraftUseCase`) y `noticia`
(FKs uuid reales, checks de estado/fondo/título, índices de biblioteca). La
composición pasa a **`capas jsonb`** propia con `check (jsonb_typeof(capas) =
'array')`: antes se serializaba DENTRO de `texto` con un prefijo, lo que hacía que
una columna significara dos cosas y que la composición no se pudiera consultar. RLS
con el patrón de `especialidades`; la política pública exige `estado='published' AND
(publicar_desde IS NULL OR publicar_desde <= now())` — sin eso, programar sería lo
mismo que publicar.

`20260923120000_news_published_at.sql` queda **ESCRITA Y SIN APLICAR**. Sella
`published_at` al publicar, que hoy no lo escribe nadie: la columna existe, el DTO la
declara y ninguna ruta la rellena, así que una noticia publicada queda con el campo
nulo para siempre. Va en trigger y no en Ktor porque sellar «solo si está vacío»
exige leer-y-escribir atómico, que un `upsert` de PostgREST no da.

`LIVE_VERIFIED` contra MAIN, con rollback deliberado para no dejar filas: inserción,
persistencia del frame en `jsonb` (x=48, width=360 sobreviven el ida y vuelta),
rechazo de capas no-array, rechazo de programada sin fecha, rechazo de FK inválida y
disparo del trigger de `actualizado_en`. Ciclo completo crear → guardar → recargar →
publicar → borrar, tabla devuelta a 0 filas.

`DEPLOYED`: `0eeae46` en `oposibots-prod-01`. `SENTRY_RELEASE` alineado.

### Editor de Noticias — rediseño y capacidades

Layout de cuatro columnas (Biblioteca · Editor · Inspector · Publicación) con plegado
escalonado: primero cae Publicación —es contexto, no formulario— y luego el Inspector,
cuyo contenido pasa dentro del editor en vez de estrujarse. Anchos fijos, sin `weight`.

El lienzo deja de vivir dentro de una pestaña: es permanente en el editor y las
pestañas solo cambian el formulario que lo acompaña. Antes solo aparecía en «Capas
visuales», así que escribir el título o elegir especialidad se hacía a ciegas.

**Drag y resize reales.** `UpdateLayerFrame(id,x,y,width,height)` existía desde el
principio y ya se persistía, pero ningún gesto lo disparaba. Tres reglas: el
acumulador arranca del valor real de la capa en cada `onDragStart` —si no, el
redondeo a entero suma deriva arrastre tras arrastre—; todo se recorta al lienzo; y
una capa bloqueada no se mueve **y no pinta tiradores**, porque un tirador que no
responde es una affordance falsa.

Añadido además: zoom 50–200 % (estado de VISTA, no del documento: no ensucia
`isDirty`), alineación en los seis ejes mediante `UpdateLayerFrame`, flechas que
mueven 1 px y 10 px con Mayús, `Supr` para eliminar capa, y tamaño de lienzo editable
—`UpdateCanvasWidth`/`Height` existían con recorte de capas incluido y ninguna
pantalla los ofrecía—.

`fontSizeSp` y `fontWeight` por capa, de punta a punta (modelo, DTO cliente, DTO
backend, borrador local y ViewModel). **`0` y `""` son centinelas de «derivado del
tipo de capa»**, no valores: así lo ya guardado se sigue pintando igual y se puede
volver atrás. `"normal"` como default habría hecho que toda capa de título guardada
perdiera la negrita al cargarse.

`FUNCTIONAL_DRIFT: INTENTIONAL`. Drag/resize y tipografía son funcionalidad NUEVA,
autorizada explícitamente por el usuario, no refactor a comportamiento constante.

### Delta encontrado en Exámenes/Preguntas

`AdminGateway.NewsTypeRow.id` se declaraba `Int` contra la columna `uuid` de
`tipo_noticia`: la deserialización de PostgREST reventaba y el catálogo de tipos
llegaba al panel como «No disponible», impidiendo clasificar cualquier noticia.
Además `toRow()` hacía `id.toIntOrNull()`, que sobre un uuid da `null` siempre, así
que **editar un tipo de noticia creaba uno nuevo**. Ambos corregidos.
`PENDING_DEPLOY`: requiere redesplegar Ktor.

`PORTADA_DE_EXAMEN_RETIRADA` (decisión de producto): la entrada para subir una
portada desaparece de Exámenes. La portada se compone con los componentes de la app,
y la API de la app no enviaba la imagen subida, así que el archivo no llegaba a
verse. `portada_url` y los intents siguen existiendo: se retira la ENTRADA, no lo
guardado.

`NOT_VERIFIED`: render de Noticias con sesión administrativa real no capturado en
esta entrega; light/dark y responsive de Noticias V2 sin revisión sistemática;
deshacer/rehacer y guías NO existen y no se han simulado con botones muertos.

## Compresión de medios y recursos de pregunta (2026-09-23, PRE-DESPLIEGUE)

`NOT_DEPLOYED`. Todo lo de esta sección está en `feature/news-v2-visual-redesign`
y verificado en local; **nada de esto corre todavía en Hetzner**, que sigue en
`0eeae46`.

### Compresión automática de subidas

Se comprimen imágenes, vídeo y audio antes de subir a Supabase Storage. Imágenes
en proceso con `javax.imageio` (lado máximo 2048 px, JPEG q0.82 progresivo,
orientación EXIF aplicada a los píxeles, techo de 50 MP leído de cabecera para
evitar bombas de descompresión). Vídeo y audio con `ffmpeg` externo (H.264
CRF 23 preset veryfast, escala ≤1080p, AAC 128k, `+faststart`).

Reglas innegociables, todas con test: si la compresión falla, tarda más del
timeout o no hay ffmpeg → **se sube el original**; si el resultado pesa igual o
más → se sube el original; el MIME devuelto siempre corresponde a los bytes
devueltos; PDF pasa intacto. MP3/M4A pasan intactos a propósito: recomprimir
lossy sobre lossy es pérdida generacional a cambio de casi ningún byte. WebP
pasa intacto porque la JVM de serie ni lo lee ni lo escribe.

**MITIGACIÓN PNG/JPEG (line-art).** Un PNG de menos de 300 KB que no hizo falta
redimensionar **se queda en PNG**. Motivo: el JPEG con pérdida introduce ringing
alrededor de los bordes duros, y el material de oposición está lleno de ellos
—esquemas, tablas, capturas de temario, texto fino—. En ese contenido el JPEG
sale a la vez MÁS PEQUEÑO y PEOR, así que la regla de «no engordar» NO protege:
el resultado degradado pasaría el filtro de tamaño. El umbral no mide «es
line-art» —eso no se sabe barato— sino que no hay nada que ganar. Un PNG grande
que sí necesita redimensionarse SIGUE convirtiéndose a JPEG, que es donde está
el ahorro real. Dos tests fijan ambos lados de la regla: uno con rejilla y
diagonales que debe seguir siendo PNG, y otro con foto grande que debe pasar a
JPEG.

`ALCANCE`: sólo subidas NUEVAS. Los **15 objetos ya en Storage (≈9,7 MB) NO se
tocan** y **NO se hace backfill**. Un backfill exigiría releer, recomprimir y
reescribir `ruta_storage` y metadatos, y es trabajo aparte con riesgo propio.

`ffmpeg` sin medir: no hay Docker en la máquina de desarrollo. El tamaño real
debe medirse en el despliegue con `docker image inspect`, NO con la estimación
de 70-90 MB.

### Puntos de subida investigados uno por uno

| Punto | Estado real |
|---|---|
| Portada de solucionario (`Application.kt` `uploadPortada`) | **RETIRADA**: lanza `LEGACY_EXAM_COVER_RETIRED`, HTTP 410. No se toca |
| `/content/preguntas/{id}/recursos/{id}/upload` | **LEGACY ROTO**: hace `preguntaId.toIntId()` y `preguntas.id` es `uuid`, así que `toIntOrNull()` devuelve null y lanza SIEMPRE. Inalcanzable, no se arregla |
| Portada de examen (`uploadExamenPortada`) | **VIVA**: compresión conectada, validando el MIME declarado ANTES y comprobando que el MIME resultante siga admitido. Comprimir no puede ampliar lo que se acepta |

### Patrón técnico detectado — IDs enteros contra claves uuid

`LEGACY_INT_ID_DRIFT`. Existen restos de backend escritos bajo la hipótesis de
que las claves primarias eran enteras, cuando en MAIN son `uuid`. Detectado ya
en **tres** sitios independientes:

1. `noticia` / `especialidad_id` (`SupabaseNoticiaRow`) — corregido.
2. `tipo_noticia` (`AdminGateway.NewsTypeRow`) — corregido, rompía el catálogo.
3. Recursos legacy (`uploadRecurso` → `toIntId()`) — dejado como está: la ruta
   es inalcanzable y arreglarla sería resucitar un endpoint sin consumidor.

**DECISIÓN: no se hace migración ni refactor global de ids legacy ahora.** El
patrón queda registrado para que la próxima aparición se reconozca a la primera
en vez de diagnosticarse desde cero. Véase DEBT-076.

### Recursos de preguntas inmersivas — P0-4 CERRADO

El cliente ya crea, sube y archiva recursos. Cinco métodos en
`QuestionsRepository`, multipart siguiendo el patrón de `uploadExamCover`, y un
formulario que respeta las reglas reales de la base: `texto` sólo con cuerpo,
`enlace` sólo con URL `https?://`, el resto sólo con archivo. La regla «sólo
inmersiva» NO se duplica en cliente: la impone el servidor con 422 y se muestra
su mensaje.

El diálogo de borrado dice **«Archivar»**, no «Eliminar», porque la ruta pone
`activo=false` y hoy no existe desarchivar.

Cerrados además tres huecos que dejó el cableado: el campo `description` existía
en el formulario y en el backend pero no en el modelo de dominio, así que se
escribía y no se veía; la matriz de honestidad declaraba `«Subir recursos» →
BLOCKED` cuando ya funciona —una matriz que declara bloqueado lo que funciona
miente igual que un botón que aparenta funcionar sin hacerlo—; y se retiró la
constante muerta que anunciaba «todavía no está disponible».

### Disco de Supabase en Estado del sistema

Fila «Disco Supabase» entre RAM y Latencia API. Lo lee **Ktor**, no Prometheus,
para no meter la service key como credencial de scrape en un fichero del
servidor. Fuente: endpoint Prometheus privilegiado de Supabase
(`/customer/v1/privileged/metrics`, HTTP Basic con usuario `service_role`),
verificado en vivo: 200 con ~940 series.

Se usan `node_filesystem_size_bytes` y `node_filesystem_avail_bytes` filtradas
por `mountpoint="/data"`. **El `mountpoint="/"` es el disco del SISTEMA
OPERATIVO y está al ~75 %**: usarlo daría un panel alarmado sin motivo. Con los
bytes reales da 3,6 %.

`NO EXISTE métrica de Storage` en ese endpoint (cero coincidencias de «storage»
en las 940 series): Storage va sobre S3. La etiqueta habla sólo del volumen de
Postgres. Storage sí es medible por SQL sobre `storage.objects`, pero eso exige
configurar la cuota del plan a mano y NO se ha hecho.

Semántica conservada: cualquier fallo —endpoint caído, 401, cuerpo malformado,
falta una de las dos series, valor no finito, `avail > size`, dos muestras
contradictorias— produce `null` y «No disponible». Un disco vacío medido es
`0 %`. La clave nunca viaja en la URL ni se guarda como propiedad.

### Portada de examen retirada de la UI

Decisión de producto: la portada se compone con los componentes de la app. La
entrada de subida desaparece de los DOS sitios donde estaba
(`ExamBasicInfoSection` y `ExamsScreen`), y sus escenarios del arnés visual se
retiran con ella. `portada_url`, los intents y el endpoint SIGUEN existiendo: se
retira la ENTRADA, no lo guardado.

`GATES LOCALES`: backend 869/869, data 106/106, news 34/34, questions 86/86,
settings 10/10, exams 313/313 — **1.418 tests, 0 fallos**.
`composeApp:compileKotlinJvm` PASS. `git diff --check` limpio.

### Optimización Kotlin/KMP profesional — INTEGRADA en main (2026-09-24)

Estado: **integrada en `main`**. Sin deploy: este refactor no implica un
despliegue de Ktor a producción.

Trazabilidad:

| | SHA |
|---|---|
| Baseline original (News V2 certificada) | `7187040ca1b323329a9525627c6339f40de9308a` |
| Commit del optimizador (rama `refactor/kotlin-kmp-professional-optimization`) | `33e2181fac285df14bf36c67addec888ff77b2ed` |
| Merge `--no-ff` a `main` | `68e783be14319edbb939bda319df34903fc86e0f` |

`origin/main` seguía en el baseline en el momento del merge: sin divergencia ni
conflictos.

Delta certificado (recalculado desde Git, no copiado de un informe previo):
**86 ficheros `.kt` afectados = 70 modificados + 2 añadidos + 14 eliminados;
+442 / −5.199; neto −4.757 LOC.** (El commit completo son 99 ficheros, +2.288 /
−5.199, porque incluye 13 ficheros de documentación.)

Los 14 eliminados: `SupabaseContentRepositoryImpl`, `KtorContentRepositoryImpl`,
`SupabaseCommerceRepositoryImpl`, `SupabaseNewsRepositoryImpl`, `ContentDtos`
(data); `ExamQuestionsSection`, `ExamResourcesSection` (features/exams);
`AdminShell`, `DesktopModerationRoot` y los 4 ficheros `AppExperience`
expect/actual (composeApp); `AppUiState` (core/common). Todos sin consumidores
directos ni indirectos (Koin, expect/actual por plataforma, recursos, Gradle).

Cobertura de revisión: 731/731 `.kt` tras los borrados (739 al empezar),
reconciliada contra el recuento real de disco. Un fallo de agrupación propio
dejó `backend/dbtest/` (14 ficheros) sin asignar en la primera pasada; se
detectó y se revisó aparte. Además, certificación independiente del delta con
dos auditores que no escribieron el código: **0 regresiones** (auth headers,
formateador de tiempo, componentes Compose, helpers de test y contratos
serializados verificados como equivalentes al baseline; News V2 sin regresión).
Dos correcciones triviales de higiene se aplicaron en esa fase (una línea en
blanco final en `AuditMapper.kt`, dos imports huérfanos de `HttpHeaders`).

Gates: compilación JVM completa y tests de los 34 módulos, forzados sin caché,
sobre el commit del optimizador y de nuevo sobre `main` tras el merge (ver
resultado post-merge en el informe de cierre). `backend:dbtest` contra
PostgreSQL embebido real: 45/45. Android excluido por la misma limitación de
`ANDROID_HOME` que en el baseline.

Detalle completo, con fichero:línea de lo aplicado y de lo registrado, en
`docs/optimization/` (`LEDGER.md`, `APPLIED.md`, `reports/01` a `08`).

**Deuda detectada y NO corregida (sigue abierta; no se ha resuelto):**

1. **Fuga de corrutinas en `MviViewModel`**: `clear()` no lo llama nadie, así
   que los ViewModels inyectados con `factory {}` mantienen su `collectLatest`
   vivo indefinidamente. Defecto de ciclo de vida real, no de optimización.
2. **Bug de fecha en `AuditViewModel`**: formatea sin `removeSuffix("Z")`; si el
   instante cae en un segundo exacto, `takeLast(16)` se desplaza (~1 de cada
   1.000 refrescos). Requiere su propio test.
3. **DEBT-081 (`AdminTableColumn`)**: usa `weight: Float`, contra la regla de
   medidas exactas de AGENTS.md; lo heredan al menos 5 pantallas. Requiere
   rediseñar `AdminTable` con ancho fijo por columna.

Los hallazgos Tier 2/3 restantes de los 8 informes tampoco se aplicaron
(cambian comportamiento observable, piden un test que no existe o son decisión
de producto/arquitectura); quedan documentados con precisión de línea.

## Recursos de pregunta: sólo imagen, audio y vídeo; inmersiva exige audio (2026-09-25)

Rama `feature/question-resources-audio-video` (sin merge ni push).

- **Tipos**: los únicos recursos son `imagen`, `audio` y `video`, siempre con archivo. Se eliminan `pdf`, `texto` y `enlace` del cliente (`QuestionResourceKind`, formulario, selector de archivos, repositorio) y del backend (`QUESTION_RESOURCE_MIME` sin `application/pdf`; `POST /preguntas/{id}/recursos` por JSON rechaza todo con 422). Los recursos históricos de otros tipos siguen leyéndose.
- **Sin tope de producto**: desaparece el «Máximo 50 MB» del popup y el `require` de cliente. El servidor conserva un techo TÉCNICO de 300 MB (el archivo se carga en memoria para comprimirlo; no puede ser ilimitado) y comprime antes de subir a Storage. NOT_VERIFIED: el límite por objeto del bucket `recursos-pregunta` en Supabase aplica al archivo ya comprimido; un archivo que siga por encima fallará en `storagePut` con error genérico.
- **Inmersiva utilizable = al menos un audio activo**: `vincularPreguntaExamen` (422 `QUESTION_IMMERSIVE_REQUIRES_AUDIO`) y `publicarExamen` (422 `EXAM_PUBLISH_IMMERSIVE_WITHOUT_AUDIO`) lo imponen; la pregunta se guarda igualmente. El editor avisa con `lacksRequiredAudio()`. Ráfaga y razonada no piden nada.
- **Riesgo**: los exámenes con inmersivas ya vinculadas SIN audio no podrán publicarse hasta añadirles uno (no se ha auditado el dato real en MAIN). La RPC `admin_link_question_to_exam` no se ha tocado: la regla vive en Ktor, por lo que otra vía que llame directamente a la RPC la esquivaría.
- **Pruebas**: `:features:questions:jvmTest`, `:data:jvmTest`, `:domain:jvmTest`, `:features:exams:jvmTest`, `:backend:ktor:test` (incluye `ImmersiveAudioRuleTest`) en verde; `:composeApp:compileKotlinJvm` correcto. NOT_VERIFIED: recorrido en ventana real del popup.
### OposiMarket: escaparate de exámenes publicados (2026-09-25)

Rama `feature/oposimarket-published-exams`, desde `main` (`4530b73`). Nueva
sección **«Exámenes publicados»** en OposiMarket, primera pestaña y la que abre
por defecto: rejilla con todos los exámenes PUBLICADOS (ráfaga, razonado e
inmersivo) y su portada, con búsqueda (título, especialidad, código) y filtro
por tipo con recuento. Solo consulta; los datos se editan en Exámenes.

- `CURRENT_IMPLEMENTATION`: el estado se filtra en cliente
  (`publishedExams`: `PUBLISHED` y no borrado) sobre `refreshAllExams()`, que
  devuelve el catálogo completo. Un fallo de carga es error con reintento, no un
  escaparate vacío; sin publicados es `Empty` con su mensaje.
- La portada se **compone** en la app (`ExamCoverArt`, 264×148, 16:9): fondo oscuro
  `#131313` del diseño de Figma (nodo `59527:25808`, «icono/card») con la
  ilustración vectorial dorada de la comunidad para los inmersivos, el viento
  para las ráfagas y la brújula para los razonados. 21 vectores en
  `features/store/src/commonMain/composeResources/drawable/cover_*.xml`
  (convertidos de SVG a vector drawable para que carguen igual en escritorio,
  Android e iOS; `cover_rafagas_xs` queda sin usar, es el tamaño compacto).
  La ilustración sale de `coverArtworkFor` (pura y testeada: seed canónico de 19
  comunidades; Cataluña usa Barcelona y Ceuta/Melilla comparten). Un inmersivo
  sin comunidad, o con una que el diseño no cubre, usa el icono genérico:
  no se inventa la de otra comunidad.
- `PRODUCT_DECISION_PENDING`: pantalla por revisar por el producto tras ver las
  portadas; falta el diseño de los inmersivos sin comunidad (examen nacional).
- Tests: 13 nuevos en `features/store` (lógica pura + `MarketViewModel`) y un
  banco visual (`PublishedExamsVisualHarness`, 7 capturas claro/oscuro/estados).
## Exam cover V1.1 — portada automática (2026-09-21, sin commit)

- **Contrato**: la portada de un examen NO se sube, NO se elige y NO se guarda. Se deduce siempre de `tipoExamen` + comunidad autónoma: Ráfaga → `cover_rafaga`, Razonado → `cover_razonado` (comunes a todas las comunidades), Inmersivo → vector de su comunidad (`vector_madrid`, `vector_andalucia`…). No hay columna nueva ni migración (se descartó `portada_key`).
- **Resolver único**: `ExamCoverResolver` (`domain/.../model/ExamCoverResolver.kt`), con una copia gemela en OposiBot (`core/model/ExamCoverResolver.kt`). La comunidad se identifica por su código ISO 3166-2:ES (`comunidades_autonomas.codigo`, p. ej. `ES-MD`; 19 filas en MAIN). Los nombres `vector_*` son PROVISIONALES hasta recibir los vectores definitivos: el cambio es sólo la tabla `COMMUNITY_VECTORS`.
- **OposiControl**: «Información básica → Portada» (y las pantallas clásica y móvil) es una previsualización automática (`ExamCoverPreview`, visual provisional dibujado en Compose). Eliminados el selector de archivos, «Subir ahora», «Quitar», el diálogo de borrado y el estado de subida del ViewModel.
- **Ktor**: `AppExamenDto` gana `comunidadCodigo` (`GET /api/v1/app/examenes` y `/examenes/{id}`; `null` = nacional). Una sola lectura de `comunidades_autonomas` y sólo si algún examen tiene comunidad; si falla, el catálogo sale sin código.
- **LEGACY conservado, sin uso desde la UI**: rutas `POST/DELETE /api/v1/admin/examenes/{id}/portada`, casos de uso `UploadExamCover`/`ClearExamCover` y método de repositorio, columnas `portada_storage_path`/`portada_mime_type` (M6), bucket `exam-resources/exam-covers/`. Retirada = decisión pendiente (DEBT-082).
- **Sin decidir**: portada de un Inmersivo sin comunidad (nacional) o con una comunidad sin vector → hoy `cover_generica` provisional.
- **Verificado**: Ktor 4 tests nuevos (`AppV1ExamCoverContractTest`) + regresión de portada/ciclo de vida; dominio 8 tests del resolver; features:exams 308 tests; OposiBot: el test del resolver compila en iOS (link/ejecución `SKIPPED` en Windows) y se ejecutó 7/7 en JVM con una copia temporal.
- **Hechos comprobados en MAIN (lectura, 2026-09-21)** sobre Monetización de examen: `examenes` tiene además `comprable_con_tickets`, `dias_acceso_compra`, `demo_disponible`, `requiere_plan_codigo`, `creditos_inmersivos_necesarios`, que ni la UI ni el DTO exponen; `precio_tickets` es nullable con CHECK `>= 0`; el CHECK de acceso admite sólo `gratuito|premium` y ninguno relaciona acceso con precio. `rpc_purchase_articulo`, `rpc_grant_exam_access` y `can_access_exam` existen con firma `integer` sobre tablas que no existen (`articulo_tienda`, `examen`, `acceso_examen_usuario`): huérfanas. `productos_store`, `paquetes_opositickets`, `compras_usuario` y `contenido_desbloqueado_usuario` existen con 0 filas; `articulo_tienda`, `oferta_tienda`, `recurso_temporal_tienda` y `campana_rafaga` NO existen (DEBT-068).

## Distribución macOS de escritorio (2026-09-25)

Rama `feature/macos-desktop-distribution`. Workflow `.github/workflows/macos-desktop-release.yml` (runners `macos-15` arm64 y `macos-15-intel` x64): genera `OposiControl.app` + DMG con runtime Java 21 embebido, apuntando a `https://api.oposibots.es`. Dos DMG separados (no universal). Modo `unsigned` → `…-UNSIGNED_INTERNAL_BUILD.dmg`; modo `signed` → Developer ID + Hardened Runtime + `notarytool` + `stapler`, con secretos de GitHub (ver `docs/release/MACOS_DISTRIBUTION.md`). Gradle: `macOS { packageName = "OposiControl" … }`, `modules(...)` explícitos (también afectan al runtime de Windows: añade `java.sql`, `jdk.crypto.ec`…), tarea `verifyProductionDesktopConfig`. Windows sigue como `OposiControlAdmin` (verificado `createDistributable` + arranque en Windows). VERIFICADO 2026-09-25 (run 36137146759): DMG unsigned arm64 y x64 generados; la app arranca sin Java externo (libjvm del runtime embebido) hasta la pantalla de login; cfg con BASE_URL=https://api.oposibots.es y APP_ENV=prod. arm64 lleva firma ad-hoc de jpackage; x64 sin firma. PENDIENTE: credenciales Apple (build firmada/notarizada) y merge a main.

## macOS: arranque roto en Mac real por firma ad-hoc + Hardened Runtime (2026-09-26)

Evidencia de Arturo (arm64): `libjli.dylib: code signature not valid for use in process … different Team IDs`. Inspección en CI (run 36230133151): jpackage deja TODOS los Mach-O firmados ad-hoc (`TeamIdentifier=not set`) y con Hardened Runtime (`flags=0x10002(adhoc,runtime)`), salvo `libskiko` (`0x2`). Hardened Runtime activa la validación de librerías y un proceso ad-hoc no la supera en un Mac con SIP; el runner de GitHub tiene SIP desactivado, por eso no lo reproducía. Corrección (solo build interna): `composeApp/packaging/macos/adhoc-sign.sh` vuelve a firmar ad-hoc de dentro hacia fuera SIN Hardened Runtime; el workflow falla si queda algún binario con `runtime`, si lanzador y `libjli` difieren en Team ID o si la app copiada del DMG no sigue viva 40 s. `disable-library-validation` NO se ha añadido. NOT_VERIFIED: arranque en un Mac con SIP activo (pendiente de Arturo).
