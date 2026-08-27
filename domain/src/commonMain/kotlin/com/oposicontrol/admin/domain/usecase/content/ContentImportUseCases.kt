package com.oposicontrol.admin.domain.usecase.content

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
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

class UpdateContentImportStagingUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> {
        if (item.title.isBlank()) return AppResult.Failure(AppError.Validation("La pregunta necesita título."))
        if (listOf(item.optionA, item.optionB, item.optionC, item.optionD).any { it.isBlank() }) {
            return AppResult.Failure(AppError.Validation("Las cuatro opciones deben tener contenido."))
        }
        if (item.correctAnswer.uppercase() !in setOf("A", "B", "C", "D")) {
            return AppResult.Failure(AppError.Validation("La respuesta correcta debe ser A, B, C o D."))
        }
        return repository.updateStaging(item)
    }
}

class PublishContentImportBatchUseCase(private val repository: ContentImportRepository) {
    suspend operator fun invoke(batchId: String): AppResult<ContentImportBatch> {
        if (batchId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona un lote."))
        return repository.publishImportBatch(batchId)
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
