# Module Map

## Active Gradle modules

Source: `settings.gradle.kts`, filesystem and feature source paths. These are module identities, not claims that every capability is end-to-end verified.

### Application and shared layers

| Module | Responsibility | Evidence/status |
|---|---|---|
| `:composeApp` | Multiplatform application shell, navigation and platform entry points | `SOURCE_VERIFIED` |
| `:core:common` | Shared app result/state/common utilities | `SOURCE_VERIFIED` |
| `:core:network` | Shared network/client adapters | `SOURCE_VERIFIED` |
| `:core:database` | Shared database/SQLDelight integration | `SOURCE_VERIFIED` |
| `:core:ui` | Shared Compose UI/theme/components | `SOURCE_VERIFIED` |
| `:core:testing` | Shared testing utilities | `SOURCE_VERIFIED` |
| `:domain` | Domain models, repositories and use cases | `SOURCE_VERIFIED` |
| `:data` | Repository/data-source implementations and remote DTOs | `SOURCE_VERIFIED` |
| `:backend:ktor` | Ktor server routes, services/gateways and server boundary | `SOURCE_VERIFIED` |

`core/api` has no source files and is not included by the active `settings.gradle.kts`; only generated/ignored build output was observed. Classification is `FILESYSTEM_ONLY / GENERATED_ARTIFACT / NOT_ACTIVE_GRADLE_MODULE`, not an active module and not a candidate for reactivation without new source evidence.

### 17 feature modules

| Module | Screen/domain evidence |
|---|---|
| `:features:auth` | Auth |
| `:features:dashboard` | Dashboard |
| `:features:users` | Users |
| `:features:settings` | Settings (source-only additional section) |
| `:features:audit` | Audit |
| `:features:specialties` | Specialties |
| `:features:communities` | Communities |
| `:features:tags` | Tags |
| `:features:news-types` | News Types |
| `:features:news` | News |
| `:features:exams` | Exams and Questions/Resources |
| `:features:exam-access` | Exam Access |
| `:features:store` | Store |
| `:features:tickets` | Tickets |
| `:features:support` | Support |
| `:features:moderation` | Moderation |

## Architecture notes

- KMP client layers call Ktor for the documented admin/content/app boundaries, while the Store repository currently has a direct Supabase implementation in source; this is a technical risk, not a resolved architecture claim.
- `AdminSection` has 17 product sections plus source-level `SETTINGS` in the current navigation enum; the required Screen Registry deliberately tracks the 17 requested sections and documents Settings as an additional source section.
- Runtime UI reachability and live DB alignment remain `NOT_VERIFIED`.
