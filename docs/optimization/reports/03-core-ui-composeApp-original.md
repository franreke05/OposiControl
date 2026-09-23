# Informe de optimización — `core/ui` (40 commonMain) + `composeApp` (36) + 7 features (73 ficheros del lote original antes de la reconciliación de cobertura)

Rama `refactor/kotlin-kmp-professional-optimization`. Ley aplicada: **BEHAVIOR PRESERVATION > FEWER LINES**.

Nota transversal del revisor: `derivedStateOf` no se usa en ninguna parte del repositorio (grep sobre 454 ficheros, 0 ocurrencias). No propuso introducirlo.

Estado de aplicación: **NINGUNO de estos hallazgos se ha aplicado todavía** (llegaron en el lote inicial antes de completar la cobertura del resto del repo, y la prioridad pasó a cerrar el hueco de cobertura). Quedan documentados aquí íntegros para una tanda futura. Los de `composeApp` que SÍ se solapan con hallazgos ya aplicados desde el informe 05 (p.ej. `isMobileContentExperience`, `AdminShell`) están marcados abajo.

---

## A · Código muerto (valor alto / riesgo ~0)

**A1 · Dos ficheros de UI enteros, inalcanzables (635 líneas)** — `ExamQuestionsSection.kt` (407) y `ExamResourcesSection.kt` (228) en `features/exams`. **YA APLICADO** (ver informe 04 / ledger: ambos ficheros borrados).

**A2 · Editor de portada de escritorio, muerto (~135 líneas)** — `ExamWorkspaceCoverEditor` (`ExamWorkspaceControls.kt:695`), `ExamCoverMarker` (`:815`), `coverWidth`/`coverHeight` (`:137`/`:147`). **YA APLICADO** (ver ledger). Huérfanos por retirada documentada de la portada (2026-09-23). Nota original del revisor: quitarlos deja sin lectores de producción a `examCoverPresentation` (`ExamWorkspacePresentation.kt:806`) y `EXAM_COVER_FORMATS` (`:748`), ambos aún ejercitados por ~200 líneas de `ExamWorkspacePresentationTest` (1058-1259, 1757-1759) sobre código que ya no pinta nadie. **NO tocado**: decidir aparte qué hacer con esa capa de presentación y sus tests.

**A3 · Cinco símbolos sueltos sin usar** — `ExamsScreen.kt:936 CoverEditor` (**aplicado**, ver ledger), `MobileContentScreen.kt:3087 NewsActiveToggle` (**aplicado**), `DashboardScreen.kt:1011 DashboardPreview` (**NO aplicado** — es un `@Preview`, convención viva del repo con 4 usos, no código muerto real), `ExamPublicationPresentation.kt:531 examDeletionNoticeIcon` (**aplicado**), `ExamWorkspaceControls.kt:419 ExamMetaLine` (**aplicado**).

**A4 · Documentación obsoleta** — `ExamWorkspaceTabs.kt:30` («Las seis vistas del examen» sobre un enum con cuatro entradas) y `ExamBasicInfoSection.kt:17` («…y con qué imagen se guarda», contradice la retirada de portada). **NO aplicado** — cosmético, pendiente.

---

## B · Componentes Compose duplicados entre features (el hallazgo de más valor — NINGUNO aplicado todavía)

**B1 · `ExamSolidPanel` ≡ `SpecialtySolidPanel`** — `ExamWorkspaceControls.kt:301` vs `SpecialtiesWorkspaceControls.kt:130`. Cadena de modificadores idéntica: `fillMaxWidth().clip(Radius.card).background(surfaceSolid).border(1.dp, borderColor ?: border, Radius.card).padding(padding)`. Única diferencia: Especialidades acepta `background: Color?` extra. El KDoc de Especialidades ya lo admite. Propuesta: `SolidPanel(modifier, padding, borderColor, background, verticalArrangement, content)` en `core/ui`. Riesgo muy bajo. Cubierto por `ExamWorkspaceVisualHarness`, `SpecialtiesVisualHarness`, `ExamCheckContrastTest`, `SpecialtiesContrastTest`.

