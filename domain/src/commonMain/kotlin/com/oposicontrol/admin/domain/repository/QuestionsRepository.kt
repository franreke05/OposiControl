package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionPage

/**
 * Qué preguntas quiere el banco: activas, inactivas o las dos.
 *
 * Existe porque el servidor filtraba `activa = true` incondicionalmente y el
 * filtro «Inactivas» de la pantalla no podía devolver nada nunca — filtraba un
 * conjunto del que las inactivas ya se habían ido. Y como archivar sí funciona,
 * una pregunta desactivada desaparecía del banco sin forma de volver a
 * encontrarla: reversible en la base, irreversible en la interfaz.
 *
 * [wireValue] es el valor exacto que entiende `GET /preguntas?activa=`.
 */
enum class QuestionActiveFilter(val wireValue: String) {
    ACTIVE("true"),
    INACTIVE("false"),
    ALL("todas"),
}

/**
 * Banco global de preguntas (pantalla Preguntas, JVM/Desktop). Deliberadamente
 * separado de [ContentRepository]: esta pantalla no comparte contrato con
 * Exámenes, así que ni [ContentRepository] ni [com.oposicontrol.admin.domain.model.ExamQuestion]
 * crecen por sus necesidades.
 */
interface QuestionsRepository {
    /**
     * Una página del banco (`GET /preguntas?q=&tipo=&especialidadId=&activa=&examenId=&orden=&limit=&offset=`),
     * con el total exacto de la consulta (KQ0-4).
     *
     * Sustituye a `listQuestions()`, `listQuestions(filter)` y `listQuestionsForExam`.
     * Aquéllas descargaban el banco entero —o la lista completa de un examen— y la
     * pantalla filtraba y paginaba lo cargado; y la variante por estado tenía una
     * implementación por defecto que respondía un fallo a quien no la sobrescribía.
     * Ahora es un único método abstracto: ningún implementador puede olvidarse de un
     * filtro, porque todos viajan en [QuestionListQuery].
     */
    suspend fun listQuestions(query: QuestionListQuery): AppResult<QuestionPage>

    /** Detalle completo — opciones, solución, recursos (`GET /preguntas/{id}`). */
    suspend fun getQuestionDetail(questionId: String): AppResult<QuestionDetail>

    /**
     * Alta de pregunta nueva (`POST /preguntas`, KQ2-4).
     *
     * [questionId] lo genera la PANTALLA una sola vez por alta y lo repite en cada
     * reintento: es la clave de idempotencia de `admin_create_question`. Un reintento
     * tras un timeout con el mismo id no crea otra pregunta; con un id nuevo, sí.
     *
     * [specialtyId] se elige directamente: la especialidad ya no se deriva del examen
     * inicial, que ahora es opcional ([examId]) y se vincula en la misma transacción.
     */
    suspend fun createQuestion(
        questionId: String,
        specialtyId: String,
        draft: QuestionDraft,
        examId: String? = null,
    ): AppResult<QuestionDetail>

    /**
     * Actualiza una pregunta existente (`PATCH /preguntas/{id}`, KQ2-2): sólo las
     * claves presentes viajan, y el servidor escribe en una transacción.
     *
     * [optionsChanged] importa: si el admin no tocó las opciones, no se envían, y el
     * servidor conserva opciones y explicación tal cual. El PATCH ya NO vincula (D-03):
     * para eso están [linkQuestionToExam] y [unlinkQuestionFromExam].
     */
    suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
    ): AppResult<QuestionDetail>

    /** Archiva la pregunta (`DELETE /preguntas/{id}` → `activa=false` en backend, no borrado físico). */
    suspend fun archiveQuestion(questionId: String): AppResult<Unit>

    /** Los exámenes REALES en los que está la pregunta (`GET /preguntas/{id}/examenes`, K3-4). */
    suspend fun listQuestionExams(questionId: String): AppResult<List<QuestionExamLink>>

    /**
     * Vincula la pregunta a un examen (`POST /preguntas/{id}/examenes`, KQ3-1) y
     * devuelve la lista completa ya releída. [position] `null` = al final.
     */
    suspend fun linkQuestionToExam(questionId: String, examId: String, position: Int?): AppResult<List<QuestionExamLink>>

    /** Quita la pregunta de un examen sin tocarla en el banco (`DELETE /preguntas/{id}/examenes/{examId}`). */
    suspend fun unlinkQuestionFromExam(questionId: String, examId: String): AppResult<List<QuestionExamLink>>
}
