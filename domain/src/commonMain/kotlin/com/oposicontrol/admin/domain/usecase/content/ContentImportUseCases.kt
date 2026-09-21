package com.oposicontrol.admin.domain.usecase.content

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.IMPORT_ANNULMENT_REASON_MAX_LENGTH
import com.oposicontrol.admin.domain.model.stagingCompletenessProblem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.canonicalExamType
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import kotlin.time.Clock

class ListContentImportBatchesUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke() = repository.listImportBatches()
}

class GetContentImportBatchUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(batchId: String): AppResult<ContentImportBatch> {
        if (batchId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona un lote."))
        return repository.getImportBatch(batchId)
    }
}

class UploadPdfToStagingUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(
        examId: String,
        examType: String,
        fileUri: String,
        fileName: String,
    ): AppResult<ContentImportBatch> {
        if (examId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona un examen."))
        if (fileUri.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona un PDF."))
        if (!fileName.lowercase().endsWith(".pdf")) {
            return AppResult.Failure(AppError.Validation("El archivo debe ser un PDF."))
        }
        return repository.uploadPdfToStaging(examId, canonicalExamType(examType), fileUri, fileName)
    }
}

/**
 * GUARDAR o APROBAR una fila de staging: aquí sí se exige que esté completa.
 *
 * Omitirla es otra operación ([SkipContentImportStagingUseCase]) y no pasa por esta
 * validación: una fila en error tiene que poder descartarse aunque le falte todo.
 */
class UpdateContentImportStagingUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> {
        stagingCompletenessProblem(item)?.let { return AppResult.Failure(AppError.Validation(it)) }
        val reason = item.annulmentReason?.trim()?.takeIf { item.annulled && it.isNotEmpty() }
        if (reason != null && reason.length > IMPORT_ANNULMENT_REASON_MAX_LENGTH) {
            return AppResult.Failure(
                AppError.Validation("El motivo de la anulación admite hasta $IMPORT_ANNULMENT_REASON_MAX_LENGTH caracteres."),
            )
        }
        // El motivo sólo viaja con la anulación puesta; sin ella se borra.
        return repository.updateStaging(item.copy(annulmentReason = reason))
    }
}

/**
 * OMITIR una fila: pasa a `omitida` SIN exigir título, opciones, respuesta ni
 * razonamiento. La fila queda como está en el lote y no se publica; no se borra nada.
 */
class SkipContentImportStagingUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> {
        if (item.id.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona una fila."))
        return repository.updateStaging(item.copy(parseStatus = "omitida", parseError = null))
    }
}

class PublishContentImportBatchUseCase(private val repository: ContentImportRepository) {
    /** [confirmExamPublished]: la respuesta al aviso de examen publicado (KI3-3); por defecto se pregunta. */
    suspend operator fun invoke(batchId: String, confirmExamPublished: Boolean = false): AppResult<ContentImportBatch> {
        if (batchId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona un lote."))
        return repository.publishImportBatch(batchId, confirmExamPublished)
    }
}

class CreateContentDraftUseCase(private val repository: ContentRepository) {
    suspend operator fun invoke(specialtyId: String, title: String, examType: String): AppResult<String> {
        if (specialtyId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona una especialidad."))
        if (title.isBlank()) return AppResult.Failure(AppError.Validation("Indica el nombre del examen borrador."))
        val now = Clock.System.now()
        return if (canonicalExamType(examType) == "rafaga") {
            when (val result = repository.upsertRafaga(
                Rafaga(
                    id = "",
                    specialtyId = specialtyId,
                    specialtyName = "",
                    title = title,
                    priceTickets = 0,
                    status = ExamStatus.DRAFT,
                    publishFrom = null,
                    publishedAt = null,
                    questionCount = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )) {
                is AppResult.Failure -> result
                is AppResult.Success -> AppResult.Success(result.value.id)
            }
        } else {
            when (val result = repository.upsertExam(
                Exam(
                    id = "",
                    specialtyId = specialtyId,
                    specialtyName = "",
                    title = title,
                    description = "",
                    typeExam = canonicalExamType(examType),
                    typeAccess = "gratuito",
                    priceTickets = 0,
                    status = ExamStatus.DRAFT,
                    publishFrom = null,
                    publishedAt = null,
                    sortOrder = 0,
                    questionCount = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )) {
                is AppResult.Failure -> result
                is AppResult.Success -> AppResult.Success(result.value.id)
            }
        }
    }
}