**B2 · Píldora de filtro: carcasa y colores duplicados, valor por valor** — `ExamFilterPill` (`ExamCatalogPane.kt:457`) vs `SpecialtyStatusFilterPill` (`SpecialtiesCatalogPane.kt:303`). Carcasa, arrangement (4dp en ambos), fondo, texto, icono y borde idénticos función a función; sólo cambia el slot de contenido (CHECK delante vs CHEVRON_DOWN detrás). Propuesta: `FilterPillShell(active, onClick, content)` + una función de color en `core/ui`. Elimina 4 funciones de color duplicadas y el literal `accent.copy(0.55f)` incrustado en `ExamCatalogPane.kt:474`. Riesgo muy bajo. Cubierto por los dos contrast tests y los dos harness visuales.

**B3 · `ImportTextField` ≡ `QuestionTextField`** — `ImportScreenParts.kt:57` vs `QuestionsScreenParts.kt:132`. Idénticos hasta el byte salvo que `QuestionTextField` añade un `Text(supportingText)` opcional al final. Propuesta: `SolidTextField(..., supportingText: String? = null)` en `core/ui`. ~45 líneas duplicadas fuera. Riesgo muy bajo. Cubierto por `ImportVisualHarness`, `QuestionsVisualHarness`.
**Contraejemplo que NO hay que tocar**: `ImportDropdown` (`ImportScreenParts.kt:102`) y `QuestionSelect` (`QuestionsScreenParts.kt:188`) parecen hermanos y no lo son (`inputHeight` vs `buttonCompact`, `bodyMedium` vs `bodySmall`, `fieldBorder` vs `border`, 9dp vs 6dp). Fusionarlos movería píxeles.

**B4 · El Design System ya tiene la función que el Dashboard reimplementó** — `DashboardV2Screen.kt:1774 StatusTone.dotColor()` duplica exactamente `statusToneColor` (`core/ui/.../StatusChip.kt:173`), que es `internal` (por eso la feature no podía llamarla). Propuesta: exponerla y borrar la copia local. Riesgo nulo (mapeo idéntico verificado). Hoy la copia del Dashboard NO está protegida por `StatusToneTest`/`ContrastTest`.

**B5 · Dos objetos de medidas con nueve valores idénticos** — `ExamWorkspaceSizing` (`ExamWorkspaceControls.kt:72-189`) y `SpecialtiesSizing` (`SpecialtiesPresentation.kt:44-100`): catálogo ancho 330, normal 310, rail/resumen 300, `columnGap = Spacing.lg`, `editorMinWidth = 700`, `stackedCatalogMaxHeight = 480`, `sectionPadding`, `sectionIconChip`, `scrollbarGutter = 10.dp`. Riesgo bajo, pero acopla dos features — hacerlo después de B1/B6.

**B6 · El marco del editor de workspace, escrito dos veces** — `ExamWorkspaceTabs` (`:124-221`) y `SpecialtiesWorkspaceTabs` (`:106-186`): mismo `GlassSectionSurface` → `DesktopTabs(WORKSPACE)` → rama `fillsHeight`/apilada → barra de guardar. Propuesta: `WorkspaceEditorFrame(...)` en `core/ui`. **Riesgo medio** — el de Exámenes lleva `onSizeChanged` y `testTag`s que `ExamWorkspaceUiTest`/`ExamWorkspaceResponsiveTest` usan; hay que pasarlos como parámetros, no perderlos. Es la extracción que más cuidado pide de todo el lote.

**B7 · La fila de catálogo, escrita tres veces** — `ExamCatalogCard` (`ExamCatalogPane.kt:266`), `SpecialtyCatalogRow` (`SpecialtiesCatalogPane.kt:191`), variante en `QuestionBankRow` (`QuestionBankPane.kt:219`). Carcasa exacta compartida entre las dos primeras. **Diferencia deliberada, NO unificar sin parametrizar**: la barra lateral es `tokens.accent` en Exámenes y `accentInk` en Especialidades, justificada por una medición de contraste documentada (`SpecialtiesPresentation.kt:1039-1043`, coral sobre `surfaceSelected` da 2,10:1). Extraer con `barColor` como parámetro.

