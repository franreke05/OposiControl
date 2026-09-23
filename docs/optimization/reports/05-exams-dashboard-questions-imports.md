# Revisión — `features/exams` (64) + `features/dashboard` (19) + `features/questions` (21) + `features/imports` (22) = 126/126 (declarado ~128)

Ley: **BEHAVIOR PRESERVATION > FEWER LINES**, CERO PÍXEL. Regla dura del proyecto: medidas exactas en dp, nunca `weight` ni fracciones.

---

## TIER 1 — máximo valor, riesgo nulo

### T1.1 · `QuestionsResourcesScreen` + `EditorField` muertos (≈275 líneas)

**APLICADO.** `ExamsScreen.kt:1241-1506` (`QuestionsResourcesScreen`, 267 líneas) y `:1508` (`EditorField`, 26 líneas) borrados. `AppRoot.kt:997-1000` ya documentaba que esa ruta no se usa. Sin ningún llamador en todo el repo.

### T1.2 · `CoverEditor` muerto

**APLICADO.** `ExamsScreen.kt:936-1021` borrado. Su sustituto vivo es `examCoverPresentation` (`ExamWorkspacePresentation.kt:812-1059`).

### T1.3 · Dos símbolos muertos en `MobileContentScreen.kt`

**APLICADO.** `Int.toTicketLabel()` (:1657) y `NewsActiveToggle` (:3087) borrados. Cero referencias en todo el repo.

### T1.4 · Tres fixtures muertos + 5 imports en `ExamBasicInfoVisualHarness.kt`

**APLICADO.** `portadaDePrueba()`, `conPortadaGuardada()`, `examenNuevoConPortadaElegida()` borrados (el fichero documentaba en su propio KDoc que los escenarios de portada se retiraron 2026-09-23). Imports huérfanos `java.awt.Font/GradientPaint/RenderingHints/image.BufferedImage/Color as AwtColor` eliminados.

### T1.5 · Doble normalización del filtro de estado en el bucle de filtrado

**APLICADO.** `ExamsViewModel.kt:683,688`: `statusFilter` ya venía normalizado en la línea 683; el `.trim().lowercase()` repetido en la 688 (ejecutado una vez por examen del catálogo) se eliminó, dejando `exam.status.name.lowercase() == statusFilter`.

### T1.6 · `examResourceGroups` se calculaba dos veces en la misma composición

**Ya no aplica** — `ExamResourcesSection.kt`, donde vivía el hallazgo, se borró entero en T1.1/A1 del informe 03.

### T1.7 · `val` sin usar que además señala un hueco de estado

**APLICADO en su totalidad**: `QuestionRelationsSection.kt:43` (`val tokens = GlassTheme.tokens`, nunca leído) borrado. `ContentUploadScreen.kt:168,212` (`val selected = ...`, calculado y nunca leído en ninguno de los dos sitios, verificado leyendo los bloques `Button` completos) borrados.
**NO aplicado a propósito — es R.10 abajo**: esas dos filas de chips calculan si el chip está seleccionado y NO lo pintan; hoy no hay estado visual de selección. Arreglar el hueco visual es mejora de producto, no limpieza; sólo se quitó el cálculo muerto.

---

## TIER 2 — valor alto, riesgo bajo-medio (ninguno aplicado — todos piden test/harness nuevo o revisión sitio a sitio)

- **T2.1** Preflight de publicación recalculado en cada composición — `ImportBatchSummary.kt:64-65` (`batch.metrics()` + `importPublishGate(state, batch)` sin `remember`). Riesgo bajo si la clave es `state`+`batch` completos (no sólo `batch.id`).
- **T2.2** Tres derivaciones sin `remember` en `ImportReviewPane.kt:68,111,396`.
- **T2.3** Listas estáticas reconstruidas por composición en `DashboardScreen.kt:337,657,775` + `AttemptsBarChart` (`:440`) sin `remember`.
- **T2.4** `sortedBy`/`filter` en composición en 8 puntos de `MobileContentScreen.kt` (líneas 1333-1340, 1556, 1617, 1983, 2089, 2149, 2241, 2493). **Riesgo medio, no mecánico** — sin ningún test ni harness visual que cubra este fichero (el más grande del alcance, 3185 líneas). El propio revisor recomienda test/harness ANTES de tocar.
- **T2.5** `items(...)` sin `key` en `MobileLayeredNewsEditor.kt:698` — cambia comportamiento durante un reordenamiento (para bien), no es cero-píxel garantizado en sentido estricto. Sin test.
- **T2.6** Rama inalcanzable atada a `ExamsState.contentPendingMigrationMessage` (`MobileContentScreen.kt:1551-1554`) — el campo nunca lo escribe nadie fuera del default `""`. Borrar la rama es bajo riesgo; borrar el campo toca `copy()` y fixtures (medio riesgo).

## TIER 3 — valor menor (ninguno aplicado)

