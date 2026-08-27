# Screen Registry

Registro activo de las 17 secciones administrativas requeridas. La navegación está basada en `AppDestination.Admin(AdminSection)`; no son URLs persistentes. `Reachable` describe reachability desde el código, no una verificación visual runtime. Desktop/mobile permanecen `PARTIAL` o `UNKNOWN` cuando la fuente no permite afirmar más.

| Screen ID / Name | Feature/domain | Route/navigation key | Module | Primary files | Reachable | Current implementation | Desktop | Mobile | Backend/data | Known endpoint families | Known issues | Evidence | Target notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `AUTH` / Auth | admin auth | `AppDestination.Auth` | `features:auth` | `AuthScreen.kt` | CONDITIONAL | BYPASSED | PARTIAL | PARTIAL | `AuthApi` / auth provider expected | `/v1/auth/*` / Supabase Auth | `TEMPORARILY_DISABLE_LOGIN = true` | source + `06_UI_SCREEN_INVENTORY.md` | real admin authentication |
| `DASHBOARD` / Dashboard | operations | `AdminSection.DASHBOARD` | `features:dashboard` | `DashboardScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | `ContentDashboardRepositoryImpl` | `/api/v1/admin/dashboard`, legacy dashboard | analytics/runtime not fully verified | source + audit | operational console |
| `USERS` / Users | admin users | `AdminSection.USERS` | `features:users` | `UsersScreen.kt` | YES | FUNCTIONAL | PARTIAL | PARTIAL | `UsersRepositoryImpl` | `/api/v1/admin/usuarios`, `/admin/users` | legacy schema dependence | source + audit | explicit role gate pending |
| `ROLES` / Roles | authorization | `AdminSection.ROLES` | `features:roles` | `RolesScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | `RolesRepositoryImpl` | `/api/v1/admin/roles`, `/admin/roles` | backend read-only/role model pending | source + audit | admin role decision pending |
| `SPECIALTIES` / Specialties | content catalog | `AdminSection.SPECIALTIES` | `features:specialties` | `SpecialtiesScreen.kt` | YES | FUNCTIONAL | PARTIAL | UNKNOWN | content repositories | `/content/especialidades`, `/api/v1/admin/especialidades` | live DB not verified | source + audit | no new UI change |
| `COMMUNITIES` / Communities | content catalog | `AdminSection.COMMUNITIES` | `features:communities` | `CommunitiesScreen.kt` | YES | FUNCTIONAL | PARTIAL | UNKNOWN | `CommunitiesRepositoryImpl` | `/content/comunidades`, `/api/v1/admin/comunidades-autonomas` | legacy/V1 drift | source + audit | no new UI change |
| `TAGS` / Tags | content taxonomy | `AdminSection.TAGS` | `features:tags` | `TagsScreen.kt` | YES | FUNCTIONAL | PARTIAL | UNKNOWN | `KtorTagsRepositoryImpl` | `/content/etiquetas`, `/api/v1/admin/etiquetas`, `/content/search` | legacy migration pending | source + audit | no new UI change |
| `NEWS_TYPES` / News Types | news taxonomy | `AdminSection.NEWS_TYPES` | `features:news-types` | `NewsTypesScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | `AdminOperationsRepository` | `/admin/news-types` | legacy endpoint/schema | source + audit | SPECIALTY segmentation applies to news |
| `NEWS` / News | editorial content | `AdminSection.NEWS` | `features:news` | `NewsScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | `KtorNewsRepositoryImpl` | `/content/noticias`, `/content/noticias/assets` | persistence drift; mobile desktop split | source + audit | professional admin workflow |
| `EXAMS` / Exams | exam content | `AdminSection.EXAMS` | `features:exams` | `ExamsScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | content/admin V1 repositories | `/content/solucionarios`, `/content/rafaga`, `/api/v1/admin/examenes` | current/target exam types differ | source + audit | inmersivo/razonado/rafaga target |
| `QUESTIONS_RESOURCES` / Questions & Resources | content/import | `AdminSection.QUESTIONS_RESOURCES`, `AppRoute.Content.Imports` | `features:exams` | `ExamsScreen.kt`, `QuestionsResourcesScreen`, `ContentUploadScreen.kt` | YES | PASS_WITH_GAPS | PARTIAL | PARTIAL | admin V1/content repositories | `/content/preguntas/*`, `/api/v1/admin/preguntas/*`, `/api/v1/admin/importaciones/*` | duplicate detection + atomic publication DESIGNED (`APPLY_PENDING_APPROVAL`, not wired live); resources, live Supabase/RLS and runtime remain open | source + Screen 09 commits `9eea7da`/`3c09713`, integrated | staged/reviewable mobile import; not ready for real client data |
| `EXAM_ACCESS` / Exam Access | access control | `AdminSection.EXAM_ACCESS` | `features:exam-access` | `ExamAccessScreen.kt` | YES | PARTIAL | PARTIAL | PARTIAL | `AdminOperationsRepository` | `/admin/exam-access`, `/api/v1/admin/accesos` | legacy schema dependence | source + audit | no role assumption |
| `STORE` / Store | commerce | `AdminSection.STORE` | `features:store` | `StoreScreen.kt` | YES | PARTIAL | PARTIAL | UNKNOWN | `SupabaseCommerceRepositoryImpl` | direct Supabase expected by code; Ktor family UNKNOWN | direct client persistence risk; live not verified | source + audit | no implementation in this pass |
| `TICKETS` / Tickets | ticket wallet | `AdminSection.TICKETS` | `features:tickets` | `TicketsScreen.kt` | YES | PARTIAL | PARTIAL | PARTIAL | `AdminOperationsRepository` | `/admin/ticket-wallets`, `/api/v1/admin/tickets` | legacy schema dependence | source + audit | operational admin flow |
| `SUPPORT` / Support | support | `AdminSection.SUPPORT` | `features:support` | `SupportScreen.kt` | YES | PARTIAL | PARTIAL | PARTIAL | `AdminOperationsRepository` | `/admin/support/conversations`, `/api/v1/admin/soporte` | current enums vs target workflow | source + audit | target bidirectional support |
| `MODERATION` / Moderation | moderation | `AdminSection.MODERATION` | `features:moderation` | `ModerationScreen.kt` | YES | PARTIAL | PARTIAL | PARTIAL | `KtorModerationRepositoryImpl` | `/admin/moderation/actions`, `/api/v1/admin/moderacion` | runtime reachability not verified | source + audit | preserve operational usability |
| `AUDIT` / Audit | audit | `AdminSection.AUDIT` | `features:audit` | `AuditScreen.kt`, `AuditViewModel.kt` | YES | BROKEN | PARTIAL | UNKNOWN | `AuditRepositoryImpl` | `/api/v1/admin/auditoria`, `/admin/audit` | BUILD_BLOCKING: `System` unresolved | source + `06_UI_SCREEN_INVENTORY.md` | no fix in documentation pass |

## Required screen truth

### Auth

- `CURRENT: BYPASSED`.
- Cause: `TEMPORARILY_DISABLE_LOGIN = true`.
- `TARGET: real admin authentication`.

### Audit

- `CURRENT: BUILD_BLOCKING`.
- Cause: `AuditViewModel.kt` unresolved `System`.

### Screen 09 — Content Upload

- `CURRENT: PASS_WITH_GAPS`. Integrated into `integration/mobile-finish` (originally `feature/mobile-content-upload` / `9eea7da`, `3c09713`).
- `CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: NO`.
- Runtime, live Supabase/RLS compatibility, asynchronous processing and immersive resources remain pending.
- Duplicate handling and atomic publication: **designed**, not closed — a candidate RPC (`admin_import_lote_preguntas`) exists at `supabase/canonical/content_import_atomicity_candidate_v1/`, status `APPLY_PENDING_APPROVAL`, not applied to any database, not wired into the active route. The live route can still report `parcial`.

### Screen 10 — Estado del sistema

- `CURRENT: IMPLEMENTED / NOT_CONNECTED`. Integrated into `integration/mobile-finish` (originally `feature/mobile-settings` / `9efd31b`).
- Route: `AppRoute.System.Status` (independent from the legacy `AppRoute.System.Diagnostics`), opened from the Settings row.
- `NotConnectedSystemStatusRepository` backs the screen today: the UI is real and prepared for overall status, Ktor, Supabase, worker, CPU, RAM, latency, errors, crashes, queue and `updatedAt`, but no real aggregation backend exists yet — no fake metrics are shown.

## Navigation and scope notes

- Desktop entry: `DesktopModerationRoot → AppRoot`; source exposes the administrative `AdminSection` set.
- Mobile entry: `MobileContentRoot → MobileContentScreen/MobileAdminPanel`; the mobile panel exposes the operational subset plus the Dashboard quick action to `AppRoute.Content.Imports` for Screen 09.
- The source also contains `SETTINGS`, but the required product registry here intentionally covers the 17 requested sections and does not count Settings as one of them.
- `AppRoute.System.Status` (Screen 10) is now implemented (`SystemStatusScreen`/`SystemStatusViewModel`), opened from the Settings row; it remains separate from the legacy `AppRoute.System.Diagnostics` route. Backed by `NotConnectedSystemStatusRepository` — real UI, no real backend yet, no fake metrics.
- Runtime visual reachability remains `NOT_VERIFIED`.