**B8 · Cinco hubs con la misma carcasa de 8 líneas** — `composeApp/.../HubScreens.kt`: `SystemHubScreen`, `UsersHubScreen`, `CatalogHubScreen`, `ModerationHubScreen`, `NewsHubScreen`. Mismo patrón `rememberSaveable` + `LaunchedEffect` + `DesktopTabs` + `when`. Propuesta: `HubScaffold(tabs, initialTabId, content)`. **`composeApp` no tiene ningún harness visual ni UI test** — sólo `NavigationInformationArchitectureTest`/`AppRootContentTargetTest` de enrutado puro. Seguro con revisión a ojo de las cinco pantallas.

---

## C · Recomposición innecesaria (ninguno aplicado)

**C1 · `DashboardV2`: el reloj recompone el panel entero cada 30 s** — `DashboardV2Screen.kt:116,131-136` (`RELATIVE_CLOCK_MILLIS = 30000`). Se lee arriba del todo y viaja por valor hasta `ActivityRow`/`SystemHealthChips` (dos `Text`, líneas `:420`,`:1101`). Cada tick invalida ~200 nodos. Propuesta: `now` como `State<Instant>`, leer `.value` sólo en las dos hojas. El de más valor del bloque C. Cubierto por `DashboardV2ContentUiTest`, `DashboardV2SystemStripUiTest`, `DashboardV2VisualHarness`.

**C2 · `UsersState.rolesById` es un getter calculado que se lee en composición** — `UsersState.kt:62` (`get() = availableRoles.associateBy { it.id }`), leído en `UsersListPane.kt:92-93` y pasado como parámetro a `UserRowCard`: cada composición construye un Map nuevo, invalidando todas las filas visibles. Propuesta: `remember(state.availableRoles) { state.rolesById }`. Ídem `adminRoleIds`.

**C3 · `filteredUsers` se ejecuta dos veces por composición** — `UsersListPane.kt:120` (recuento) y `:180` (lista). Propuesta: un solo `remember(...)` con las 5 claves.

**C4 · Lista sin `key` estable + orden recalculado en composición** — `MobileLayeredNewsEditor.kt:698,711`: `items(state.editorLayers.sortedByDescending { it.sortOrder })` sin `key`. Sin `key`, `LazyRow` reutiliza por índice; al reordenar capas cada tile hereda el estado interno del que ocupaba su posición. Propuesta: `key = { it.id }` + `remember`. Sin test hoy; comprobación manual del reordenamiento recomendada.

**C5 · Cálculos de capas dentro de la composición, en la ruta del arrastre** — `NewsLayersEditor.kt:110` y `NewsPreviewPane.kt:208,226` (`sortedBy`/`filter`/`none` en el camino caliente de `UpdateLayerFrame`, que se emite en cada frame de puntero). Propuesta: `remember(state.editorLayers) { ... }`.

**C6 · Lambda recreada que impide saltar a todos los paneles de Noticias** — `NewsWorkspaceScreen.kt:28-80`: `val dispatch: (NewsIntent) -> Unit = { ... }` se aloca en cada recomposición y va a 5 paneles. Riesgo bajo si se acierta con las claves del `remember`.

**C7 · Un `BoxWithConstraints` por fila de lista** — `UsersVisuals.kt:237-240`: `UserRowCard` abre uno solo para `narrow = maxWidth < 520.dp`, el mismo para todas las filas visibles de un `LazyColumn`. **Riesgo medio** (cambia firma), señalado, no empujado.

**C8 · Cálculo de catálogo sin `remember`** — `ExamCatalogPane.kt:96`: `loadedExams.mapNotNull{it.year}.distinct().sortedDescending()` en cada recomposición de la columna, incluida cada tecla del buscador.

---

