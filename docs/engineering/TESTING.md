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
