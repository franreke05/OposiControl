package com.oposicontrol.admin.domain.usecase.content

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.ContentRepository

class ObserveSpecialtiesUseCase(
    private val repository: ContentRepository,
) {
    operator fun invoke() = repository.observeSpecialties()
}

class RefreshSpecialtiesUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke() = repository.refreshSpecialties()
}

class UpsertSpecialtyUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(specialty: Specialty): AppResult<Specialty> {
        if (specialty.name.isBlank()) {
            return AppResult.Failure(AppError.Validation("La especialidad necesita nombre."))
        }
        return repository.upsertSpecialty(specialty)
    }
}

class DeleteSpecialtyUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(specialtyId: String) = repository.deleteSpecialty(specialtyId)
}

class ObserveExamsUseCase(
    private val repository: ContentRepository,
) {
    operator fun invoke() = repository.observeExams()
}

class ObserveRafagasUseCase(
    private val repository: ContentRepository,
) {
    operator fun invoke() = repository.observeRafagas()
}

class RefreshExamsUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke() = repository.refreshExams()
}

class RefreshRafagasUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke() = repository.refreshRafagas()
}

class GetExamQuestionsUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String) = repository.getExamQuestions(examId)
}

class GetRafagaQuestionsUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(rafagaId: String) = repository.getRafagaQuestions(rafagaId)
}

class GetExamResourceAuditUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String) = repository.getExamResourceAudit(examId)
}

class UpsertExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(exam: Exam): AppResult<Exam> {
        if (exam.specialtyId.isBlank()) {
            return AppResult.Failure(AppError.Validation("El examen necesita una especialidad."))
        }
        if (exam.title.isBlank()) {
            return AppResult.Failure(AppError.Validation("El examen necesita titulo."))
        }
        return repository.upsertExam(exam)
    }
}

class DeleteExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String) = repository.deleteExam(examId)
}

class UpsertRafagaUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(rafaga: Rafaga): AppResult<Rafaga> {
        if (rafaga.specialtyId.isBlank()) {
            return AppResult.Failure(AppError.Validation("La rafaga necesita una especialidad."))
        }
        if (rafaga.title.isBlank()) {
            return AppResult.Failure(AppError.Validation("La rafaga necesita titulo."))
        }
        if (rafaga.priceTickets < 0) {
            return AppResult.Failure(AppError.Validation("El precio en tickets no puede ser negativo."))
        }
        return repository.upsertRafaga(rafaga)
    }
}

class DeleteRafagaUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(rafagaId: String) = repository.deleteRafaga(rafagaId)
}

class UpsertQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(question: ExamQuestion): AppResult<ExamQuestion> {
        if (question.title.isBlank()) {
            return AppResult.Failure(AppError.Validation("La pregunta necesita titulo."))
        }
        if (question.statement.isBlank()) {
            return AppResult.Failure(AppError.Validation("La pregunta necesita enunciado."))
        }
        if (question.options.size != 4) {
            return AppResult.Failure(AppError.Validation("Cada pregunta debe tener exactamente 4 opciones."))
        }
        if (question.options.any { it.content.isBlank() }) {
            return AppResult.Failure(AppError.Validation("Las 4 opciones deben tener contenido."))
        }
        if (question.options.count { it.isCorrect } != 1) {
            return AppResult.Failure(AppError.Validation("Debes marcar una unica opcion correcta."))
        }
        val invalidResource = question.resources.firstOrNull { resource ->
            when (resource.type) {
                QuestionResourceType.TEXT -> resource.textBody.isNullOrBlank()
                else -> resource.publicUrl.isNullOrBlank()
            }
        }
        if (invalidResource != null) {
            return AppResult.Failure(
                AppError.Validation("Todos los recursos deben estar completos antes de guardar la pregunta."),
            )
        }
        return repository.upsertQuestion(question)
    }
}

class ImportQuestionsUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona un examen antes de importar preguntas."))
        }
        if (questions.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No hay preguntas validas para importar."))
        }
        if (questions.size > 50) {
            return AppResult.Failure(AppError.Validation("No puedes importar mas de 50 preguntas de una vez."))
        }
        questions.forEachIndexed { index, question ->
            val line = index + 1
            if (question.statement.isBlank()) {
                return AppResult.Failure(AppError.Validation("La pregunta $line necesita enunciado."))
            }
            if (question.options.size != 4) {
                return AppResult.Failure(AppError.Validation("La pregunta $line debe tener exactamente 4 opciones."))
            }
            if (question.options.any { it.content.isBlank() }) {
                return AppResult.Failure(AppError.Validation("La pregunta $line tiene opciones vacias."))
            }
            if (question.options.count { it.isCorrect } != 1) {
                return AppResult.Failure(AppError.Validation("La pregunta $line debe tener una unica opcion correcta."))
            }
        }
        return repository.importQuestions(examId, questions)
    }
}

class DeleteQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String, questionId: String) = repository.deleteQuestion(examId, questionId)
}

class MoveQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = repository.moveQuestion(examId, questionId, direction)
}

class UpsertRafagaQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(question: RafagaQuestion): AppResult<RafagaQuestion> {
        if (question.rafagaId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una rafaga antes de guardar preguntas."))
        }
        if (question.statement.isBlank()) {
            return AppResult.Failure(AppError.Validation("La pregunta de rafaga necesita enunciado."))
        }
        return repository.upsertRafagaQuestion(question)
    }
}

class DeleteRafagaQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(rafagaId: String, questionId: String) = repository.deleteRafagaQuestion(rafagaId, questionId)
}

class MoveRafagaQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = repository.moveRafagaQuestion(rafagaId, questionId, direction)
}

class UploadQuestionResourceUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = repository.uploadResource(
        questionId = questionId,
        resourceId = resourceId,
        type = type,
        localPathOrUrl = localPathOrUrl,
        title = title,
    )
}

class DeleteQuestionResourceUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(questionId: String, resourceId: String): AppResult<Unit> {
        if (questionId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una pregunta antes de borrar recursos."))
        }
        if (resourceId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona un recurso para borrarlo."))
        }
        return repository.deleteResource(questionId, resourceId)
    }
}

class UploadExamCoverUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(
        examId: String,
        localPathOrUri: String,
    ): AppResult<Exam> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Guarda o selecciona un examen antes de subir portada."))
        }
        if (localPathOrUri.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una imagen de portada."))
        }
        return repository.uploadExamCover(examId, localPathOrUri)
    }
}

class ClearExamCoverUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String): AppResult<Exam> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona un examen antes de limpiar portada."))
        }
        return repository.clearExamCover(examId)
    }
}
