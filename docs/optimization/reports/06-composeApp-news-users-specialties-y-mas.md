# Revisión — `composeApp` (36) + `features/news,users,specialties,opositester,forum,reports,store` (111) = 147/147

Corrección propia del revisor: el encargo original decía "~131 ficheros"; el recuento real es 147. `MobileHomeScreen.kt` (503 líneas, en `features/dashboard`) NO entra en este alcance y no se revisó aquí (lo cubre el informe 05). `composeApp` tiene 7 ficheros de test (no 2): `NavigationInformationArchitectureTest`, `AppRootContentTargetTest`, `AppNavigatorTest`, `NavigationBackTest`, `NavigationIconsTest`, `AppNavigationCatalogTest`, `PlatformRuntimeConfigSourceJvmTest`.

---

## TIER 1 — máximo valor ÷ riesgo

### T1.1 · Tres copias BYTE-IDÉNTICAS del formateador de tiempo relativo

**APLICADO.** `formatRelativeTime` (`ReportsModels.kt`), `formatForumRelativeTime` (`ForumModels.kt`), `formatTesterRelativeTime` (`TesterReportModels.kt`) — diff de cuerpos confirmó cero diferencias. Canónico creado en `core/common/.../presentation/RelativeTime.kt`; los tres nombres locales se quedan como delegaciones de una línea (ningún call site tocado). Los tres módulos ya dependían de `core/common`.

### T1.2 · `FilterIconButton` idéntico en tres features

**APLICADO.** `ForumScreen.kt`, `TesterListScreen.kt`, `ReportsScreen.kt` — diff = sólo comentarios. Canónico en `core/ui/.../FilterBar.kt`; los tres se quedan como delegaciones con `@Composable`.

### T1.3 · `LoadingList` idéntico en tres features

**APLICADO.** Mismo patrón que T1.2, mismo fichero canónico.

### T1.4 · Andamiaje de pantalla-detalle duplicado Reports ↔ OposiTester

**APLICADO parcialmente**: `DetailSection` y `LoadingDetail` (byte-idénticos) extraídos a `core/ui/.../DetailScaffold.kt`. `StatusPill`: la carcasa (`GlassSurface`+`Row`+`Box`+`Text`) se extrajo como `DetailStatusPill(label, color)`; el `when` que mapea cada estado a su color se dejó LOCAL en cada pantalla (los vocabularios de estado son distintos — OposiTester tiene un estado más, `REVIEWED`), decisión explícita del propio informe.
**NO aplicado**: `private enum class PendingAction { RESOLVE, DISCARD }` duplicado en los dos ficheros — valor casi nulo (es wiring de diálogo local a cada pantalla), se dejó como está.

### T1.5 · Composables MUERTOS (ficheros enteros)

**APLICADO.** `composeApp/.../root/AdminShell.kt` y `.../root/DesktopModerationRoot.kt` borrados. Cero call sites verificados en todo el repositorio.

### T1.6 · `expect`/`actual` muerto: `isMobileContentExperience()`

**APLICADO.** Los 4 ficheros `AppExperience.kt`/`.android.kt`/`.ios.kt`/`.jvm.kt` borrados. El corte móvil/escritorio real lo decide `appBreakpointFor(maxWidth)` en `AppRoot.kt`.

### T1.7 · Función privada muerta `toBurstCampaignType`

**APLICADO** (sólo el borrado de la función muerta). `StoreViewModel.kt:310-315` — cero usos dentro de su propio fichero (la homónima viva está en `data/.../KtorCommerceRepositoryImpl.kt:335`, símbolo distinto).
**NO aplicado — es R.1 abajo**: esta función muerta es el síntoma visible de que `StoreState.burstType` se edita en pantalla y nunca se lee al guardar. Eso es un bug/decisión de producto, no se toca aquí.

### T1.8 · Condicionales que ya colapsan solos (ambas ramas iguales)

**APLICADO (dos de tres):**
- `AppNavigator.kt:110`: `UNAUTHENTICATED -> if (requested is AppRoute.Auth) AppRoute.Auth else AppRoute.Auth` → `UNAUTHENTICATED -> AppRoute.Auth`.
- `SpecialtiesViewModel.kt:164-165`: rama `Loading||Idle -> current.exams` y `else -> current.exams` idénticas → colapsadas a un solo `else`.

