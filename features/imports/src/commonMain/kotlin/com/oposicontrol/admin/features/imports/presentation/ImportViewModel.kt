package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel propio de Importación V2 (banco/wizard, JVM/Desktop).
 *
 * Reutiliza únicamente casos de uso YA existentes en `domain` (los mismos que
 * usa el `ContentUploadViewModel` legacy de `features/exams`) — no se creó
 * ningún endpoint, repositorio ni migración nuevos. `features/exams` queda
 * intacto: en compacto/mobile se sigue pintando `ContentUploadScreen`.
 */
class ImportViewModel(
    dispatcherProvider: DispatcherProvider,
    observeExamsUseCase: ObserveExamsUseCase,
    private val refreshExamsUseCase: RefreshExamsUseCase,
    private val listBatchesUseCase: ListContentImportBatchesUseCase,
    private val getBatchUseCase: GetContentImportBatchUseCase,
    private val uploadPdfUseCase: UploadPdfToStagingUseCase,
    private val updateStagingUseCase: UpdateContentImportStagingUseCase,
    private val publishBatchUseCase: PublishContentImportBatchUseCase,
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
            is ImportIntent.SkipStaging -> updateStagingRow(intent.item.copy(parseStatus = "omitida"), "Pregunta marcada para saltar; no se borró.")

            ImportIntent.PublishBatch -> publishBatch()
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

    private suspend fun updateStagingRow(item: ContentImportStagingItem, message: String, keepEditorOpen: Boolean = false) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = updateStagingUseCase(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { state ->
                val active = (state.activeBatch as? UiAsync.Success)?.value
                    ?: return@updateState state.copy(actionState = UiAsync.Success(message))
                state.copy(
                    activeBatch = UiAsync.Success(active.copy(staging = active.staging.map { row -> if (row.id == result.value.id) result.value else row })),
                    editDraft = if (keepEditorOpen) result.value else null,
                    selectedStagingId = if (keepEditorOpen) result.value.id else null,
                    actionState = UiAsync.Success(message),
                )
            }
        }
    }

    private suspend fun publishBatch() {
        // Misma guarda que el flujo legacy: la publicación todavía no es
        // atómica (content_import_atomicity_candidate_v1 sigue sin aplicar),
        // así que dos publicaciones solapadas podrían duplicar preguntas.
        if (currentState().actionState is UiAsync.Loading) return
        val batchId = (currentState().activeBatch as? UiAsync.Success)?.value?.id
            ?: return updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Abre un lote antes de publicar."))) }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = publishBatchUseCase(batchId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val message = if (result.value.status == "parcial") {
                    "Importación parcial: ${result.value.importedCount}/${result.value.detectedCount}. Revisa los errores antes de reintentar."
                } else {
                    "Contenido aprobado persistido; el examen no se ha publicado automáticamente."
                }
                updateState { it.copy(activeBatch = UiAsync.Success(result.value), actionState = UiAsync.Success(message)) }
                refresh()
            }
        }
    }
}
