# Build and Runtime

## Official commands

- Android: `./gradlew.bat :composeApp:assembleDebug`.
- Desktop: `./gradlew.bat :composeApp:jvmRun`.
- Tests: `./gradlew.bat test`.
- iOS: Xcode project under `iosApp`.

## Runtime findings

- Backend main: Netty, default `HOST=0.0.0.0`, `PORT=8080`.
- Combined profile requires Supabase URL/service role and all gateways.
- Consumer profile requires OposiBots auth config.
- Real Supabase/UI runtime was not started for this audit.
- Global compile/test is blocked by AuditViewModel compile error; backend module and exams unit tests pass separately.

Status: build `PARTIAL`; runtime `NOT_VERIFIED`.
