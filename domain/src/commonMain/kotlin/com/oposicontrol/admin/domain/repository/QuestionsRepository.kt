package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLinkRequest
import com.oposicontrol.admin.domain.model.QuestionListItem

/**
 * Banco global de preguntas (pantalla Preguntas, JVM/Desktop). Deliberadamente
 * separado de [ContentRepository]: esta pantalla no comparte contrato con
 * Exámenes, así que ni [ContentRepository] ni [com.oposicontrol.admin.domain.model.ExamQuestion]
 * crecen por sus necesidades.
 */
interface QuestionsRepository {
    /** Todas las preguntas activas, sin filtrar por examen (`GET /preguntas`). */
    suspend fun listQuestions(): AppResult<List<QuestionListItem>>

    /** Preguntas de un examen concreto, con posición real (`GET /examenes/{id}/preguntas`). */
    suspend fun listQuestionsForExam(examId: String): AppResult<List<QuestionListItem>>

    /** Detalle completo — opciones, solución, recursos (`GET /preguntas/{id}`). */
    suspend fun getQuestionDetail(questionId: String): AppResult<QuestionDetail>

    /**
     * Alta de pregunta nueva (`POST /preguntas`). `examId`/`position` son
     * obligatorios porque el backend exige `especialidad_id`, y hoy la única
     * fuente real de ese dato es la especialidad del examen elegido — no hay
     * "Tema" independiente que la sustituya.
     */
    suspend fun createQuestion(draft: QuestionDraft, examId: String, position: Int): AppResult<QuestionDetail>

    /**
     * Actualiza una pregunta existente (`PATCH /preguntas/{id}`).
     *
     * [optionsChanged] importa: el backend solo intenta reemplazar
     * opciones/solución cuando llegan opciones en el cuerpo, y ese camino
     * depende de una RPC hoy ausente. Si el admin no tocó las opciones, no se
     * envían — así enunciado/tipo/fuente/explicación/estado se guardan de
     * verdad aunque crear/editar opciones siga roto.
     *
     * [linkToExam], si no es null, añade `examenId`/`posicion` al mismo
     * PATCH para asociar (o reposicionar) la pregunta en ese examen.
     */
    suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
        linkToExam: QuestionExamLinkRequest? = null,
    ): AppResult<QuestionDetail>

    /** Archiva la pregunta (`DELETE /preguntas/{id}` → `activa=false` en backend, no borrado físico). */
    suspend fun archiveQuestion(questionId: String): AppResult<Unit>
}
