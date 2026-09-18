package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.coroutines.flow.Flow

interface ContentRepository {
    fun observeSpecialties(): Flow<List<Specialty>>

    /**
     * **Todos** los exámenes, de cualquier tipo, con el tipo ya canonizado.
     *
     * Es la fuente única de la vertical de Exámenes. [observeExams] y
     * [observeRafagas] son dos cubos heredados del camino móvil que reparten por
     * tipo, y ese reparto ya perdió exámenes una vez: se filtraba por
     * `"inmersivo"` y por `"rafaga"`, así que un examen `razonado` (o el alias
     * legacy `guiado`) no entraba en ninguno y era invisible en el panel pese a
     * estar bien guardado. Cualquier pantalla nueva debe partir de aquí.
     *
     * El default delega en [observeExams] para no romper implementaciones
     * heredadas; sólo la implementación real de AdminV1 garantiza que no falte
     * nada.
     */
    fun observeAllExams(): Flow<List<Exam>> = observeExams()

    fun observeExams(): Flow<List<Exam>>
    fun observeRafagas(): Flow<List<Rafaga>>

    suspend fun refreshSpecialties(): AppResult<List<Specialty>>

    /** Recarga la fuente única de [observeAllExams]. */
    suspend fun refreshAllExams(): AppResult<List<Exam>> = refreshExams()

    suspend fun refreshExams(): AppResult<List<Exam>>
    suspend fun refreshRafagas(): AppResult<List<Rafaga>>
    suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>>
    suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>>
    suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit>

    suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty>
    suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit>

    /** Modos disponibles por especialidad. Ver [SpecialtyCapabilities]. */
    suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities>
    suspend fun saveSpecialtyCapabilities(capabilities: SpecialtyCapabilities): AppResult<SpecialtyCapabilities>

    suspend fun upsertExam(exam: Exam): AppResult<Exam>

    /**
     * Publica el examen. Endpoint dedicado (`POST .../publicar`), no un `upsert`
     * con el estado cambiado a mano: el backend audita esta acción
     * (`examen_publicado`) y fija `publicado_en` con SU reloj — un valor de
     * fecha que el cliente enviara aquí se ignoraría, así que esta función no
     * acepta ninguna fecha. No existe publicación programada a futuro en el
     * contrato actual.
     */
    suspend fun publishExam(examId: String): AppResult<Exam>

    /** Endpoint dedicado (`POST .../despublicar`), auditado como `examen_despublicado`. */
    suspend fun unpublishExam(examId: String): AppResult<Exam>

    /**
     * **BORRA el examen.** `DELETE .../examenes/{id}`, auditado como
     * `examen_eliminado`, y es irreversible.
     *
     * Hasta EXAM-CRUD este KDoc decía lo contrario —«el nombre histórico es
     * `deleteExam`, pero el endpoint real archiva»— y era cierto: el DELETE hacía
     * `activo=false`. Ya no. Quien quiera retirar un examen del catálogo sin
     * perderlo tiene [archiveExam].
     *
     * **Puede fallar con un rechazo de negocio, y ése es su caso normal.** Si del
     * examen cuelgan datos de usuarios —intentos, respuestas, compras…— el
     * servidor responde 409 con el código `EXAM_HAS_USER_DATA` y **no borra
     * nada**. Llega como `AppError.Validation` con ese código en `code`, y la
     * pantalla lo presenta como aviso de la acción.
     */
    suspend fun deleteExam(examId: String): AppResult<Unit>

    /**
     * Archiva el examen: `POST .../examenes/{id}/archivar`, auditado como
     * `examen_archivado`. Pone `activo=false` y `publicado_en=null`, no borra
     * nada, y es lo reversible de las dos acciones.
     *
     * Devuelve el examen guardado —igual que [publishExam] y [unpublishExam]— y
     * no `Unit`: archivar es una acción de CICLO DE VIDA, así que la pantalla
     * puede reflejar el estado nuevo sin recargar ni volver a sembrar el editor.
     *
     * **Reversible, con una vuelta que no es a borrador**: lo único que recupera
     * un examen archivado es [publishExam], que lo reactiva PUBLICADO. No hay
     * ruta de desarchivado a borrador —ver el KDoc de
     * `ExamPublicationPresentation`—, y el `default` de abajo no la inventa.
     *
     * El `default` sigue el precedente de [unlinkQuestionFromExam] y
     * [archiveQuestion]: las implementaciones legacy de este contrato
     * (`KtorContentRepositoryImpl`, `SupabaseContentRepositoryImpl`) hablan con
     * backends que no tienen esta ruta, y un método abstracto las obligaría a
     * fingir que sí. Fallar diciéndolo es más honesto que archivar por otro
     * camino.
     */
    suspend fun archiveExam(examId: String): AppResult<Exam> =
        AppResult.Failure(
            AppError.Validation("Esta implementación de ContentRepository no sabe archivar exámenes."),
        )

    suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga>

    /**
     * **BORRA la ráfaga.** Comparte endpoint con [deleteExam] —una ráfaga es una
     * fila de `examenes`— así que comparte su semántica nueva y su guarda: si
     * tiene datos de usuarios, 409 y no se borra nada.
     */
    suspend fun deleteRafaga(rafagaId: String): AppResult<Unit>

    suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion>
    suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>>
    /**
     * Quita la pregunta de ESTE examen. La pregunta sigue existiendo en el banco
     * y en los demás exámenes que la usen.
     *
     * Borra la fila de `preguntas_examen` y recompacta posiciones. Es lo que
     * debe ejecutar un botón «Quitar del examen».
     */
    suspend fun unlinkQuestionFromExam(examId: String, questionId: String): AppResult<Unit> =
        AppResult.Failure(
            AppError.Validation(
                "Esta implementación de ContentRepository no sabe desvincular preguntas. " +
                    "El default no archiva la pregunta a propósito: hacerlo la borraría de todos los exámenes.",
            ),
        )

    /**
     * Archiva la pregunta en el banco (`activa=false`) — desaparece de **todos**
     * los exámenes que la comparten.
     *
     * Sin `examId`: el efecto no está acotado a un examen y la firma no debe
     * insinuar que sí.
     */
    suspend fun archiveQuestion(questionId: String): AppResult<Unit> =
        AppResult.Failure(
            AppError.Validation("Esta implementación de ContentRepository no sabe archivar preguntas."),
        )

    suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>>

    suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion>
    suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit>
    suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>>

    suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource>
    suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit>

    suspend fun uploadExamCover(
        examId: String,
        localPathOrUri: String,
    ): AppResult<Exam>
    suspend fun clearExamCover(examId: String): AppResult<Exam>
}
