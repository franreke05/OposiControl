package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.canonicalExamType
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.content.CreateContentDraftUseCase
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.SkipContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.time.Clock

class ContentUploadViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    observeExamsUseCase: ObserveExamsUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
    private val refreshExamsUseCase: RefreshExamsUseCase,
    private val listBatchesUseCase: ListContentImportBatchesUseCase,
    private val getBatchUseCase: GetContentImportBatchUseCase,
    private val uploadPdfUseCase: UploadPdfToStagingUseCase,
    private val updateStagingUseCase: UpdateContentImportStagingUseCase,
    private val skipStagingUseCase: SkipContentImportStagingUseCase,
    private val publishBatchUseCase: PublishContentImportBatchUseCase,
    private val createDraftUseCase: CreateContentDraftUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ContentUploadState, ContentUploadIntent>(
    initialState = ContentUploadState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                updateState {
                    it.copy(
                        specialties = if (specialties.isEmpty()) UiAsync.Empty else UiAsync.Success(specialties),
                        selectedSpecialtyId = it.selectedSpecialtyId ?: specialties.firstOrNull()?.id,
                    )
                }
            }
        }
        scope.launch {
            observeExamsUseCase().collectLatest { exams ->
                updateState {
                    it.copy(
                        exams = if (exams.isEmpty()) UiAsync.Empty else UiAsync.Success(exams),
                        selectedExamId = it.selectedExamId ?: exams.firstOrNull()?.id,
                    )
                }
            }
        }
        dispatch(ContentUploadIntent.Refresh)
        analyticsTracker.trackScreen("content_upload")
    }

    override suspend fun handleIntent(intent: ContentUploadIntent) {
        when (intent) {
            ContentUploadIntent.Refresh -> refresh()
            is ContentUploadIntent.SelectSpecialty -> updateState { it.copy(selectedSpecialtyId = intent.id) }
            is ContentUploadIntent.SelectExam -> selectExam(intent.id)
            is ContentUploadIntent.SelectType -> updateState { it.copy(selectedExamType = canonicalExamType(intent.value)) }
            is ContentUploadIntent.UpdateDraftTitle -> updateState { it.copy(draftTitle = intent.value) }
            ContentUploadIntent.CreateDraft -> createDraft()
            is ContentUploadIntent.SelectPdf -> selectPdf(intent)
            ContentUploadIntent.UploadPdf -> uploadPdf()
            is ContentUploadIntent.OpenBatch -> openBatch(intent.id)
            ContentUploadIntent.CloseBatch -> updateState { it.copy(activeBatch = UiAsync.Idle, actionState = UiAsync.Idle) }
            is ContentUploadIntent.SaveStaging -> saveStaging(intent.item.copy(parseStatus = "aprobada"))
            is ContentUploadIntent.SkipStaging -> skipStaging(intent.item)
            ContentUploadIntent.PublishBatch -> publishBatch()
        }
    }

    private suspend fun refresh() {
        when (val result = refreshSpecialtiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(specialties = UiAsync.Error(result.error)) }
            is AppResult.Success -> Unit
        }
        when (val result = refreshExamsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(result.error)) }
            is AppResult.Success -> Unit
        }
        when (val result = listBatchesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(batches = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(batches = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)) }
        }
    }

    private fun selectExam(id: String) {
        val exam = (currentState().exams as? UiAsync.Success)?.value?.firstOrNull { it.id == id } ?: return
        updateState { it.copy(selectedExamId = exam.id, selectedSpecialtyId = exam.specialtyId, selectedExamType = canonicalExamType(exam.typeExam)) }
    }

    private suspend fun createDraft() {
        val state = currentState()
        val specialtyId = state.selectedSpecialtyId
            ?: return showError("Selecciona una especialidad.")
        if (state.draftTitle.isBlank()) return showError("Indica el nombre del examen borrador.")
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = createDraftUseCase(specialtyId, state.draftTitle.trim(), state.selectedExamType)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState { it.copy(selectedExamId = result.value, draftTitle = "", actionState = UiAsync.Success("Borrador preparado")) }
                refreshExamsUseCase()
            }
        }
    }

    private fun selectPdf(intent: ContentUploadIntent.SelectPdf) {
        if (intent.mimeType != null && intent.mimeType != "application/pdf" && !intent.name.lowercase().endsWith(".pdf")) {
            return showError("Selecciona un PDF digital con texto seleccionable.")
        }
        updateState { it.copy(selectedPdfUri = intent.uri, selectedPdfName = intent.name, actionState = UiAsync.Success("PDF seleccionado")) }
    }

    private suspend fun uploadPdf() {
        // Misma guarda que publishBatch: subir el PDF crea un lote en staging,
        // así que un doble toque crearía dos lotes para el mismo archivo.
        if (currentState().actionState is UiAsync.Loading) return
        val state = currentState()
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = uploadPdfUseCase(state.selectedExamId.orEmpty(), state.selectedExamType, state.selectedPdfUri, state.selectedPdfName)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        activeBatch = UiAsync.Success(result.value),
                        selectedPdfUri = "",
                        selectedPdfName = "",
                        actionState = UiAsync.Success("Lote creado en staging; revisa las preguntas antes de publicar."),
                    )
                }
                refreshBatches()
                analyticsTracker.trackAction("content_import_staged", mapOf("batch_id" to result.value.id))
            }
        }
    }

    private suspend fun openBatch(id: String) {
        updateState { it.copy(activeBatch = UiAsync.Loading) }
        when (val result = getBatchUseCase(id)) {
            is AppResult.Failure -> updateState { it.copy(activeBatch = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(activeBatch = UiAsync.Success(result.value), actionState = UiAsync.Idle) }
        }
    }

    private suspend fun saveStaging(item: ContentImportStagingItem) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = updateStagingUseCase(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> replaceStaging(result.value, "Pregunta aprobada para publicación.")
        }
    }

    private suspend fun skipStaging(item: ContentImportStagingItem) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        // Omitir NO exige completitud: es el mismo caso de uso que la pantalla de escritorio.
        when (val result = skipStagingUseCase(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> replaceStaging(result.value, "Pregunta marcada para saltar; no se borró.")
        }
    }

    private suspend fun publishBatch() {
        // Guarda antes de cualquier punto de suspensión: la publicación es UNA RPC atómica en el
        // servidor, pero un segundo toque con la primera en vuelo enviaría una petición
        // redundante (y su error confundiría al administrador). En un dispatcher confinado a
        // un solo hilo esto basta, sin Mutex adicional.
        if (currentState().actionState is UiAsync.Loading) return
        val batchId = (currentState().activeBatch as? UiAsync.Success)?.value?.id
            ?: return showError("Abre un lote antes de publicar.")
        updateState { it.copy(actionState = UiAsync.Loading) }
        // Sin diálogo de confirmación en esta pantalla compacta: `confirmExamPublished` va a false y, si
        // el examen ya está publicado (409), se explica que la confirmación se hace desde el escritorio.
        when (val result = publishBatchUseCase(batchId, confirmExamPublished = false)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error.asPublishError())) }
            is AppResult.Success -> {
                // La publicación es atómica: o el lote queda `importado` o falla entero (no hay «parcial»).
                val message = if (result.value.status.trim().equals("importado", ignoreCase = true)) {
                    "Lote importado (${result.value.importedCount}/${result.value.detectedCount}); el examen no se ha publicado automáticamente."
                } else {
                    "El lote no ha quedado importado (estado: ${result.value.status.ifBlank { "desconocido" }}). Revisa las filas antes de reintentar."
                }
                updateState { it.copy(activeBatch = UiAsync.Success(result.value), actionState = UiAsync.Success(message)) }
                refreshBatches()
                analyticsTracker.trackAction("content_import_publish", mapOf("batch_id" to batchId, "status" to result.value.status))
            }
        }
    }

    private fun replaceStaging(item: ContentImportStagingItem, message: String) {
        updateState { state ->
            val active = (state.activeBatch as? UiAsync.Success)?.value ?: return@updateState state.copy(actionState = UiAsync.Success(message))
            state.copy(
                activeBatch = UiAsync.Success(active.copy(staging = active.staging.map { if (it.id == item.id) item else it })),
                actionState = UiAsync.Success(message),
            )
        }
    }

    private suspend fun refreshBatches() {
        when (val result = listBatchesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(batches = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(batches = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)) }
        }
    }

    private fun showError(message: String) {
        updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(message))) }
    }
}
