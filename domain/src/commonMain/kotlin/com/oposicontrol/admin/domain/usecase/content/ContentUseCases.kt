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
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
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

class GetSpecialtyCapabilitiesUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(specialtyId: String): AppResult<SpecialtyCapabilities> {
        if (specialtyId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una especialidad."))
        }
        return repository.getSpecialtyCapabilities(specialtyId)
    }
}

class SaveSpecialtyCapabilitiesUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(capabilities: SpecialtyCapabilities): AppResult<SpecialtyCapabilities> {
        if (capabilities.specialtyId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una especialidad."))
        }
        // Misma regla que aplica el backend, comprobada antes de la llamada para
        // dar el mensaje en el formulario en vez de como error de red. El backend
        // la sigue validando: esto es conveniencia de UI, no la autoridad.
        if (capabilities.hasNoExamModes && capabilities.limitationMessage.isNullOrBlank()) {
            return AppResult.Failure(
                AppError.Validation(
                    "Una especialidad sin ningún modo de examen necesita un mensaje que explique " +
                        "al usuario por qué no hay contenido disponible.",
                ),
            )
        }
        return repository.saveSpecialtyCapabilities(capabilities)
    }
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

/**
 * Fuente única de la vertical de Exámenes: todos los tipos, sin reparto previo.
 *
 * [ObserveExamsUseCase] y [ObserveRafagasUseCase] siguen sirviendo al camino
 * móvil, pero una lista construida sobre ellos vuelve a arriesgarse a perder un
 * tipo. Cualquier pantalla nueva usa éste.
 */
class ObserveAllExamsUseCase(
    private val repository: ContentRepository,
) {
    operator fun invoke() = repository.observeAllExams()
}

class RefreshAllExamsUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke() = repository.refreshAllExams()
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
            return AppResult.Failure(AppError.Validation("El examen necesita título."))
        }
        return repository.upsertExam(exam)
    }
}

/**
 * **Borra el examen para siempre.** Ver `ContentRepository.deleteExam`: el
 * servidor lo bloquea con un 409 si tiene datos de usuarios.
 *
 * Valida el identificador por la misma razón que [PublishExamUseCase]: un examen
 * sin guardar no tiene id, y un `DELETE .../examenes/` sin id es una petición a
 * la colección.
 */
class DeleteExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String): AppResult<Unit> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Este examen no está guardado: no hay nada que borrar."))
        }
        return repository.deleteExam(examId)
    }
}

/** Archiva el examen (reversible). Ver `ContentRepository.archiveExam`. */
class ArchiveExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String): AppResult<Exam> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Guarda el examen antes de archivarlo."))
        }
        return repository.archiveExam(examId)
    }
}

class PublishExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String): AppResult<Exam> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Guarda el examen antes de publicarlo."))
        }
        return repository.publishExam(examId)
    }
}

class UnpublishExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String): AppResult<Exam> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Guarda el examen antes de despublicarlo."))
        }
        return repository.unpublishExam(examId)
    }
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

/**
 * Quita la pregunta de este examen. La pregunta sigue en el banco.
 *
 * Es lo que debe ejecutar «Quitar del examen».
 */
class UnlinkQuestionFromExamUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(examId: String, questionId: String): AppResult<Unit> {
        if (examId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona un examen."))
        }
        if (questionId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una pregunta."))
        }
        return repository.unlinkQuestionFromExam(examId, questionId)
    }
}

/**
 * Archiva la pregunta en el banco: desaparece de **todos** los exámenes.
 *
 * Quien lo invoque desde la UI debe advertirlo antes de ejecutarlo.
 */
class ArchiveQuestionUseCase(
    private val repository: ContentRepository,
) {
    suspend operator fun invoke(questionId: String): AppResult<Unit> {
        if (questionId.isBlank()) {
            return AppResult.Failure(AppError.Validation("Selecciona una pregunta."))
        }
        return repository.archiveQuestion(questionId)
    }
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
