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
