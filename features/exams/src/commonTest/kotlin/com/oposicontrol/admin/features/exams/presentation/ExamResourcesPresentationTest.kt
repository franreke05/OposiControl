package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lo que la pestaña Recursos afirma, comprobado sin pintar.
 *
 * Dos reglas que esta pantalla existe para no romper:
 *
 *  - **Sólo las preguntas inmersivas pueden llevar recursos.** Es regla de
 *    producto y el backend no la impone, así que en los datos puede haber
 *    recursos colgados de otra cosa: se enseñan como anomalía, no se esconden.
 *  - **Una subida que nunca ocurrió no puede parecer un fichero servido.** El
 *    cliente deja hoy la ruta local en el campo de URL (P0-4 / EXAM-008): eso se
 *    dice, y la ruta no se enseña.
 */
class ExamResourcesPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun resource(
        id: String,
        type: QuestionResourceType = QuestionResourceType.IMAGE,
        url: String? = "https://cdn.oposicontrol.dev/recursos/$id.bin",
        text: String? = null,
    ) = QuestionResource(
        id = id,
        questionId = "q-1",
        type = type,
        title = "Recurso $id",
        textBody = text,
        storagePath = null,
        publicUrl = url,
        mimeType = null,
        sortOrder = 0,
    )

    private fun question(
        id: String = "q-1",
        type: QuestionType? = QuestionType.INMERSIVA,
        resources: List<QuestionResource> = emptyList(),
    ) = ExamQuestion(
        id = id,
        examId = "ex-1",
        title = "Pregunta $id",
        statement = "Enunciado de $id",
        sortOrder = 0,
        type = type,
        options = emptyList(),
        resources = resources,
        createdAt = now,
        updatedAt = now,
    )

    // -----------------------------------------------------------------------
    // Ruta local: el rastro de una subida que no existió
    // -----------------------------------------------------------------------

    @Test
    fun `una ruta local se reconoce en las formas en que aparece de verdad`() {
        val locales = listOf(
            "C:\\Users\\admin\\Desktop\\auscultacion.mp3",
            "C:/Users/admin/Desktop/auscultacion.mp3",
            "file:///C:/Users/admin/recurso.png",
            "\\\\servidor\\compartido\\recurso.png",
            "/Users/admin/Documents/recurso.pdf",
            "/home/admin/recurso.pdf",
        )
        locales.forEach { ruta ->
            assertTrue(isLocalResourcePath(ruta), "Debería reconocerse como ruta local: $ruta")
        }
    }

    @Test
    fun `una url servida no se marca como ruta local`() {
        listOf(
            "https://cdn.oposicontrol.dev/recursos/r-1.png",
            "http://localhost:8080/recursos/r-1.png",
            "",
            "   ",
        ).forEach { valor ->
            assertFalse(isLocalResourcePath(valor), "No debería marcarse: «$valor»")
        }
    }

    @Test
    fun `un recurso con ruta local se declara no disponible y nunca ensena la ruta`() {
        val ruta = "C:\\Users\\admin\\Desktop\\auscultacion.mp3"
        val grupos = examResourceGroups(
            listOf(question(resources = listOf(resource("r-1", QuestionResourceType.AUDIO, url = ruta)))),
        )

        val entrada = grupos.single().resources.single()
        assertTrue(entrada.isUnavailable, "Una ruta local no es un fichero servido")
        assertFalse(entrada.preview.contains("Users"), "La ruta no puede salir en pantalla: ${entrada.preview}")
        assertFalse(entrada.preview.contains("\\"), "La ruta no puede salir en pantalla: ${entrada.preview}")
        assertEquals("auscultacion.mp3", entrada.preview)
    }

    @Test
    fun `un recurso servido no se marca como no disponible`() {
        val grupos = examResourceGroups(listOf(question(resources = listOf(resource("r-1")))))

        assertFalse(grupos.single().resources.single().isUnavailable)
    }

    @Test
    fun `un recurso de texto no se juzga por su url`() {
        val grupos = examResourceGroups(
            listOf(
                question(
                    resources = listOf(
                        resource("r-1", QuestionResourceType.TEXT, url = null, text = "Paciente de 68 años."),
                    ),
                ),
            ),
        )

        val entrada = grupos.single().resources.single()
        assertFalse(entrada.isUnavailable)
        assertEquals("Paciente de 68 años.", entrada.preview)
    }

    // -----------------------------------------------------------------------
    // Sólo las inmersivas pueden llevar recursos
    // -----------------------------------------------------------------------

    @Test
    fun `una pregunta inmersiva puede llevar recursos`() {
        val grupos = examResourceGroups(
            listOf(question(type = QuestionType.INMERSIVA, resources = listOf(resource("r-1")))),
        )

        val grupo = grupos.single()
        assertEquals(QuestionType.INMERSIVA, grupo.questionType)
        assertTrue(grupo.resourcesAllowed)
    }

    @Test
    fun `un recurso en una pregunta que no consta inmersiva es una anomalia`() {
        val grupos = examResourceGroups(
            listOf(question(type = QuestionType.RAZONADA, resources = listOf(resource("r-1")))),
        )

        assertFalse(grupos.single().resourcesAllowed, "Sólo las inmersivas pueden llevar recursos")
    }

    @Test
    fun `un tipo sin identificar tampoco autoriza recursos, y se dice que no consta`() {
        val grupos = examResourceGroups(
            listOf(question(type = null, resources = listOf(resource("r-1")))),
        )

        val grupo = grupos.single()
        assertFalse(grupo.resourcesAllowed)
        assertEquals("Tipo sin identificar", questionTypeLabel(grupo.questionType))
    }

    /**
     * El aviso de la anomalía dice «no consta», no «no es».
     *
     * Un `tipo_pregunta` ausente llega como «rafaga» por el valor por defecto del
     * DTO del backend, así que de una pregunta no inmersiva sólo se puede afirmar
     * que no consta como tal.
     */
    @Test
    fun `el aviso de anomalia no afirma mas certeza de la que hay`() {
        assertTrue(EXAM_RESOURCE_NOT_IMMERSIVE.contains("no consta"))
    }

    @Test
    fun `el aviso de archivo no disponible explica la causa sin ensenar la ruta`() {
        assertTrue(EXAM_RESOURCE_UNAVAILABLE.contains("ruta local"))
        assertFalse(EXAM_RESOURCE_UNAVAILABLE.contains("C:"))
    }
}
