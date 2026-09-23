# Cambios aplicados — optimización Kotlin/KMP

Baseline: `7187040ca1b323329a9525627c6339f40de9308a`
Rama: `refactor/kotlin-kmp-professional-optimization`
Worktree: `OposiControl-KotlinOptimizer`

Verificado con `git diff --name-status 7187040` (working tree vs baseline;
`HEAD` sigue siendo `7187040` porque el `commit` está prohibido en este
worktree) contra el baseline: **84 ficheros `.kt` cambiados** (70 `M` + **14**
`D`, recuento exacto de Git, no estimado), **326 inserciones(+), 5.199
eliminaciones(-)** en lo trackeado, más 2 ficheros `.kt` nuevos sin trackear
(116 líneas). Total: 86 ficheros tocados, 442 inserciones, 5.199 eliminaciones,
neto **−4.757 líneas**.

**Corrección sobre una versión anterior de este documento**: decía "8 ficheros
borrados por completo". Era un error de redacción del título de la tabla de
abajo — la propia tabla siempre enumeró los 14 (una fila agrupaba visualmente
los 4 ficheros de `AppExperience`/`.android`/`.ios`/`.jvm` bajo un solo renglón
de prosa). `DELETED_FILES_GIT = 14` es la cifra correcta y coincide con lo que
la tabla de abajo enumera fichero por fichero.

Escritor único durante toda la aplicación. Gate por unidad: compilar el/los
módulo(s) afectado(s) + ejecutar su(s) suite(s) de test antes de pasar al
siguiente cambio. Gate final: `jvmTest`/`test` en los 26 módulos con tests de
todo el repositorio (excluidos los targets Android, sin SDK configurado en
este worktree) — **BUILD SUCCESSFUL, 0 fallos**.

## Ficheros borrados por completo (14, `git diff --name-status` status `D`)

| Fichero | Líneas | Motivo |
|---|---|---|
| `data/.../repository/SupabaseContentRepositoryImpl.kt` | 981 | Nunca instanciado (H1) |
| `data/.../repository/KtorContentRepositoryImpl.kt` | 936 | Nunca instanciado (H1) |
| `data/.../repository/SupabaseCommerceRepositoryImpl.kt` | 344 | Ya `@Deprecated`, nunca instanciado (H1) |
| `data/.../repository/SupabaseNewsRepositoryImpl.kt` | 238 | Legacy, nunca instanciado (H1) |
| `data/.../remote/dto/ContentDtos.kt` | 255 | Único importador era el `SupabaseContentRepositoryImpl` borrado (H1) |
| `features/exams/.../ExamQuestionsSection.kt` | 407 | Composable sin llamador (T1.1) |
| `features/exams/.../ExamResourcesSection.kt` | 228 | Composable sin llamador (T1.1) |
| `composeApp/.../root/AdminShell.kt` | 36 | Sin llamador (T1.5) |
| `composeApp/.../bootstrap/AppExperience.kt` + `.android.kt` + `.ios.kt` + `.jvm.kt` | 3+3+3+3 | `expect`/`actual` sin ningún call site (T1.6) |
| `composeApp/.../root/DesktopModerationRoot.kt` | 13 | Sin llamador (T1.5) |
| `core/common/.../presentation/AppUiState.kt` | 39 | Jerarquía de error paralela, cero consumidores (1.2) |

**Total borrado en ficheros completos: 3.489 líneas.**

## Ficheros nuevos (2)

- `core/common/.../presentation/RelativeTime.kt` — canónico de `formatRelativeTime`, antes triplicado byte a byte en Informes/Foro/OposiTester.
- `core/ui/.../components/DetailScaffold.kt` — canónicos `DetailSection`, `LoadingDetail`, `DetailStatusPill`, antes duplicados entre Informes y OposiTester.

(`core/ui/.../components/FilterBar.kt` no es nuevo pero recibió 61 líneas nuevas: los canónicos `FilterIconButton` y `LoadingList`.)

## Resumen por módulo de lo aplicado

### `backend/ktor` (src/main)
- `Application.kt` −117 líneas: clase `InMemoryGateway` completa, sin consumidores (A3).
- `AdminGateway.kt` +29/−(neto) líneas: `settings()` unificado con `AdminV1Routes` vía `adminSettingsFromEnvironment()` (A9); KDoc reescrito tras H1.
- `AdminV1Routes.kt`: usa `opositesterRewardIdempotencyKey(id)` en vez de literal a mano (A7); `settings()` delega en el canónico (A9).
- `AppV1Routes.kt` −5 líneas: `hasModeratorOrAdminRole` sin llamador, borrado (B11).

