package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.SkipContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel propio de Importación V2 (banco/wizard, JVM/Desktop).
 *
 * Reutiliza casos de uso de `domain`. `features/exams` queda intacto: en
 * compacto/mobile se sigue pintando `ContentUploadScreen`.
 *
 * **El selector observa TODOS los exámenes (KI0-1, IMP-01).** Observaba
 * `observeExams()`, que excluye las ráfagas, y filtraba por el tipo elegido —que
 * por defecto es Ráfaga—: la lista salía vacía SIEMPRE, y 6 de los 7 exámenes
 * vivos son de ráfaga. Con `observeAllExams()` la ráfaga se puede importar.
 */
class ImportViewModel(
    dispatcherProvider: DispatcherProvider,
    observeExamsUseCase: ObserveAllExamsUseCase,
    private val refreshExamsUseCase: RefreshAllExamsUseCase,
    private val listBatchesUseCase: ListContentImportBatchesUseCase,
    private val getBatchUseCase: GetContentImportBatchUseCase,
    private val uploadPdfUseCase: UploadPdfToStagingUseCase,
    private val updateStagingUseCase: UpdateContentImportStagingUseCase,
    private val publishBatchUseCase: PublishContentImportBatchUseCase,
    private val skipStagingUseCase: SkipContentImportStagingUseCase,
) : MviViewModel<ImportState, ImportIntent>(
    initialState = ImportState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            observeExamsUseCase().collectLatest { exams ->
                updateState { it.copy(exams = if (exams.isEmpty()) UiAsync.Empty else UiAsync.Success(exams)) }
            }
        }
        scope.launch { refresh() }
    }

    override suspend fun handleIntent(intent: ImportIntent) {
        when (intent) {
            ImportIntent.Refresh -> refresh()
            is ImportIntent.SelectType -> updateState { it.copy(selectedType = intent.type, selectedExamId = null) }
            is ImportIntent.SelectExam -> updateState { it.copy(selectedExamId = intent.examId) }
            is ImportIntent.PdfPicked -> updateState {
                it.copy(pickedPdfPath = intent.pdf.path, pickedPdfName = intent.pdf.name, pickedPdfSizeBytes = intent.pdf.sizeBytes)
            }
            ImportIntent.UploadPdf -> uploadPdf()

            is ImportIntent.OpenBatch -> openBatch(intent.id)
            ImportIntent.CloseBatch -> updateState {
                it.copy(
                    activeBatch = UiAsync.Idle,
                    reviewFilter = ReviewFilter.ALL,
                    reviewSearch = "",
                    selectedStagingId = null,
                    editDraft = null,
                    actionState = UiAsync.Idle,
                )
            }

            is ImportIntent.SetReviewFilter -> updateState { it.copy(reviewFilter = intent.filter) }
            is ImportIntent.SetReviewSearch -> updateState { it.copy(reviewSearch = intent.query) }
            is ImportIntent.SelectStaging -> updateState {
                val item = (it.activeBatch as? UiAsync.Success)?.value?.staging?.firstOrNull { row -> row.id == intent.id }
                it.copy(selectedStagingId = intent.id, editDraft = item)
            }
            is ImportIntent.UpdateEditDraft -> updateState { it.copy(editDraft = intent.item) }
            ImportIntent.SaveEditDraft -> saveEditDraft()
            is ImportIntent.ApproveStaging -> updateStagingRow(intent.item.copy(parseStatus = "aprobada"), "Pregunta aprobada.")
            // Omitir NO exige que la fila esté completa: es otro caso de uso, sin la validación de guardar/aprobar.
            is ImportIntent.SkipStaging -> updateStagingRow(
                intent.item, "Pregunta marcada para saltar; no se borró.",
                action = { skipStagingUseCase(it) },
            )
            is ImportIntent.LinkStagingToExisting -> linkToExisting(intent.item)
            is ImportIntent.UnlinkStaging -> unlinkFromExisting(intent.item)

            ImportIntent.PublishBatch -> publishBatch(confirmExamPublished = false)
            ImportIntent.ConfirmPublishOnPublishedExam -> {
                updateState { it.copy(showPublishConfirm = false) }
                publishBatch(confirmExamPublished = true)
            }
            ImportIntent.DismissPublishConfirm -> updateState { it.copy(showPublishConfirm = false, actionState = UiAsync.Idle) }
            ImportIntent.ToggleHowItWorks -> updateState { it.copy(showHowItWorks = !it.showHowItWorks) }
            ImportIntent.DismissAction -> updateState { it.copy(actionState = UiAsync.Idle) }
        }
    }

    private suspend fun refresh() {
        refreshExamsUseCase()
        when (val result = listBatchesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(batches = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(batches = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)) }
        }
    }

    private suspend fun uploadPdf() {
        // Doble guarda igual que el ViewModel legacy: subir crea un lote nuevo,
        // así que un doble clic duplicaría el lote para el mismo PDF.
        if (currentState().actionState is UiAsync.Loading) return
        val state = currentState()
        val examId = state.selectedExamId
            ?: return updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen destino."))) }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = uploadPdfUseCase(examId, state.selectedType.apiValue, state.pickedPdfPath, state.pickedPdfName)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        activeBatch = UiAsync.Success(result.value),
                        pickedPdfPath = "",
                        pickedPdfName = "",
                        pickedPdfSizeBytes = 0,
                        actionState = UiAsync.Success("Lote creado en staging; revisa las preguntas antes de publicar."),
                    )
                }
                refresh()
            }
        }
    }

    private suspend fun openBatch(id: String) {
        updateState { it.copy(activeBatch = UiAsync.Loading, reviewFilter = ReviewFilter.ALL, reviewSearch = "", selectedStagingId = null, editDraft = null) }
        when (val result = getBatchUseCase(id)) {
            is AppResult.Failure -> updateState { it.copy(activeBatch = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(activeBatch = UiAsync.Success(result.value), actionState = UiAsync.Idle) }
        }
    }

    private suspend fun saveEditDraft() {
        val draft = currentState().editDraft ?: return
        updateStagingRow(draft, "Cambios guardados en el lote en revisión.", keepEditorOpen = true)
    }

    /**
     * Guarda una fila con el caso de uso que toque: [action] es guardar/aprobar por
     * defecto (exige la fila completa) u omitir (no exige nada).
     *
     * La respuesta del servidor a un PATCH no trae la coincidencia detectada, así que se
     * conserva la que la fila ya tenía: sin ella una duplicada vinculada perdería el
     * código de la pregunta («Vinculada a PREG-…») y al deshacer no habría a qué volver.
     */
    private suspend fun updateStagingRow(
        item: ContentImportStagingItem,
        message: String,
        keepEditorOpen: Boolean = false,
        action: suspend (ContentImportStagingItem) -> AppResult<ContentImportStagingItem> = { updateStagingUseCase(it) },
    ) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = action(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { state ->
                val saved = result.value.let { row -> if (row.duplicate == null) row.copy(duplicate = item.duplicate) else row }
                val active = (state.activeBatch as? UiAsync.Success)?.value
                    ?: return@updateState state.copy(actionState = UiAsync.Success(message))
                state.copy(
                    activeBatch = UiAsync.Success(active.copy(staging = active.staging.map { row -> if (row.id == saved.id) saved else row })),
                    editDraft = if (keepEditorOpen) saved else null,
                    selectedStagingId = if (keepEditorOpen) saved.id else null,
                    actionState = UiAsync.Success(message),
                )
            }
        }
    }

    /**
     * «Vincular a existente»: la fila se aprueba VINCULADA a la pregunta que el servidor
     * detectó como duplicada del banco. No se crea ninguna pregunta nueva.
     *
     * Se manda la fila TAL Y COMO ESTÁ GUARDADA en el lote, no el borrador: el servidor
     * sólo acepta el vínculo si el texto coincide con el de la pregunta existente, y un
     * borrador a medio editar rompería esa coincidencia.
     */
    private suspend fun linkToExisting(item: ContentImportStagingItem) {
        val stored = storedRow(item)
        val target = stored.duplicate?.takeIf { it.origin.equals("banco", ignoreCase = true) }
        val questionId = target?.questionId?.takeIf { it.isNotBlank() }
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Esta fila no tiene una pregunta existente con la que vincular.")))
            }
        val label = target.questionCode.takeIf { it.isNotBlank() } ?: "la pregunta existente"
        updateStagingRow(
            stored.copy(parseStatus = "aprobada", linkQuestionId = questionId),
            "Fila vinculada a $label; no se creará una pregunta nueva.",
            keepEditorOpen = true,
        )
    }

    /**
     * Deshace el vínculo: la fila queda `aprobada` SIN vínculo, es decir, otra vez una
     * duplicada sin resolver (Omitir / Vincular / editar). Una sola llamada: con el
     * servidor actual, una fila aprobada sin `vincularPreguntaId` borra el vínculo.
     */
    private suspend fun unlinkFromExisting(item: ContentImportStagingItem) {
        val stored = storedRow(item)
        updateStagingRow(
            stored.copy(parseStatus = "aprobada", linkQuestionId = null),
            "Vínculo deshecho: la fila vuelve a estar pendiente de resolver.",
            keepEditorOpen = true,
        )
    }

    private fun storedRow(item: ContentImportStagingItem): ContentImportStagingItem =
        (currentState().activeBatch as? UiAsync.Success)?.value?.staging?.firstOrNull { it.id == item.id } ?: item

    /**
     * Publicar es UNA llamada atómica en el servidor (KI3-1): o el lote queda
     * `importado` o no cambia nada.
     *
     * - Si el servidor pide confirmación porque el examen está publicado
     *   (`IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED`), se abre el diálogo y la
     *   segunda llamada viaja con `confirmExamPublished = true`.
     * - Ante un fallo de red o timeout NO se reintenta a ciegas: primero se RELEE el
     *   lote. Si ya está `importado`, el primer intento llegó y no hay nada que
     *   repetir; el servidor tampoco lo repetiría (`ejecutado = false`), pero la
     *   relectura evita incluso esa llamada y enseña el estado real.
     */
    private suspend fun publishBatch(confirmExamPublished: Boolean) {
        if (currentState().actionState is UiAsync.Loading) return
        val current = currentState()
        val activeBatch = (current.activeBatch as? UiAsync.Success)?.value
            ?: return updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Abre un lote antes de publicar."))) }
        val batchId = activeBatch.id
        if (!confirmExamPublished) {
            // El preflight es una previsión: si ya sabe que no se puede, no se manda a ciegas.
            // El servidor sigue siendo la autoridad y vuelve a comprobarlo todo.
            val gate = importPublishGate(current, activeBatch)
            if (!gate.enabled) {
                return updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(gate.reason))) }
            }
            // Examen ya publicado: se pide la confirmación (KI3-3) ANTES de llamar, no después.
            if (gate.requiresPublishedExamConfirmation) {
                return updateState { it.copy(showPublishConfirm = true, actionState = UiAsync.Idle) }
            }
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = publishBatchUseCase(batchId, confirmExamPublished)) {
            is AppResult.Failure -> when {
                result.error.isPublishedExamConfirmation() -> updateState { it.copy(showPublishConfirm = true, actionState = UiAsync.Idle) }
                result.error is AppError.Network -> rereadAfterUncertainPublish(batchId, result.error)
                else -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            }
            is AppResult.Success -> {
                val batch = result.value
                val message = if (importBatchIsPublished(batch.status)) {
                    val annulled = batch.staging.count { it.annulled && stagingTone(it) == ReviewFilter.IMPORTADA }
                    "Lote publicado: ${batch.importedCount} de ${batch.detectedCount} preguntas ya están en el examen" +
                        when (annulled) {
                            0 -> "."
                            1 -> " (1 anulada por el tribunal)."
                            else -> " ($annulled anuladas por el tribunal)."
                        }
                } else {
                    "El lote no se ha publicado (estado: ${batch.status}). Revisa las filas antes de reintentar."
                }
                updateState { it.copy(activeBatch = UiAsync.Success(batch), actionState = UiAsync.Success(message)) }
                refresh()
            }
        }
    }

    /** Tras un corte de red, la verdad está en el servidor: se relee antes de decidir si hubo publicación. */
    private suspend fun rereadAfterUncertainPublish(batchId: String, original: AppError) {
        when (val reread = getBatchUseCase(batchId)) {
            is AppResult.Success -> {
                val batch = reread.value
                if (importBatchIsPublished(batch.status)) {
                    updateState {
                        it.copy(
                            activeBatch = UiAsync.Success(batch),
                            actionState = UiAsync.Success("La conexión se cortó, pero el lote ya estaba publicado: ${batch.importedCount} preguntas en el examen."),
                        )
                    }
                    refresh()
                } else {
                    updateState { it.copy(activeBatch = UiAsync.Success(batch), actionState = UiAsync.Error(original)) }
                }
            }
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(original)) }
        }
    }
}

/** El código con el que el servidor pide confirmar que las preguntas se verán al instante (IM3). */
internal const val IMPORT_EXAM_PUBLISHED_CONFIRMATION_CODE = "IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED"

private fun AppError.isPublishedExamConfirmation(): Boolean =
    this is AppError.Validation && code.equals(IMPORT_EXAM_PUBLISHED_CONFIRMATION_CODE, ignoreCase = true)