- T3.1 `EXAMENES` público-mal-alcance en dos `ImportFixtures.kt`/`QuestionsFixtures.kt` (debería ser `private`).
- T3.2 `MobileDashboardScreen.kt:16` parámetro `environmentLabel` sin usar.
- T3.3 `ExamsState.kt:32` (`rafagas`) y `:105` (`importSummary`) sin lector ni escritor útil — mismo caveat que T2.6.
- T3.4 `MobileLayeredNewsEditor.kt:863,876` listas literales reconstruidas por composición.

---

## REGISTRAR, NO TOCAR

- **R.1** `ExamsViewModel.seeded()` (`:394`) convierte `UiAsync.Error` en `UiAsync.Empty` — su gemelo de Preguntas (`seededCatalog`) NO lo hace. Es exactamente el defecto `null != 0` que el proyecto ya cerró en otros sitios. Cambiarlo es decisión de producto.
- **R.2** `linkedQuestionCount(questions, exam)` (`ExamWorkspacePresentation.kt:356`) NO lee `exam` en el cuerpo — parece parámetro muerto y NO lo es: `ExamWorkspacePresentationTest.kt:206,221,222,248` lo usan a propósito para afirmar que la función IGNORA el contador del examen (regla K2-2). **No quitar el parámetro.**
- **R.3** `DropdownField`/`LabeledField` privados de `ExamsScreen.kt:1023,1188` frente a los canónicos de `core/ui/.../FormField.kt` — comparados valor a valor, NO son iguales (`defaultMinSize` y color de borde distintos). Unificar movería píxel.
- **R.4** Etiquetadores duplicados entre Preguntas y Exámenes (`examStatusLabel`, `questionTypeLabel`, `examOptionLabel` ≡ `importExamLabel`). Unificar exige módulo común nuevo o mover funciones entre features — cambio de arquitectura.
- **R.5** `questionResourceIcon` (`QuestionResourcesSection.kt:392`) mapea `DOCUMENT → AppIcon.REPORTS`; el equivalente de Exámenes usa `AppIcon.RESOURCES` para el mismo tipo. Alinearlo cambia un píxel — decisión de diseño.
- **R.6** `Box(Modifier.size(0.dp))` en `QuestionAnswersSection.kt:166` parece borrable y NO lo es: fuerza un hueco de separación en el extremo de un `Row` con `spacedBy`. Borrarlo desplaza la fila.
- **R.7** Intents con manejador y SIN ningún emisor en toda la app: `SelectRafaga`, `DeleteSelectedRafagaQuestion`, `DeleteSelectedSpecialty`, `RefreshResourceAudit`, `ArchiveSelectedExam`, `ClearExamCover`, `DeleteSelectedExam` (`ExamsIntent.kt`). No es código muerto: es funcionalidad implementada sin control en la UI. Decisión de producto.
- **R.8** `loadedCatalog` duplicado literalmente entre `ExamsViewModel.kt:408` y `QuestionsViewModel.kt:682`. Extraerlo a `core` es correcto pero cruza módulos.
- **R.9** `ImportReviewPane.kt:172`: `rows.forEach` pinta todo el lote de una vez. Cambiar a `LazyColumn` movería el comportamiento de desplazamiento — prohibido por la ley de cero píxel.
- **R.10** Ver T1.7 — chips de `ContentUploadScreen` sin estado visual de selección. El cálculo muerto se quitó; el hueco visual sigue documentado aquí para decisión de producto.
- **R.11** Cuatro `BoxWithConstraints` en `DashboardScreen.kt`. Sustituirlos por dp exactos cambiaría el reparto — cambio de diseño.
- **R.12** Funciones vivas sólo por los tests: `questionCapabilities`/`questionCapability`, `importCapabilities`/`importCapability`, `firstAllowedType`, `QuestionImportParser.parse`, `ImportParseReport.validCount`. Pueden ser contrato anticipado; decidir, no barrer.
- **R.13** `DropTargetState.Linked` (`MobileContentScreen.kt:1939`) — único punto de referencia, ninguna transición lo produce; probablemente inalcanzable pero no demostrado con certeza.
- **R.14** `HARNESS_SIDEBAR` transcrito seis veces entre los harness visuales de cada feature — deliberado y justificado por escrito (el catálogo real vive en `composeApp`, los módulos de feature no pueden depender de él).

---

## Cobertura

`FILES_IN_SCOPE ≈ 128` (64 exams + 19 dashboard + 21 questions + 22 imports = 126 en el recuento exacto del repo). `FILES_READ` = todos, incluyendo los 14 ya cubiertos íntegros por el informe 03 (`ExamWorkspaceControls.kt`, `ExamSaveBar.kt`, `ExamCatalogPane.kt`, `ExamWorkspaceScreen.kt`, `ExamWorkspaceTabs.kt`, `ExamSummaryPanel.kt`, `ExamBasicInfoSection.kt`, `DashboardV2Screen.kt`, `DashboardV2State.kt`, `DashboardV2ViewModel.kt`, `ImportScreenParts.kt`, `ImportScreen.kt`, `QuestionsScreenParts.kt`, `QuestionBankPane.kt`). Ninguno sin leer.