**NO aplicado a propósito — es R.3 abajo**: `NewsViewModel.kt:386` (`if (item.imagePath.isNullOrBlank()) "linked" else "linked"`) también colapsa sintácticamente, pero el revisor advierte que es probablemente un copy-paste de la línea 385 (que sí distingue `"local"`/`"linked"`) y colapsarlo escondería la pregunta de cuál de las dos líneas está mal. Se dejó sin tocar.

### T1.9 · `AppRoot` duplicaba en línea el cuerpo de `canNavigateBack()`

**APLICADO.** `AppRoot.kt:463`: `enabled = backStack.size > 1 || route.parentRoute() != null` → `enabled = navigator.canNavigateBack()`. El import de `parentRoute` (que quedó sin otro uso) se sustituyó por el de `canNavigateBack`. Esto hace que `NavigationBackTest.canNavigateBack coincide con lo que handleSystemBack va a hacer` pase a proteger producción de verdad (antes protegía una función sin llamador).

### T1.10 · `userCapabilities()` se reconstruía en cada recomposición

**APLICADO.** `UserPresentation.kt` — la lista de 10 `UserCapability` pasó de `fun userCapabilities(): List<UserCapability> = listOf(...)` a `private val USER_CAPABILITIES: List<UserCapability> = listOf(...)`; `userCapability(name)` y `userUnavailableCapabilities()` (llamadas desde composición en `UserEditorPane.kt`) ahora leen el val memoizado.

---

## TIER 2 (ninguno aplicado — todos piden test/harness nuevo, revisión sitio a sitio, o son de riesgo medio real)

- **T2.1** ~12 sitios de trabajo de listas en composición sin `remember`, el de más valor: `NewsPreviewPane.kt:208` (`sortedBy`+`filter` en el camino caliente de arrastre, se emite en cada frame de puntero). Resto: `NewsPreviewPane.kt:310-315,218,425,432`, `SpecialtySummaryPanel.kt:79-80`, `UserDetailPanel.kt:145`, `NewsPublicationPane.kt:206`, `ReportsScreen.kt:111`, `AppRoot.kt:422-427,514-515`, `MobileAdminPanel.kt:404-412,1276`, `MarketScreen.kt:76`. Cubiertos por harness visuales de specialties/users/news donde existen; SIN NINGUNO en AppRoot/MobileAdminPanel/ReportsScreen/MarketScreen.
- **T2.2** Rama `onDelete` muerta en `AdminUsersActionRow`/`AdminActionRow` de `MobileAdminPanel.kt` — los 5 call sites pasan `null`. Andamiaje de una retirada documentada, no funcionalidad pendiente.
- **T2.3** Rama `NewsIntent.Publish` inalcanzable en el diálogo de confirmación de `NewsPublicationPane.kt:271-280`.
- **T2.4** `ReportsScreen.FilterPill` NO lleva `.minimumInteractiveComponentSize()` mientras Foro y OposiTester sí — **riesgo MEDIO**, cambia tamaño mínimo medido, no es cero-píxel por inspección.
- **T2.5** Paginación duplicada Foro↔OposiTester (`loadFirstPage`/`refresh`/`loadMore`/`mergeX` calcados) y AUSENTE en Informes (sin scroll infinito). Fusionar VM es riesgo medio.

## TIER 3 (ninguno aplicado)

- T3.1 `currentState()` repetido en una misma expresión en varios ViewModels.
- T3.2 Literales donde el token tiene el valor exacto: `UserEditorPane.kt:211-212` (36dp/18dp), `MobileAdminPanel.kt:86-90` (12/8/10/14dp → `Radius.*`), `NewsPreviewPane.kt:84,86` y `NewsPublicationPane.kt:172` (`RoundedCornerShape(12.dp)` → `Radius.card`).
- T3.3 `specialtyCheckDetailColor` — `when` con tres brazos idénticos pero exhaustivo a propósito (añadir un cuarto estado sería error de compilación si se colapsa). Defendible dejarlo.
- T3.4 KDoc mal colocado en `main.kt:14-29` (documenta tamaño de ventana pero cuelga de `initializeDesktopSentry()`, no de `fun main()`).
- T3.5 Número mágico `10 * 1024 * 1024` ×3 en `NewsAssets.jvm.kt`.
- T3.6 14 `get()` posicionales en Koin (`NewsFeatureModule.kt`, `UsersFeatureModule.kt`) — reordenar dos parámetros del mismo tipo recablearía el grafo en silencio; otros módulos ya nombran los parámetros.

