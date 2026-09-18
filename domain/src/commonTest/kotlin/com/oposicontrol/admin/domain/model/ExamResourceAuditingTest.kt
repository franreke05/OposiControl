package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La auditoría de recursos como función pura (K0-5): la calculan el ViewModel de
 * Exámenes —con las preguntas ya cargadas, sin otra petición— y el repositorio
 * —para el camino móvil—, con UNA definición de «recurso pendiente».
 */
class ExamResourceAuditingTest {

    private val now = Instant.parse("2026-09-17T09:00:00Z")

    private fun resource(id: String, type: QuestionResourceType, url: String?) = QuestionResource(
        id = id,
        questionId = "q",
        type = type,
        title = id,
        textBody = if (type == QuestionResourceType.TEXT) "Texto" else null,
        storagePath = null,
        publicUrl = url,
        mimeType = null,
        sortOrder = 0,
    )

    private fun question(id: String, resources: List<QuestionResource>) = ExamQuestion(
        id = id,
        examId = "ex",
        title = id,
        statement = "Enunciado $id",
        sortOrder = 0,
        options = emptyList(),
        resources = resources,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Pendiente = no es de texto y todavía no tiene URL que abrir. Un texto se
     * sostiene con su cuerpo. Mutación: contar como pendiente un texto sin URL, o
     * una URL en blanco como accesible, pone este test en rojo.
     */
    @Test
    fun aResourceIsPendingOnlyWhenItIsNotTextAndHasNoUrlToOpen() {
        val audit = listOf(
            question(
                "q1",
                listOf(
                    resource("img", QuestionResourceType.IMAGE, "https://x/i.png"),
                    resource("aud", QuestionResourceType.AUDIO, null),
                    resource("doc", QuestionResourceType.DOCUMENT, "  "),
                    resource("txt", QuestionResourceType.TEXT, null),
                ),
            ),
            question("q2", emptyList()),
        ).toAudit()

        assertEquals(2, audit.summary.totalQuestions)
        assertEquals(1, audit.summary.questionsWithResources)
        assertEquals(1, audit.summary.questionsWithoutResources)
        assertEquals(4, audit.summary.linkedResources)
        assertEquals(2, audit.summary.pendingResources)
        assertEquals(listOf(2, 0), audit.questions.map { it.pendingResourceCount })
        assertEquals(listOf(true, false), audit.questions.map { it.hasLinkedResources })
        // Los «sin vincular» no se pueden deducir de las preguntas: vacío, nunca inventado.
        assertEquals(0, audit.summary.unlinkedResources)
        assertEquals(emptyList(), audit.unlinkedResources)
    }

    @Test
    fun anExamWithoutQuestionsIsAnEmptyAuditNotAFailure() {
        val audit = emptyList<ExamQuestion>().toAudit()

        assertEquals(0, audit.summary.totalQuestions)
        assertEquals(emptyList(), audit.questions)
    }
}
