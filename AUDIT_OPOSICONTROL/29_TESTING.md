# Testing Inventory

## Tests found

- Backend: auth gateway/routes, user admin gateway/routes, backend security.
- Features: auth ViewModel, news ViewModel, exams ViewModel/parser, support ViewModel.
- Domain: login use case.
- No PDF fixture file found in tracked inventory; parser tests are text strings.
- No E2E/browser/UI screenshot suite identified.

## Executed

| Command | Result |
|---|---|
| `./gradlew.bat :backend:ktor:test --no-daemon --console=plain` | PASS; build success, tasks up-to-date |
| `./gradlew.bat :features:exams:testDebugUnitTest --no-daemon --console=plain` | PASS |
| `./gradlew.bat test --no-daemon --console=plain` | FAIL at `:features:audit:compileReleaseKotlinAndroid`; `AuditViewModel.kt:35:49 Unresolved reference 'System'` |

No test failure was fixed. Full suite status: `FAIL`. Coverage: `NOT_VERIFIED`.
