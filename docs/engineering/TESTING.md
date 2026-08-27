# Testing

Validation recorded for `2026-08-19`, pase `OPC-20260819-004`:

| Command | Result | Note |
|---|---|---|
| `:backend:ktor:test --no-daemon --console=plain` | PASS | backend tests |
| `:features:exams:testDebugUnitTest --no-daemon --console=plain` | PASS | exams unit tests |
| `:features:audit:compileDebugKotlinAndroid --console=plain` | PASS | Clock API aligned with current kotlinx-datetime |
| `:features:news:testDebugUnitTest --console=plain` | PASS | local draft contract and save-draft behavior |
| `test --console=plain` | PASS | global Kotlin/Android test graph |
| `:composeApp:compileKotlinJvm --console=plain` | PASS | JVM/Desktop compilation |
| `:composeApp:compileKotlinIosSimulatorArm64 --console=plain` | PASS | Kotlin/Native compilation only; no iOS runtime |
| `git diff --check` | PASS | final tracked diff check |

Warnings deprecados de `kotlinx.datetime` y de APIs expect/actual permanecen, sin bloquear la suite. La verificación live de backend/Supabase y los runtimes instalados siguen fuera de esta matriz.

Validación recorded for `2026-08-27`, OposiTester Screen 06 (recuperación) + Screen 07 (detalle), rama `recovery/mobile-opositester-06-07` (`63c6357`, `b674fc5`):

| Command | Result | Note |
|---|---|---|
| `:backend:ktor:test --no-daemon --console=plain` | PASS | incluye `AdminV1FeedbackDetailTest` (5 casos, función pura de mapeo de detalle) |
| `:features:opositester:jvmTest --no-daemon --console=plain` | PASS | 37/37 — lista, detalle y mapping |
| `:composeApp:jvmTest --no-daemon --console=plain` | PASS | 9/9 — `AppNavigatorTest` |
| `:composeApp:assembleDevDebug --no-daemon --console=plain` | PASS | APK dev-debug generado (~47.5 MB) |
| `git diff --check` | PASS | sin conflictos ni espacios en blanco |

Verificación visual en emulador/dispositivo físico sigue `NOT_VERIFIED` — sin runtime disponible en esta sesión; código, tests y build la respaldan (`PASS_WITH_RUNTIME_DATA_PENDING`).

Validación recorded for `2026-08-27`, integración final mobile (Screens 01–10 + observability foundation) en `integration/mobile-finish`:

| Command | Result | Note |
|---|---|---|
| `:backend:ktor:test --no-daemon --console=plain` | PASS | incluye `ObservabilityTest` (21 casos: request-id, redaction, structured events, health/live, health/ready, timeouts) |
| `:domain:jvmTest --no-daemon --console=plain` | PASS | |
| `:data:jvmTest --no-daemon --console=plain` | NO-SOURCE | módulo sin tests JVM configurados (preexistente) |
| `:core:ui:jvmTest --no-daemon --console=plain` | PASS | |
| `:features:reports:jvmTest --no-daemon --console=plain` | PASS | |
| `:features:forum:jvmTest --no-daemon --console=plain` | PASS | |
| `:features:opositester:jvmTest --no-daemon --console=plain` | PASS | |
| `:features:settings:jvmTest --no-daemon --console=plain` | PASS | incluye `SystemStatusViewModelTest` (Screen 10) |
| `:features:exams:jvmTest --no-daemon --console=plain` | PASS | incluye `ContentUploadViewModel`-relacionados (Screen 09) |
| `:composeApp:jvmTest --no-daemon --console=plain` | PASS | tras corregir un choque de nombre de test (`systemStatusIsIndependentFromDiagnostics` duplicado, dejado por dos merges independientes tocando `AppNavigatorTest.kt` sin marcador de conflicto) |
| `:composeApp:assembleDevDebug --no-daemon --console=plain` | PASS | APK dev-debug |
| `:composeApp:compileKotlinJvm --no-daemon --console=plain` | PASS | target JVM/Desktop compila; empaquetado nativo (`createDistributable`) no se ejecutó en esta sesión |
| `git diff --check` | PASS | |

Runtime físico/emulador sigue `NOT_VERIFIED` — `adb devices` no reportó ningún dispositivo conectado en esta sesión (comprobado explícitamente). Sentry no está conectado (`SENTRY_CONFIGURATION_PENDING`, sin `SENTRY_DSN` disponible). Prometheus/Grafana: sin infraestructura, solo documentado.