## D · Medidas y literales (cero píxel, valor pequeño — ninguno aplicado)

- **D1** `QuestionBankPane.kt:324,335` — `size = 28.dp` donde `Sizing.iconButtonSmall` vale exactamente eso; el paginador hermano de `ExamCatalogPane.kt:425,438` ya usa el token.
- **D2** `MobileLayeredNewsEditor.kt:1054,1058,1063` — `Color(0xFFFF453A)` × 3 en el mismo fichero. **NO cambiar por `tokens.danger`** (movería píxeles, es paleta fija deliberada). Sí extraer a un `private val` con nombre.
- **D3** `MobileLayeredNewsEditor.kt:864,877` — listas literales reconstruidas en cada composición; subir a `private val` de fichero.
- **D4** `QuestionBankPane.kt:76,238,245` y `ExamCatalogPane.kt:534,540,546` — nombres totalmente cualificados en línea donde ya hay imports del mismo paquete. Cosmético.

---

## E · Observaciones que NO se propone tocar (moverían píxeles — decisión del dueño)

- **E1** Posible defecto de contraste en `ExamSaveBar.kt:150` (`tokens.success` sin medir) vs `SpecialtiesPresentation.kt:977` (`successInk`, medido y documentado en 2,98:1 sobre esa misma barra). Mismo componente, uno medido y otro no.
- **E2** Defecto real en `GlobalTagSearch.kt:144-150`: `SelectableSearchChip` codifica selección añadiendo `" activo"` a la etiqueta, pero `statusToneFor` (`StatusChip.kt:231`) compara la cadena entera en minúsculas — `"#slug activo"` nunca casa con `"activo"`, así que los dos estados resuelven a `UNKNOWN` y se pintan igual. Además anuncia «Estado: ...» a lectores de pantalla para algo que no es un estado.
- **E3** Noticias es el único workspace fuera del contenedor del DS — `NewsWorkspaceScreen.kt:97 NewsPanel` usa `GlassSurface` a mano en vez de `GlassSectionSurface` (pierde el rim en degradado y el sheen superior que sí tienen Exámenes/Especialidades/Dashboard). Converger es cambio visual.
- **E4** `NewsWorkspaceScreen.kt:106 NewsField` usa `OutlinedTextField` de Material mientras el resto usa `GlassTextField`/`BasicTextField` opaco. Misma clase de divergencia que E3.
- **E5** El propio DS se sale de su escala declarada: `SectionCard.kt:26-27` (22/18dp), `ScreenHeader.kt:32,52` (22/20, 24/22), `EmptyStateCard.kt:46` (28/30), `LoadingStateCard.kt:94` (22/24), `FilterBar.kt:29` (16/14), `GlassToggleRow.kt:43` (16/12) usan valores fuera de `Spacing`. Alinear movería píxeles en muchas pantallas a la vez.

---

## Orden sugerido por el revisor (valor ÷ riesgo), para cuando se retome

1. A1–A4 (aplicado ya vía informes posteriores).
2. B4, D1, C8, C2, C3, C5 — cero píxel, cada uno aislado. **Pendiente.**
3. B3, B1, B2 — las tres extracciones exactas a `core/ui`. **Pendiente, alto valor.**
4. C1 (dashboard `now`) — alto valor. **Pendiente.**
5. B7, C4, C6, D2, D3. **Pendiente.**
6. B5, B8, B6 (la que más cuidado pide, por los testTags). **Pendiente.**
7. C7 — señalado, no empujado.
8. E1–E5 — al dueño del producto.

## Cobertura

`FILES_IN_SCOPE = 276` (alcance original antes de la reconciliación). **Leídos íntegros en este lote: 67** (core/ui 40 de commonMain, exams 7, specialties 5, users 4, news 4, dashboard 3, imports 2, questions 2, composeApp 2 con más detalle). El resto del alcance original (209 ficheros) fue cubierto por los informes 04 y 05, que citan explícitamente estos 67 (más los 6 de composeApp) como "ya cubiertos, no releer".
