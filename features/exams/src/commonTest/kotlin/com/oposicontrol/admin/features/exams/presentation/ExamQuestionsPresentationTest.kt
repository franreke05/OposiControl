package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pestaña Preguntas afirma, comprobado sin pintar.
 *
 * Las cuatro reglas que no pueden romperse: el tipo se dice o se admite que no se
 * sabe, la numeración es la de la lista, los hechos de una pregunta son los que
 * tiene, y el recuento es EL MISMO que el de la cabecera.
 */
class ExamQuestionsPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun question(
        id: String,
        order: Int,
        type: QuestionType? = QuestionType.RAFAGA,
        options: List<QuestionOption> = listOf(option(id, "A", true), option(id, "B", false)),
        resources: List<QuestionResource> = emptyList(),
        title: String = "Pregunta $id",
        statement: String = "Enunciado $id",
    ) = ExamQuestion(
        id = id,
        examId = "ex-1",
        title = title,
        statement = statement,
        sortOrder = order,
        type = type,
        options = options,
        resources = resources,
        createdAt = now,
        updatedAt = now,
    )

    private fun option(questionId: String, label: String, correct: Boolean) = QuestionOption(
        id = "$questionId-$label",
        questionId = questionId,
        label = label,
        content = "Opción $label",
        isCorrect = correct,
        sortOrder = if (label == "A") 0 else 1,
    )

    private fun resource(id: String, questionId: String) = QuestionResource(
        id = id,
        questionId = questionId,
        type = QuestionResourceType.IMAGE,
        title = "Recurso $id",
        textBody = null,
        storagePath = null,
        publicUrl = "https://cdn.example.test/$id.png",
        mimeType = "image/png",
        sortOrder = 0,
    )

    private fun exam(questionCount: Int) = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        title = "TCAE · Simulacro",
        description = "Simulacro.",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = now,
        sortOrder = 0,
        questionCount = questionCount,
        durationMinutes = 45,
        year = 2026,
        createdAt = now,
        updatedAt = now,
    )

    private fun state(
        questions: UiAsync<List<ExamQuestion>>,
        exam: Exam = exam(3),
        counted: Map<String, Int> = emptyMap(),
    ) = ExamsState(
        catalogExams = listOf(exam),
        exams = UiAsync.Success(listOf(exam)),
        selectedExamId = exam.id,
        questions = questions,
        countedQuestions = counted,
    )

    // -----------------------------------------------------------------------
    // Tipo de pregunta
    // -----------------------------------------------------------------------

    @Test
    fun `cada tipo canonico tiene su etiqueta y el alias legacy no aparece`() {
        assertEquals("Ráfaga", questionTypeLabel(QuestionType.RAFAGA))
        assertEquals("Razonada", questionTypeLabel(QuestionType.RAZONADA))
        assertEquals("Inmersiva", questionTypeLabel(QuestionType.INMERSIVA))
        // `guiada` es alias de lectura: no puede salir por ninguna etiqueta.
        QuestionType.entries.forEach { type ->
            val label = questionTypeLabel(type).lowercase()
            assertFalse(label.contains("guiad"), "«${questionTypeLabel(type)}» usa el alias legacy")
        }
    }

    /**
     * ESTA ES LA PRUEBA DE MUTACIÓN del tipo. Un `?: QuestionType.RAFAGA` en el
     * mapper, o un `?: "Ráfaga"` aquí, pinta como Ráfaga perfectamente normal lo
     * que en realidad no sabemos, y nadie vuelve a mirarlo nunca.
     */
    @Test
    fun `un tipo que no resuelve se dice y jamas se disfraza de rafaga`() {
        assertEquals(QUESTION_TYPE_UNKNOWN_LABEL, questionTypeLabel(null))
        assertEquals("Tipo sin identificar", QUESTION_TYPE_UNKNOWN_LABEL)
        assertFalse(QUESTION_TYPE_UNKNOWN_LABEL.lowercase().contains("ráfaga"))

        val fila = examQuestionRows(listOf(question("q-1", 0, type = null))).single()
        assertFalse(fila.typeKnown)
        assertEquals(QUESTION_TYPE_UNKNOWN_LABEL, fila.typeLabel)
    }

    /** La reserva del contrato se dice, y no promete una detección que no existe. */
    @Test
    fun `la reserva sobre el tipo admite que el campo ausente es indistinguible`() {
        assertTrue(EXAM_QUESTION_TYPE_CAVEAT.contains("no se puede distinguir"), EXAM_QUESTION_TYPE_CAVEAT)
        assertTrue(EXAM_QUESTION_TYPE_CAVEAT.contains("Ráfaga"), EXAM_QUESTION_TYPE_CAVEAT)
    }

    // -----------------------------------------------------------------------
    // Las filas
    // -----------------------------------------------------------------------

    /**
     * La numeración es la de la LISTA, no `sortOrder`.
     *
     * El servidor recompacta `posicion` al desvincular, pero entre medias el
     * cliente puede tener huecos; numerar con el campo crudo enseñaría un «1, 4,
     * 8» que en la app no existe.
     */
    @Test
    fun `las filas se numeran por posicion de lista y respetan el orden`() {
        val filas = examQuestionRows(
            listOf(
                question("q-c", 9),
                question("q-a", 3),
                question("q-b", 7),
            ),
        )
        assertEquals(listOf("q-a", "q-b", "q-c"), filas.map { it.id })
        assertEquals(listOf(1, 2, 3), filas.map { it.position })
        // Ni la primera sube ni la última baja.
        assertFalse(filas.first().canMoveUp)
        assertTrue(filas.first().canMoveDown)
        assertTrue(filas.last().canMoveUp)
        assertFalse(filas.last().canMoveDown)
    }

    @Test
    fun `una sola pregunta no se puede mover en ninguna direccion`() {
        val fila = examQuestionRows(listOf(question("q-1", 0))).single()
        assertFalse(fila.canMoveUp)
        assertFalse(fila.canMoveDown)
    }

    /** Sin opciones y sin correcta son cosas distintas: una es falta de datos, la otra un defecto. */
    @Test
    fun `los hechos de una pregunta distinguen sin opciones de sin respuesta marcada`() {
        assertEquals("Sin opciones", examQuestionFacts(question("q-1", 0, options = emptyList())))
        assertEquals(
            "Sin respuesta correcta marcada",
            examQuestionFacts(
                question("q-1", 0, options = listOf(option("q-1", "A", false), option("q-1", "B", false))),
            ),
        )
        assertEquals("Respuesta correcta: A", examQuestionFacts(question("q-1", 0)))
    }

    @Test
    fun `el recuento de recursos concuerda en singular y en plural`() {
        assertEquals(
            "Respuesta correcta: A · 1 recurso",
            examQuestionFacts(question("q-1", 0, resources = listOf(resource("r-1", "q-1")))),
        )
        assertEquals(
            "Respuesta correcta: A · 2 recursos",
            examQuestionFacts(
                question("q-1", 0, resources = listOf(resource("r-1", "q-1"), resource("r-2", "q-1"))),
            ),
        )
        // Cero recursos no se dice: «0 recursos» no es un dato, es ruido.
        assertEquals("Respuesta correcta: A", examQuestionFacts(question("q-1", 0)))
    }

    @Test
    fun `una pregunta sin titulo cae al enunciado y nunca a un identificador`() {
        assertEquals("Enunciado q-1", examQuestionTitle(question("q-1", 0, title = "")))
        assertEquals(
            "Pregunta sin título",
            examQuestionTitle(question("q-1", 0, title = "", statement = "")),
        )
        // Nunca el id: un identificador en pantalla no es un dato de producto.
        assertFalse(examQuestionTitle(question("q-1", 0, title = "", statement = "")).contains("q-1"))
    }

    // -----------------------------------------------------------------------
    // Recuento: una sola fuente de verdad
    // -----------------------------------------------------------------------

    /**
     * ESTA ES LA PRUEBA DE MUTACIÓN del recuento. Cualquier regla propia aquí
     * —contar la lista a mano, caer a `exam.questionCount`— hace que la pestaña
     * diga un número distinto del de la cabecera, que es el defecto que ya se dio
     * una vez entre catálogo y workspace.
     */
    @Test
    fun `el recuento de la pestana es exactamente el de la cabecera`() {
        val preguntas = listOf(question("q-1", 0), question("q-2", 1), question("q-3", 2))
        listOf(
            UiAsync.Success(preguntas),
            UiAsync.Empty,
            UiAsync.Loading,
            UiAsync.Error(AppError.Network("timeout")),
        ).forEach { async ->
            val estado = state(async, counted = mapOf("ex-1" to 3))
            assertEquals(
                linkedQuestionsLabel(estado.questions, provisionalQuestionCount(estado, exam(3))),
                examQuestionsCountLabel(estado, exam(3)),
                "La pestaña ha dejado de leer del recuento común en $async",
            )
        }
    }

    /** K2-2: mientras carga, la cifra (de la sesión o servida) es un dato desde M1. */
    @Test
    fun `mientras carga la cifra es un dato y con error no hay cifra`() {
        val cargando = examQuestionsCountLabel(state(UiAsync.Loading, counted = mapOf("ex-1" to 5)), exam(3))
        assertEquals(ExamFieldOrigin.VALUE, cargando.origin)
        assertEquals(linkedQuestionsText(5), cargando.text)
        assertFalse(cargando.text.contains("sin confirmar"), cargando.text)

        val fallo = examQuestionsCountLabel(state(UiAsync.Error(AppError.Network("timeout"))), exam(3))
        assertEquals(ExamFieldOrigin.UNAVAILABLE, fallo.origin)
        // Sin cifra: no saber no es saber tres.
        assertFalse(fallo.text.any { it.isDigit() }, fallo.text)

        // Lista cargada y vacía SÍ es cero, y es un valor.
        val vacio = examQuestionsCountLabel(state(UiAsync.Empty), exam(3))
        assertEquals(ExamFieldOrigin.VALUE, vacio.origin)
        assertEquals(linkedQuestionsText(0), vacio.text)
    }

    // -----------------------------------------------------------------------
    // Acciones: inmediatas, y lo que está bloqueado
    // -----------------------------------------------------------------------

    @Test
    fun `mientras una accion esta en curso no se ofrece lanzar otra`() {
        assertFalse(examQuestionsBusy(state(UiAsync.Success(listOf(question("q-1", 0))))))
        assertTrue(
            examQuestionsBusy(
                state(UiAsync.Success(listOf(question("q-1", 0)))).copy(actionState = UiAsync.Loading),
            ),
        )
    }

    @Test
    fun `la nota separa las acciones inmediatas del formulario`() {
        assertTrue(EXAM_QUESTIONS_IMMEDIATE_NOTE.contains("al momento"), EXAM_QUESTIONS_IMMEDIATE_NOTE)
        assertTrue(EXAM_QUESTIONS_IMMEDIATE_NOTE.contains("Guardar cambios"), EXAM_QUESTIONS_IMMEDIATE_NOTE)
        // Desvincular y archivar se explican como lo que son: alcances distintos.
        assertTrue(EXAM_QUESTION_UNLINK_NOTE.contains("banco"), EXAM_QUESTION_UNLINK_NOTE)
        assertTrue(EXAM_QUESTION_UNLINK_NOTE.contains("todos los exámenes"), EXAM_QUESTION_UNLINK_NOTE)
    }

    /**
     * Crear está bloqueado y se dice sin filtrar implementación: ni códigos de
     * error, ni nombres de tabla, ni hints del gestor de base de datos.
     */
    @Test
    fun `el bloqueo de crear se explica sin filtrar implementacion`() {
        assertTrue(EXAM_QUESTIONS_CREATE_BLOCKED.contains("no admite"), EXAM_QUESTIONS_CREATE_BLOCKED)
        listOf("pgrst", "rpc", "supabase", "postgres", "sql", "http", "404").forEach { fuga ->
            assertFalse(
                EXAM_QUESTIONS_CREATE_BLOCKED.lowercase().contains(fuga),
                "El motivo filtra «$fuga»: $EXAM_QUESTIONS_CREATE_BLOCKED",
            )
        }
    }

    /**
     * Ninguna frase de la pestaña inventa una operación que no existe —vincular
     * una pregunta existente, programar, revisar— ni un estado de pregunta.
     */
    @Test
    fun `ninguna frase promete lo que no existe`() {
        val textos = listOf(
            EXAM_QUESTIONS_IMMEDIATE_NOTE,
            EXAM_QUESTIONS_NEW_EXAM_TITLE,
            EXAM_QUESTIONS_NEW_EXAM_MESSAGE,
            EXAM_QUESTIONS_EMPTY_TITLE,
            EXAM_QUESTIONS_EMPTY_MESSAGE,
            EXAM_QUESTIONS_ERROR_TITLE,
            EXAM_QUESTIONS_LOADING_LABEL,
            EXAM_QUESTIONS_LOADING_DETAIL,
            EXAM_QUESTIONS_CREATE_BLOCKED,
            EXAM_QUESTION_UNLINK_NOTE,
            EXAM_QUESTION_TYPE_CAVEAT,
        )
        textos.forEach { texto ->
            val t = texto.lowercase()
            listOf("vincular una pregunta existente", "en revisión", "programad", "duplicad").forEach { prohibido ->
                assertFalse(t.contains(prohibido), "«$texto» promete «$prohibido»")
            }
        }
        // El vacío explica el camino REAL en vez de ofrecer un botón que no existe.
        assertTrue(EXAM_QUESTIONS_EMPTY_MESSAGE.contains("pantalla de Preguntas"), EXAM_QUESTIONS_EMPTY_MESSAGE)
    }

    @Test
    fun `un fallo de carga lleva el mensaje humano y una carga correcta no lleva ninguno`() {
        val error = AppError.Network("timeout")
        assertEquals(error.humanMessage(), examQuestionsLoadError(UiAsync.Error(error)))
        assertNull(examQuestionsLoadError(UiAsync.Success(listOf(question("q-1", 0)))))
        assertNull(examQuestionsLoadError(UiAsync.Empty))
    }
}