---

## REGISTRAR, NO TOCAR

- **R.1** `StoreState.burstType` se edita en pantalla y `StoreViewModel.saveBurst()` nunca lo lee (fija `EXAM_BURST` siempre). Mismo fallo que el proyecto ya retiró en `MobileAdminPanel.kt` para "Usuario activo". Cablear o quitar el campo es decisión de producto.
- **R.2** `NewsIntent.SelectBackgroundFile` lleva `suggestedName`/`mimeType` que `NewsViewModel` no lee (sólo usa `uri`). Los emisores están en OTRO feature (`features/exams/.../MobileLayeredNewsEditor.kt`), así que tocar el intent cruza módulos.
- **R.3** Ver T1.8 — asimetría `NewsViewModel.kt:385-386`, posible copy-paste. Decidir primero cuál de las dos líneas está mal.
- **R.4** KDoc de `ReportDetailIntent.kt:5` contradice el código: dice que `"ocultar"` es la única acción real, pero `ReportDetailViewModel` envía `"resuelto_sin_accion"` y su propio test asserta que NO es `"ocultar"`.
- **R.5** `AppRouteMetadata.allowedRoles`/`requiresAuth` son write-only o casi — la guarda real es `resolveGuardRoute` sobre `SessionState`. Asunto de Auth/Security, no de optimización.
- **R.6** `AppDestination` muerto pero deprecado a propósito (mención en prosa en `AppRoot.kt:1006`).
- **R.7** `AppNavigator.popTo()` sin llamador de producción — superficie de API del navegador, sólo usado en tests.
- **R.8** `TEMPORARILY_DISABLE_LOGIN` — AGENTS.md pone el login deshabilitado explícitamente fuera de alcance de esta reorganización.
- **R.9** Las dos ramas `AuthScreen` de `AppRoot.kt:443-451` son idénticas, pero fusionarlas toca la guarda de login que R.8 deja fuera de alcance.
- **R.10** `NewsViewModel.kt:111` inyecta `upsertNewsUseCase` que no usa en el cuerpo de la clase — quitarlo toca módulo DI + test.
- **R.11** Android decodifica imágenes sin tope (`NewsAssets.android.kt:30-40`, `BitmapFactory.decodeStream` sin límite ni `inSampleSize`); JVM sí impone 10MB. Asimetría de plataforma real, no limpieza.
- **R.12** Listas de lambdas `@Composable` construidas por recomposición en `SpecialtyExamTypesSection.kt`, `SpecialtyImpactSection.kt`, `SpecialtySummaryPanel.kt` — izar lambdas composables cambia cuándo Compose puede saltar; son justo las pantallas con cobertura de harness a nivel de píxel, no tocar sin cuidado extra.
- **R.13** `BoxWithConstraints` anidados en `UserDetailPanel.kt:79,165` dentro del de la raíz — son breakpoints responsive reales.
- **R.14** `UserPlanCard` duplica cinco filas de `UserEconomyTab` (`UserDetailPanel.kt:346-361,485-499`) — ambas en capturas de `UsersVisualHarness`, extraer exige preservar el default de `columns` exactamente.
- **R.15** Seis copias de `friendlyErrorTitle`/`friendlyErrorMessage` con textos DISTINTOS a propósito (uno por pantalla: "el foro"/"las incidencias"/"los informes"/"este hilo") — sólo el brazo `Authentication` es idéntico en las seis. NO es dedup mecánico, exige decisiones de copy.

---

## Cierre

`FILES_IN_SCOPE = 147`, `FILES_READ = 147` (132 leídos directamente + 15 ya cubiertos por el informe 03). Ninguno sin leer.