### `backend/ktor` (src/test) — dedup de andamiaje, cero cambio de aserciones
- `AdminAnalyticsGatewayTest.kt`, `AdminDashboardOverviewTest.kt`, `AdminV1ExamQuestionsTest.kt`: stub HTTP privado (`StubResponse`/`StubHttpClient`/`StubHttpResponse`, ~186 líneas) sustituido por el canónico `V1Stub*` de `SupabaseV1GatewayExamAccessTest.kt` (1.1).
- 6 ficheros: `bodyText()` (17 líneas cada uno) sustituido por `readRequestBody()` canónico (1.2).
- 10 ficheros: `describe()` sustituido por `describeRequests()` canónico; la 11ª copia (`IndependentDashboardFanoutContractTest`, que omite el verbo HTTP) se dejó intacta a propósito (1.3).

### `backend/dbtest`
- `PostgresHarness.kt`: `version()` y `Connection.entero()` borrados (sin uso); `puedeEjecutar(rol)` promovido desde `RoleSyncRollbackAndCollationTest.kt` (T1-a, T1-b, T1-c).
- `RoleSyncInstallationTest.kt`, `RoleSyncRollbackAndCollationTest.kt`: usan el helper promovido.
- Verificado con la suite REAL contra PostgreSQL embebido: 45/45 tests, 0 fallos.

### `domain` + `data`
- `ContentRepository.kt`, `KtorCommerceRepositoryImpl.kt`: KDoc reescrito tras H1 (ya no citan clases borradas).
- `KtorSystemStatusRepositoryImpl.kt` / `KtorDashboardOverviewRepositoryImpl.kt`: un solo parser de `SystemStatusLevel` (H4).
- `KtorModerationRepositoryImpl.kt`: `listPendingReports()` delega en `listReports(false)` (H5).
- `AuditMapper.kt` −10 líneas: `AuditEvent.toDto()` sin uso (H6a).
- `ContentModels.kt` −2 líneas: `isPremium`/`isGratuito` sin lectores (H6b).
- `KtorAdminV1ContentRepositoryImpl.kt`: `postExamState` sin el parámetro `examId` que no usaba (H6c); plantilla de string innecesaria simplificada (H6d).
- `KtorTagSearchRepositoryImpl.kt` −82 líneas netas: reescrito para dejar sólo lo que hace de verdad (H3); `DataModule.kt` actualizado.
- `ContentImportUseCases.kt`: usa `AppResult.map` en vez de reimplementarlo (H13).
- `ContentImport.kt`: `canonicalQuestionType` delega en `ContentExamType.fromApiValue(...).questionApiValue` (H14).
- `SettingsLocalDataSource.kt`: una sola consulta a la base al arrancar, no dos (H15).
- **9 repositorios** (`KtorAdminOperationsRepositoryImpl`, `KtorCommerceRepositoryImpl`, `KtorCommunitiesRepositoryImpl`, `KtorConnectionDiagnosticsRepositoryImpl`, `KtorForumRepositoryImpl`, `KtorModerationRepositoryImpl`, `KtorNewsRepositoryImpl`, `KtorTagsRepositoryImpl`, `KtorTesterRepositoryImpl`): `backendHeaders()` copiado sustituido por `adminAuthHeaders()` canónico de `AdminApiAuth.kt` (H2).

### `core/common`
- `AppResult.kt` −5 líneas: `onFailure` sin uso, borrado (`map` se conservó — H13 de domain/data le creó call sites reales en la misma sesión).
- `RelativeTime.kt` (nuevo): canónico de tiempo relativo.

### `core/network`
- `NetworkResultAdapter.kt`: 3 `Regex` que se compilaban en cada error de red subidas al `companion object`, junto a la ya existente `REFERENCE_PATTERN` (1.1).

### `core/ui`
- `FilterBar.kt` +61 líneas: canónicos `FilterIconButton` y `LoadingList` (T1.2/T1.3).
- `DetailScaffold.kt` (nuevo): canónicos `DetailSection`, `LoadingDetail`, `DetailStatusPill` (T1.4).

### `composeApp`
- `AppNavigator.kt`: rama `if/else` con ambos brazos iguales, colapsada (T1.8).
- `AppRoot.kt`: reutiliza `navigator.canNavigateBack()` en vez de repetir su cuerpo inline (T1.9); import `parentRoute` (que quedó huérfano) sustituido por `canNavigateBack`.
- `AdminShell.kt`, `DesktopModerationRoot.kt`, `AppExperience.kt` (+3 `actual`): borrados por no tener llamador (T1.5, T1.6).

