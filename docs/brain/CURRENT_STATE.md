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
