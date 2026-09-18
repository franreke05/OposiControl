package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Specialty
import kotlinx.datetime.Instant

/**
 * Estados de Preguntas para el banco de pruebas visual.
 *
 * Un solo sitio para todos los casos que se capturan. Los datos son inventados a
 * propósito y no tocan ningún repositorio: esto sirve para revisar jerarquía,
 * densidad, tema y responsive, no para validar el contrato.
 */
internal object QuestionsFixtures {

    private val NOW = Instant.parse("2026-04-15T09:00:00Z")

    val EXAMENES: List<Exam> = listOf(
        exam("ex-1", "EX-2026-0001", "TCAE · Simulacro marzo"),
        exam("ex-2", "EX-2026-0002", "TCAE · Simulacro abril"),
        exam("ex-3", "EX-2026-0004", "TCAE · Higiene hospitalaria"),
    )

    private fun exam(id: String, code: String, title: String) = Exam(
        id = id,
        code = code,
        specialtyId = "esp-tcae",
        specialtyName = "TCAE",
        title = title,
        description = "Simulacro completo con el temario de la última convocatoria.",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = NOW,
        sortOrder = 0,
        questionCount = 5,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun options(correct: String) = listOf("A", "B", "C", "D").mapIndexed { index, label ->
        QuestionOption(
            id = "o-$label",
            questionId = "q-1",
            label = label,
            content = when (label) {
                "A" -> "Lavado de manos con solución hidroalcohólica antes y después de cada contacto."
                "B" -> "Uso de guantes estériles únicamente en quirófano."
                "C" -> "Cambio de bata al finalizar el turno."
                else -> "Desinfección del carro de curas una vez por semana."
            },
            isCorrect = label == correct,
            sortOrder = index,
        )
    }

    private val CATALOGO: List<QuestionListItem> = listOf(
        QuestionListItem(
            id = "q-1",
            code = "PREG-000142",
            type = QuestionType.INMERSIVA,
            statement = "Observa el caso clínico y señala la actuación correcta del técnico en cuidados auxiliares.",
            active = true,
        ),
        QuestionListItem(
            id = "q-2",
            code = "PREG-000141",
            type = QuestionType.RAFAGA,
            statement = "La Constitución Española reconoce el derecho a la protección de la salud en su artículo…",
            active = true,
        ),
        QuestionListItem(
            id = "q-3",
            code = "PREG-000140",
            type = QuestionType.RAZONADA,
            statement = "¿Cuál de las siguientes medidas reduce de forma más eficaz la infección nosocomial?",
            active = true,
        ),
        QuestionListItem(
            id = "q-4",
            code = "PREG-000139",
            type = null,
            statement = "Pregunta heredada de una importación antigua, con el tipo sin reconocer.",
            active = true,
        ),
        QuestionListItem(
            id = "q-5",
            code = "PREG-000138",
            type = QuestionType.RAFAGA,
            statement = "Señala el orden correcto de colocación del equipo de protección individual.",
            active = false,
        ),
        QuestionListItem(
            id = "q-6",
            code = "PREG-000137",
            type = QuestionType.RAZONADA,
            statement = "En una cura estéril, ¿cuál es la secuencia correcta de retirada del material?",
            active = true,
        ),
    )

    /** Una página tal y como la devuelve el servidor: sus filas y el total de la consulta (KQ0-4). */
    private fun page(items: List<QuestionListItem>, total: Int = items.size) =
        QuestionPage(items = items, total = total, offset = 0, limit = QUESTION_PAGE_SIZE_DEFAULT)

    private fun base(
        detail: UiAsync<QuestionDetail> = UiAsync.Idle,
        selected: String? = null,
    ) = QuestionsState(
        questions = UiAsync.Success(page(CATALOGO)),
        exams = UiAsync.Success(EXAMENES),
        specialties = UiAsync.Success(ESPECIALIDADES),
        selectedQuestionId = selected,
        detail = detail,
    )

    private val ESPECIALIDADES: List<Specialty> = listOf(
        Specialty(
            id = "esp-tcae",
            name = "TCAE",
            description = "",
            isActive = true,
            sortOrder = 0,
            linkedExams = 3,
            createdAt = NOW,
            updatedAt = NOW,
        ),
    )

    private fun detail(
        id: String,
        code: String,
        type: QuestionType?,
        statement: String,
        explanation: String = "",
        resources: List<QuestionResource> = emptyList(),
    ) = QuestionDetail(
        id = id,
        code = code,
        type = type,
        statement = statement,
        options = options("A"),
        explanation = explanation,
        source = "BOE núm. 122, de 22 de mayo",
        active = true,
        difficulty = "media",
        resources = resources,
    )

    // -----------------------------------------------------------------------
    // Casos
    // -----------------------------------------------------------------------

    /** Una razonada abierta, con explicación y sin cambios pendientes. */
    fun razonadaSeleccionada(): QuestionsState {
        val d = detail(
            id = "q-3",
            code = "PREG-000140",
            type = QuestionType.RAZONADA,
            statement = "¿Cuál de las siguientes medidas reduce de forma más eficaz la infección nosocomial?",
            explanation = "La higiene de manos es la medida aislada con mayor impacto demostrado sobre la " +
                "transmisión cruzada en el medio hospitalario.",
        )
        return base(detail = UiAsync.Success(d), selected = "q-3").copy(
            draftStatement = d.statement,
            draftType = QuestionType.RAZONADA,
            draftOptions = d.options,
            draftExplanation = d.explanation,
            draftSource = d.source,
            draftDifficulty = d.difficulty,
        )
    }

    /** La misma, con un cambio sin guardar: la barra tiene que decirlo. */
    fun razonadaConCambiosSinGuardar(): QuestionsState =
        razonadaSeleccionada().copy(draftStatement = "Enunciado corregido y todavía sin guardar.", dirty = true)

    /** Inmersiva con recursos reales: uno accesible, uno de texto y uno sin URL pública. */
    fun inmersivaConRecursos(): QuestionsState {
        val recursos = listOf(
            QuestionResource(
                id = "r-1", questionId = "q-1", type = QuestionResourceType.AUDIO,
                title = "Parte de enfermería leído", textBody = null,
                storagePath = "preguntas/q-1/audio-9f3c.mp3",
                publicUrl = "https://cdn.oposicontrol.test/audio.mp3", mimeType = "audio/mpeg", sortOrder = 0,
            ),
            QuestionResource(
                id = "r-2", questionId = "q-1", type = QuestionResourceType.IMAGE,
                title = "Fotografía del carro de curas", textBody = null,
                storagePath = "preguntas/q-1/carro.png", publicUrl = null, mimeType = "image/png", sortOrder = 1,
            ),
            QuestionResource(
                id = "r-3", questionId = "q-1", type = QuestionResourceType.TEXT,
                title = "Protocolo del centro", textBody = "Extracto del protocolo interno de higiene.",
                storagePath = null, publicUrl = null, mimeType = null, sortOrder = 2,
            ),
        )
        val d = detail(
            id = "q-1",
            code = "PREG-000142",
            type = QuestionType.INMERSIVA,
            statement = "Observa el caso clínico y señala la actuación correcta del técnico en cuidados auxiliares.",
            explanation = "El caso describe un contacto con fluidos: la actuación correcta empieza por la " +
                "higiene de manos.",
            resources = recursos,
        )
        return base(detail = UiAsync.Success(d), selected = "q-1").copy(
            draftStatement = d.statement,
            draftType = QuestionType.INMERSIVA,
            draftOptions = d.options,
            draftExplanation = d.explanation,
            draftSource = d.source,
            draftDifficulty = d.difficulty,
            examLinks = UiAsync.Success(
                listOf(
                    QuestionExamLink(
                        examId = "ex-3",
                        examCode = "EX-2026-0004",
                        examTitle = "TCAE · Higiene hospitalaria",
                        position = 4,
                        examType = "inmersivo",
                        status = "publicado",
                    ),
                ),
            ),
        )
    }

    /** Una ráfaga: sin explicación y sin recursos. */
    fun rafagaSeleccionada(): QuestionsState {
        val d = detail(
            id = "q-2",
            code = "PREG-000141",
            type = QuestionType.RAFAGA,
            statement = "La Constitución Española reconoce el derecho a la protección de la salud en su artículo…",
        )
        return base(detail = UiAsync.Success(d), selected = "q-2").copy(
            draftStatement = d.statement,
            draftType = QuestionType.RAFAGA,
            draftOptions = d.options,
            draftSource = d.source,
            draftDifficulty = d.difficulty,
        )
    }

    /** Tipo que no es ninguno de los tres canónicos: se dice, no se disfraza. */
    fun tipoSinIdentificar(): QuestionsState {
        val d = detail(
            id = "q-4",
            code = "PREG-000139",
            type = null,
            statement = "Pregunta heredada de una importación antigua, con el tipo sin reconocer.",
        )
        return base(detail = UiAsync.Success(d), selected = "q-4").copy(
            draftStatement = d.statement,
            draftType = QuestionType.RAZONADA,
            draftOptions = d.options,
            draftSource = d.source,
            draftDifficulty = d.difficulty,
        )
    }

    /** Alta: el editor completo, sin pregunta detrás. */
    fun nueva(): QuestionsState = base().copy(
        isCreating = true,
        draftType = QuestionType.RAZONADA,
        draftOptions = defaultDraftOptions(),
        draftStatement = "",
        draftExplanation = "",
        draftSource = "",
        // Como `startCreate`: sin definir, nunca un «media» inventado (KQ0-2).
        draftDifficulty = "",
        optionsDirty = true,
    )

    /** El banco todavía cargando. */
    fun cargando(): QuestionsState = QuestionsState(
        questions = UiAsync.Loading,
        exams = UiAsync.Success(EXAMENES),
    )

    /** Banco vacío de verdad: el backend respondió sin filas. */
    fun bancoVacio(): QuestionsState = QuestionsState(
        questions = UiAsync.Empty,
        exams = UiAsync.Success(EXAMENES),
    )

    /** El banco no ha podido cargar. La cabecera sigue en pie. */
    fun bancoCaido(): QuestionsState = QuestionsState(
        questions = UiAsync.Error(AppError.Network("connection refused")),
        exams = UiAsync.Success(EXAMENES),
    )

    /** Filtros que no dejan pasar nada: el servidor devuelve una página vacía, distinta de un banco vacío. */
    fun sinCoincidencias(): QuestionsState = base().copy(
        searchQuery = "trombocitopenia",
        questions = UiAsync.Success(page(emptyList())),
    )

    /** Viendo sólo las preguntas de un examen: la lista no es el banco entero. */
    fun filtradoPorExamen(): QuestionsState = base().copy(
        examFilter = "ex-3",
        questions = UiAsync.Success(
            page(CATALOGO.take(3).mapIndexed { index, item -> item.copy(examId = "ex-3", position = index + 1) }),
        ),
    )

    /** Un guardado rechazado por el servidor: error, nunca éxito. */
    fun guardadoRechazado(): QuestionsState = razonadaConCambiosSinGuardar().copy(
        optionsDirty = true,
        saveState = UiAsync.Error(AppError.Validation("admin_replace_question_options no disponible")),
    )
}