### `features/exams`
- `ExamsScreen.kt` −372 líneas: `QuestionsResourcesScreen`+`EditorField` (267+26) y `CoverEditor` (76) sin llamador (T1.1, T1.2).
- `ExamWorkspaceControls.kt` −200 líneas: `ExamWorkspaceCoverEditor`, `ExamCoverMarker`, `ExamMetaLine`, `coverWidth`/`coverHeight` sin llamador tras la retirada documentada de portada (A2, A3).
- `MobileContentScreen.kt` −34 líneas: `toTicketLabel()`, `NewsActiveToggle` sin llamador (T1.3).
- `ExamPublicationPresentation.kt` −5 líneas: `examDeletionNoticeIcon` sin llamador (A3).
- `ExamBasicInfoVisualHarness.kt` −56 líneas: 3 fixtures de portada muertos + 5 imports AWT huérfanos (T1.4).
- `ExamsViewModel.kt`: doble normalización de `statusFilter` eliminada (T1.5).
- `ContentUploadScreen.kt` −2 líneas: dos `val selected` calculados y nunca leídos (T1.7; el hueco visual que dejan al descubierto queda registrado como R.10, decisión de producto).
- `QuestionRelationsSection.kt` −1 línea: `val tokens` sin leer (T1.7).

### `features/moderation`
- `ModerationIntent.kt`, `ModerationViewModel.kt`: `UpdateExecutor` sin ningún emisor, borrado (3.1).

### `features/specialties`
- `SpecialtiesViewModel.kt`: rama de guarda y `else` con el mismo cuerpo, colapsadas (T1.8).

### `features/store`
- `StoreViewModel.kt` −7 líneas: `String.toBurstCampaignType()` privada sin uso (T1.7; el campo `burstType` que la habría alimentado se deja como está — R.1, decisión de producto).

### `features/users`
- `UserPresentation.kt`: `userCapabilities()` (reconstruía una lista de 10 elementos en cada composición) memoizada como `private val USER_CAPABILITIES` (T1.10).

### `features/reports` + `features/forum` + `features/opositester`
- `ReportsModels.kt`, `ForumModels.kt`, `TesterReportModels.kt`: `formatRelativeTime`/`formatForumRelativeTime`/`formatTesterRelativeTime` (39 líneas cada una, byte-idénticas) delegan en el canónico de `core/common` (T1.1).
- `ReportsScreen.kt`, `ForumScreen.kt`, `TesterListScreen.kt`: `FilterIconButton` y `LoadingList` delegan en `core/ui` (T1.2, T1.3).
- `ReportDetailScreen.kt`, `TesterDetailScreen.kt`: `DetailSection` y `LoadingDetail` delegan en `core/ui`; `StatusPill` delega su carcasa en `DetailStatusPill(label, color)`, conservando local el `when` que mapea cada estado a su color (vocabularios distintos entre los dos módulos) (T1.4).

## Lo que NO se aplicó, y por qué

Cada uno de los 8 informes en `docs/optimization/reports/` lista sus hallazgos
Tier 2/3 y su sección REGISTRAR, NO TOCAR con la razón explícita de por qué no
se tocaron en esta sesión: necesitan un test nuevo que no existe, cambian
comportamiento observable (texto de error, orden en pantalla, timing), o son
decisiones de producto/arquitectura que exceden el mandato de una revisión de
optimización. No se aplicó nada de eso; se documentó con precisión de
fichero:línea para que la siguiente sesión no tenga que redescubrirlo.

## Certificación independiente posterior (segunda pasada, read-only + 2 fixes triviales)

A petición del usuario, se hizo una segunda pasada de certificación exclusiva
sobre el delta (no se re-auditaron los 731 ficheros), con dos revisores
independientes (que no habían escrito el código) más verificación mecánica
directa de git/DI/expect-actual/recursos. Resultado: **0 regresiones
encontradas** en los 84+2 ficheros del diff. Dos correcciones triviales,
íntegramente dentro del propio diff del optimizador, se aplicaron durante esta
pasada porque son inequívocamente parte del refactor (no comportamiento
nuevo):

- `AuditMapper.kt`: una línea en blanco sobrante al final del fichero,
  artefacto mecánico del borrado de `toDto()` (H6a). `git diff --check` lo
  señaló. Sin efecto en compilación ni tests; corregido por higiene de diff.
- `KtorForumRepositoryImpl.kt` / `KtorTesterRepositoryImpl.kt`: el import
  `io.ktor.http.HttpHeaders` se había restaurado de más al arreglar un error
  de compilación real que sólo afectaba a `KtorNewsRepositoryImpl.kt` (que sí
  usa `HttpHeaders.ContentDisposition`/`ContentType` en un multipart, sin
  relación con el dedup de `backendHeaders()`). En Forum/Tester el import
  quedó sin ningún uso. Detectado por un revisor independiente, verificado
  directamente, y quitado. Cero cambio de comportamiento.

Ver el mensaje de certificación entregado al usuario para los campos
estructurados (`DELETED_FILES_MATCH`, `AUTH_HEADERS_EQUIVALENCE`,
`COMPOSE_DEDUP_EQUIVALENCE`, etc.) y el detalle completo de ambas auditorías
independientes.
