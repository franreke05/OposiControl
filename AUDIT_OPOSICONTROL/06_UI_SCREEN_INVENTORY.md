# UI Screen Inventory

Todas las rutas de panel son navegación por estado (`AppDestination.Admin(AdminSection)`), no URLs persistentes. Entry point desktop: `DesktopModerationRoot` → `AppRoot`; Android/iOS: `MobileContentRoot` → `MobileContentScreen`/`MobileAdminPanel`.

| ID / sección | Archivo | Alcanzable | Estado |
|---|---|---:|---|
| AUTH | `features/auth/.../AuthScreen.kt` | No por defecto | UI_ONLY/PARTIAL: login implementado pero bypass global activo |
| DASHBOARD | `features/dashboard/.../DashboardScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| USERS | `features/users/.../UsersScreen.kt` | Sí | FUNCTIONAL; CRUD y roles |
| ROLES | `features/roles/.../RolesScreen.kt` | Sí | PARTIAL/read-only backend |
| SPECIALTIES | `features/specialties/.../SpecialtiesScreen.kt` | Sí | FUNCTIONAL |
| COMMUNITIES | `features/communities/.../CommunitiesScreen.kt` | Sí | FUNCTIONAL |
| TAGS | `features/tags/.../TagsScreen.kt` | Sí | FUNCTIONAL |
| NEWS_TYPES | `features/news-types/.../NewsTypesScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| NEWS | `features/news/.../NewsScreen.kt` | Sí | FUNCTIONAL/PARTIAL: editor/canvas y persistencia con drift |
| EXAMS | `features/exams/.../ExamsScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| QUESTIONS_RESOURCES | `features/exams/.../ExamsScreen.kt` (`QuestionsResourcesScreen`) | Sí | FUNCTIONAL/PARTIAL |
| EXAM_ACCESS | `features/exam-access/.../ExamAccessScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| STORE | `features/store/.../StoreScreen.kt` | Sí | PARTIAL: capacidades avanzadas tolerantes/optional |
| TICKETS | `features/tickets/.../TicketsScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| SUPPORT | `features/support/.../SupportScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| MODERATION | `features/moderation/.../ModerationScreen.kt` | Sí | FUNCTIONAL/PARTIAL |
| SETTINGS | `features/settings/.../SettingsScreen.kt` | Sí | UI_ONLY/local-only |
| AUDIT | `features/audit/.../AuditScreen.kt` | Sí | PARTIAL; compilación global rota en su ViewModel |

No se verificó reachability visual en runtime. `MobileAdminPanel` expone un subconjunto administrativo distinto al desktop.
