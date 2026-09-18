package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionListSort
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pantalla Preguntas afirma, comprobado sin pintar.
 *
 * Tipos canónicos, respuestas, qué secciones aplican a cada tipo, y sobre todo:
 * que no se enseña nada que el contrato no tenga — ni tema, ni etiquetas, ni
 * rutas internas de almacenamiento, ni UUID.
 */
class QuestionsPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun exam(id: String = "ex-1", code: String = "EX-2026-0001") = Exam(
        id = id,
        code = code,
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        title = "TCAE · Simulacro marzo",
        description = "",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = now,
        sortOrder = 0,
        questionCount = 5,
        createdAt = now,
        updatedAt = now,
    )

    private fun specialty(id: String, name: String) = Specialty(
        id = id,
        name = name,
        description = "",
        isActive = true,
        sortOrder = 0,
        linkedExams = 0,
        createdAt = now,
        updatedAt = now,
    )

    private fun options(correct: String = "A", blank: Boolean = false) =
        listOf("A", "B", "C", "D").mapIndexed { index, label ->
            QuestionOption(
                id = "o-$label",
                questionId = "q-1",
                label = label,
                content = if (blank) "" else "Texto de la opción $label",
                isCorrect = label == correct,
                sortOrder = index,
            )
        }

    private fun state(
        type: QuestionType = QuestionType.RAZONADA,
        correct: String = "A",
        statement: String = "¿Cuál de las siguientes es correcta?",
    ) = QuestionsState(
        selectedQuestionId = "q-1",
        draftType = type,
        draftStatement = statement,
        draftOptions = options(correct),
        draftExplanation = "Porque la norma lo dice.",
        draftSource = "BOE",
        draftDifficulty = "media",
    )

    // -----------------------------------------------------------------------
    // Tipos canónicos
    // -----------------------------------------------------------------------

    @Test
    fun `los tres tipos canonicos se nombran y ninguno se llama guiada`() {
        assertEquals("Ráfaga", questionTypeLabel(QuestionType.RAFAGA))
        assertEquals("Razonada", questionTypeLabel(QuestionType.RAZONADA))
        assertEquals("Inmersiva", questionTypeLabel(QuestionType.INMERSIVA))
        QuestionType.entries.forEach { type ->
            assertFalse(questionTypeLabel(type).lowercase().contains("guiad"), questionTypeLabel(type))
        }
    }

    /** El alias legacy se LEE como razonada; nunca se crea ni se escribe. */
    @Test
    fun `el alias legacy guiada se lee como razonada`() {
        assertEquals(QuestionType.RAZONADA, QuestionType.fromExternal("guiada"))
        assertEquals("razonada", QuestionType.RAZONADA.wireValue)
    }

    @Test
    fun `un tipo desconocido lo dice y no se disfraza de rafaga`() {
        assertEquals(QUESTION_UNKNOWN_TYPE, questionTypeLabel(null))
        assertEquals("Tipo sin identificar", QUESTION_UNKNOWN_TYPE)
        assertNull(QuestionType.fromExternal("mixta"))
    }

    @Test
    fun `las tres tarjetas de tipo estan siempre disponibles y solo una elegida`() {
        val choices = questionTypeChoices(QuestionType.INMERSIVA)
        assertEquals(3, choices.size)
        assertEquals(listOf(false, false, true), choices.map { it.selected })
        assertEquals(listOf(true, true, false), choices.map { it.selectable })
        assertEquals(
            "Inmersiva. Puede apoyarse en recursos: imagen, audio, vídeo o documento. Elegido.",
            questionTypeChoiceDescription(choices.single { it.selected }),
        )
    }

    // -----------------------------------------------------------------------
    // Identidad: nunca un UUID
    // -----------------------------------------------------------------------

    @Test
    fun `sin codigo se dice en vez de recortar el identificador interno`() {
        assertEquals("PREG-000123", questionCodeLabel("PREG-000123"))
        assertEquals("Sin código", questionCodeLabel(""))
        assertEquals("Sin código", questionCodeLabel("   "))
    }

    @Test
    fun `un examen se nombra por codigo y titulo, nunca por su id`() {
        assertEquals("EX-2026-0001 · TCAE · Simulacro marzo", examOptionLabel(exam()))
        assertEquals("TCAE · Simulacro marzo", examOptionLabel(exam(code = "")))
    }

    // -----------------------------------------------------------------------
    // Banco: una búsqueda, filtros reales
    // -----------------------------------------------------------------------

    /** No hay etiquetas en el modelo de pregunta: el buscador no puede prometerlas. */
    @Test
    fun `el buscador no promete etiquetas porque no existen`() {
        assertEquals("Buscar por código o enunciado…", QUESTION_SEARCH_PLACEHOLDER)
        assertFalse(QUESTION_SEARCH_PLACEHOLDER.lowercase().contains("etiqueta"))
    }

    @Test
    fun `los filtros ofrecidos son los que de verdad filtran`() {
        assertEquals(
            listOf("Todos los tipos", "Ráfaga", "Razonada", "Inmersiva"),
            questionTypeFilterOptions().map { it.label },
        )
        assertEquals(
            listOf("Todos los estados", "Activas", "Inactivas"),
            questionStatusFilterOptions().map { it.label },
        )
        // Cada etiqueta pide al servidor el estado que dice (KQ0-4).
        assertEquals(
            listOf(QuestionActiveFilter.ALL, QuestionActiveFilter.ACTIVE, QuestionActiveFilter.INACTIVE),
            questionStatusFilterOptions().map { it.value },
        )
        val exams = questionExamFilterOptions(UiAsync.Success(listOf(exam())))
        assertEquals(listOf("Todos los exámenes", "EX-2026-0001 · TCAE · Simulacro marzo"), exams.map { it.label })
        val specialties = questionSpecialtyFilterOptions(UiAsync.Success(listOf(specialty("esp-1", "TCAE"))))
        assertEquals(listOf("Todas las especialidades", "TCAE"), specialties.map { it.label })
        assertEquals(listOf(null, "esp-1"), specialties.map { it.value })
    }

    /**
     * KQ0-4: la consulta que sale de los filtros es la que se pide al servidor, con
     * la página convertida a `offset`/`limit`; y «nada coincide» sólo con filtros.
     */
    @Test
    fun `los filtros se convierten en la consulta del servidor`() {
        val estado = QuestionsState(
            searchQuery = "  Francia ",
            typeFilter = QuestionType.RAZONADA,
            specialtyFilter = "esp-1",
            activeFilter = QuestionActiveFilter.INACTIVE,
            sort = QuestionsSort.BY_CODE,
            pageIndex = 2,
            pageSize = 25,
        )
        assertEquals(
            QuestionListQuery(
                search = "Francia",
                type = QuestionType.RAZONADA,
                specialtyId = "esp-1",
                active = QuestionActiveFilter.INACTIVE,
                examId = null,
                sort = QuestionListSort.BY_CODE,
                offset = 50,
                limit = 25,
            ),
            estado.toListQuery(),
        )
        assertFalse(QuestionsState().hasBankFilters(), "Activas sin nada más es el banco, no un filtro")
        assertTrue(QuestionsState(activeFilter = QuestionActiveFilter.ALL).hasBankFilters())
        assertTrue(QuestionsState(searchQuery = "x").hasBankFilters())

        val pagina = QuestionsState(questions = UiAsync.Success(QuestionPage(emptyList(), total = 51, offset = 0, limit = 25)))
        assertEquals(51, pagina.totalQuestions())
        assertEquals(3, pagina.totalPages())
        assertEquals(1, QuestionsState(questions = UiAsync.Loading).totalPages())
    }

    /**
     * KQ0-5: al vincular sólo se ofrecen los exámenes que la base aceptaría —tipo
     * compatible, misma especialidad, no archivados— y cada uno dice si está
     * publicado o en borrador. Las ráfagas también: antes el catálogo no las traía.
     */
    @Test
    fun `al vincular solo se ofrecen examenes compatibles con su estado a la vista`() {
        val catalogo = UiAsync.Success(
            listOf(
                exam(id = "raf", code = "RAF-1").copy(typeExam = "rafaga", status = ExamStatus.PUBLISHED),
                exam(id = "raz", code = "RAZ-1").copy(typeExam = "razonado", status = ExamStatus.DRAFT),
                exam(id = "gui", code = "GUI-1").copy(typeExam = "guiado", status = ExamStatus.PUBLISHED),
                exam(id = "raz-otra", code = "RAZ-2").copy(typeExam = "razonado", specialtyId = "esp-2"),
                exam(id = "raz-arch", code = "RAZ-3").copy(typeExam = "razonado", status = ExamStatus.ARCHIVED),
                exam(id = "inm", code = "INM-1").copy(typeExam = "inmersivo"),
                exam(id = "raro", code = "RARO").copy(typeExam = "mixto"),
            ),
        )

        val razonada = questionLinkableExamOptions(catalogo, QuestionType.RAZONADA, specialtyId = "esp-1")
        assertEquals(listOf("raz", "gui"), razonada.map { it.value })
        assertEquals("RAZ-1 · TCAE · Simulacro marzo · Borrador", razonada.first().label)
        assertEquals("GUI-1 · TCAE · Simulacro marzo · Publicado", razonada.last().label)

        assertEquals(listOf("raf"), questionLinkableExamOptions(catalogo, QuestionType.RAFAGA, "esp-1").map { it.value })
        // En un alta la especialidad aún no existe: sólo filtra el tipo.
        assertEquals(
            listOf("raz", "gui", "raz-otra"),
            questionLinkableExamOptions(catalogo, QuestionType.RAZONADA, specialtyId = null).map { it.value },
        )
        // Un tipo sin identificar no se vincula a nada, y un examen raro no admite nada.
        assertTrue(questionLinkableExamOptions(catalogo, type = null, specialtyId = "esp-1").isEmpty())
        assertFalse(examAcceptsQuestionType(exam().copy(typeExam = "mixto"), QuestionType.RAFAGA))
    }

    /** KQ0-7: archivar dice lo que hace — sale de sus exámenes y del recuento — y cómo volver. */
    @Test
    fun `el aviso de archivar dice que sale de sus examenes y del recuento`() {
        assertEquals(
            "La pregunta deja de servirse: sale de los exámenes donde está vinculada y de su recuento. " +
                "Seguirá en el banco como inactiva y podrás reactivarla desde Clasificación.",
            QUESTION_ARCHIVE_WARNING,
        )
        assertFalse(QUESTION_ARCHIVE_WARNING.contains("Puede seguir apareciendo"), QUESTION_ARCHIVE_WARNING)
        assertEquals("La quita de sus exámenes y del recuento.", questionCapability("Archivar").note)
    }

    /** El filtro por examen cambia la FUENTE: un cero ahí no es «el banco está vacío». */
    @Test
    fun `filtrar por examen avisa de que no se ve el banco completo`() {
        val sinFiltro = QuestionsState()
        assertNull(questionScopeNote(sinFiltro, UiAsync.Success(listOf(exam()))))

        val conFiltro = QuestionsState(examFilter = "ex-1")
        val nota = questionScopeNote(conFiltro, UiAsync.Success(listOf(exam())))
        assertTrue(nota!!.contains("no el banco completo"), nota)
        assertTrue(nota.contains("EX-2026-0001"), nota)
    }

    @Test
    fun `el recuento no escribe plurales entre parentesis`() {
        assertEquals("0 preguntas", questionCountLabel(0))
        assertEquals("1 pregunta", questionCountLabel(1))
        assertEquals("248 preguntas", questionCountLabel(248))
    }

    /** Vacío por filtros y banco vacío son cosas distintas y se dicen distinto. */
    @Test
    fun `sin resultados por filtros no se confunde con un banco vacio`() {
        assertEquals("No hay preguntas que coincidan con estos filtros.", QUESTION_BANK_NO_MATCHES)
        assertTrue(QUESTION_BANK_EMPTY_MESSAGE.contains("no ha devuelto"))
    }

    // -----------------------------------------------------------------------
    // Editor: modo y guardado
    // -----------------------------------------------------------------------

    @Test
    fun `el editor distingue vacio, alta y pregunta abierta`() {
        assertEquals(QuestionEditorMode.EMPTY, questionEditorMode(QuestionsState()))
        assertEquals(QuestionEditorMode.NEW, questionEditorMode(QuestionsState(isCreating = true)))
        assertEquals(QuestionEditorMode.EXISTING, questionEditorMode(QuestionsState(selectedQuestionId = "q-1")))
    }

    @Test
    fun `una pregunta nueva se titula como tal y dice que el codigo llega al guardar`() {
        val nueva = QuestionsState(isCreating = true)
        assertEquals("Nueva pregunta", questionEditorTitle(nueva))
        assertEquals("El código se genera al guardar.", QUESTION_CODE_ON_CREATE)
    }

    @Test
    fun `un fallo al abrir la pregunta no se titula con un codigo inventado`() {
        val fallo = QuestionsState(selectedQuestionId = "q-1", detail = UiAsync.Error(AppError.Network("sin red")))
        assertEquals("No se ha podido abrir", questionEditorTitle(fallo))
    }

    @Test
    fun `la barra de guardado distingue limpio, sucio, guardando y error`() {
        val limpio = state()
        assertEquals(QuestionSaveTone.CLEAN, questionSaveBar(limpio).tone)
        assertFalse(questionSaveBar(limpio).primaryEnabled)
        assertFalse(questionSaveBar(limpio).showDiscard)

        val sucio = limpio.copy(dirty = true)
        assertEquals(QuestionSaveTone.DIRTY, questionSaveBar(sucio).tone)
        assertTrue(questionSaveBar(sucio).primaryEnabled)
        assertTrue(questionSaveBar(sucio).showDiscard)
        assertEquals("Guardar cambios", questionSaveBar(sucio).primaryLabel)

        assertEquals(QuestionSaveTone.SAVING, questionSaveBar(sucio.copy(saveState = UiAsync.Loading)).tone)

        val error = questionSaveBar(sucio.copy(saveState = UiAsync.Error(AppError.Validation("boom"))))
        assertEquals(QuestionSaveTone.ERROR, error.tone)
        assertEquals("boom", error.message)
        // Un rechazo del backend NUNCA se pinta como guardado.
        assertFalse(error.message.contains("Guardado"))
    }

    @Test
    fun `un alta pide crear y no se habilita sin examen inicial`() {
        val alta = QuestionsState(
            isCreating = true,
            draftStatement = "Enunciado nuevo",
            draftOptions = options(),
            optionsDirty = true,
            dirty = true,
        )
        val bar = questionSaveBar(alta)
        assertEquals("Crear pregunta", bar.primaryLabel)
        assertFalse(bar.primaryEnabled, "Sin especialidad no se puede crear (KQ2-4)")
        assertTrue(bar.message.contains("especialidad"), bar.message)

        // El examen inicial ya NO es obligatorio: la especialidad basta.
        val conEspecialidad = alta.copy(draftSpecialtyId = "esp-1")
        assertTrue(questionSaveBar(conEspecialidad).primaryEnabled)
        val soloExamen = alta.copy(draftCreateExamId = "ex-1")
        assertFalse(questionSaveBar(soloExamen).primaryEnabled, "el examen no sustituye a la especialidad")
    }

    // -----------------------------------------------------------------------
    // Respuestas
    // -----------------------------------------------------------------------

    @Test
    fun `las cuatro respuestas salen en orden y solo una es correcta`() {
        val rows = questionAnswerRows(state(correct = "C"))
        assertEquals(listOf("A", "B", "C", "D"), rows.map { it.label })
        assertEquals(listOf(false, false, true, false), rows.map { it.correct })
        assertEquals("C", questionCorrectAnswerLabel(state(correct = "C")))
        assertEquals("La respuesta correcta es la opción C.", questionCorrectAnswerNote(state(correct = "C")))
    }

    /** Sin exactamente una marcada no se inventa una: se pide marcarla. */
    @Test
    fun `sin una unica correcta se dice que hay que marcarla`() {
        val ninguna = state().copy(draftOptions = options().map { it.copy(isCorrect = false) })
        assertNull(questionCorrectAnswerLabel(ninguna))
        assertEquals("Marca cuál de las cuatro es la respuesta correcta.", questionCorrectAnswerNote(ninguna))

        val dos = state().copy(draftOptions = options().map { it.copy(isCorrect = it.label in listOf("A", "B")) })
        assertNull(questionCorrectAnswerLabel(dos))
    }

    @Test
    fun `una opcion vacia se marca como vacia`() {
        val rows = questionAnswerRows(state().copy(draftOptions = options(blank = true)))
        assertTrue(rows.all { it.empty })
        assertFalse(questionAnswerRows(state()).any { it.empty })
    }

    /** P0-5: el aviso dice la verdad concreta y no promete que editar opciones funcione. */
    @Test
    fun `el aviso de opciones no promete que el guardado vaya a funcionar`() {
        assertTrue(QUESTION_OPTIONS_RISK_NOTE.contains("puede rechazar"), QUESTION_OPTIONS_RISK_NOTE)
        assertFalse(QUESTION_OPTIONS_RISK_NOTE.lowercase().contains("se guardarán correctamente"))
    }

    // -----------------------------------------------------------------------
    // Explicación y clasificación
    // -----------------------------------------------------------------------

    @Test
    fun `una rafaga no pide explicacion y los otros dos tipos si`() {
        assertFalse(questionShowsExplanation(QuestionType.RAFAGA))
        assertTrue(questionShowsExplanation(QuestionType.RAZONADA))
        assertTrue(questionShowsExplanation(QuestionType.INMERSIVA))
    }

    @Test
    fun `la dificultad se lee y se dice que no hay tema ni especialidad`() {
        assertEquals("media", questionDifficultyLabel(state()))
        assertEquals("Sin definir", questionDifficultyLabel(state().copy(draftDifficulty = "")))
        assertTrue(QUESTION_NO_TOPIC_NOTE.contains("no incluye tema"), QUESTION_NO_TOPIC_NOTE)
    }

    @Test
    fun `el estado se dice con palabra`() {
        assertEquals("Activa", questionStatusLabel(true))
        assertEquals("Inactiva", questionStatusLabel(false))
    }

    // -----------------------------------------------------------------------
    // Relación con exámenes
    // -----------------------------------------------------------------------

    @Test
    fun `los vinculos son reales y una lista vacia dice exactamente eso`() {
        assertTrue(QUESTION_LINKS_EMPTY_NOTE.contains("ningún examen"), QUESTION_LINKS_EMPTY_NOTE)
        assertTrue(QUESTION_LINKS_UNAVAILABLE_NOTE.contains("No se han podido"), QUESTION_LINKS_UNAVAILABLE_NOTE)
        assertEquals("Posición 3", questionExamLinkPositionLabel(3))
        assertEquals("Publicado", questionExamLinkStatusLabel("publicado"))
        assertEquals("Borrador", questionExamLinkStatusLabel(""))
        assertTrue(QUESTION_UNLINK_PUBLISHED_WARNING.contains("despublica"), QUESTION_UNLINK_PUBLISHED_WARNING)
    }

    /**
     * KQ3-3: con exámenes vinculados (o con recursos, si es inmersiva) el tipo está
     * bloqueado y la tarjeta no elegida deja de ser seleccionable. En un alta, nunca.
     */
    @Test
    fun `el tipo se bloquea con vinculos o recursos y las tarjetas lo reflejan`() {
        val libre = QuestionsState(isCreating = false, examLinks = UiAsync.Success(emptyList()))
        assertNull(questionTypeLockReason(libre))
        assertTrue(questionTypeChoices(QuestionType.RAFAGA, locked = false).first { !it.selected }.selectable)

        val vinculada = libre.copy(
            examLinks = UiAsync.Success(
                listOf(QuestionExamLink(examId = "ex-1", examCode = "EX-1", examTitle = "T", position = 1, examType = "rafaga", status = "publicado")),
            ),
        )
        assertTrue(questionTypeLockReason(vinculada).orEmpty().contains("exámenes vinculados"))
        assertFalse(questionTypeChoices(QuestionType.RAFAGA, locked = true).first { !it.selected }.selectable)
        assertTrue(questionTypeChoices(QuestionType.RAFAGA, locked = true).first { it.selected }.selected)

        assertNull(questionTypeLockReason(vinculada.copy(isCreating = true)), "un alta nunca está bloqueada")
    }

    // -----------------------------------------------------------------------
    // Recursos: sólo Inmersiva, y nunca una ruta interna
    // -----------------------------------------------------------------------

    @Test
    fun `solo una inmersiva tiene recursos`() {
        assertFalse(questionShowsResources(QuestionType.RAFAGA))
        assertFalse(questionShowsResources(QuestionType.RAZONADA))
        assertTrue(questionShowsResources(QuestionType.INMERSIVA))
    }

    /**
     * `storagePath` es una ruta interna del almacenamiento. No sale ni en el
     * título, ni en el tipo, ni en la etiqueta de acceso.
     */
    @Test
    fun `la ruta interna de un recurso nunca se ensena`() {
        val recursos = listOf(
            QuestionResource(
                id = "r-1",
                questionId = "q-1",
                type = QuestionResourceType.AUDIO,
                title = "Audio del caso clínico",
                textBody = null,
                storagePath = "preguntas/q-1/audio-1a2b3c.mp3",
                publicUrl = "https://cdn.example.com/audio.mp3",
                mimeType = "audio/mpeg",
                sortOrder = 0,
            ),
        )
        val rows = questionResourceRows(recursos)
        val todoElTexto = rows.joinToString(" ") { "${it.title} ${it.typeLabel} ${it.accessLabel}" }
        assertFalse(todoElTexto.contains("preguntas/q-1"), todoElTexto)
        assertFalse(todoElTexto.contains(".mp3"), todoElTexto)
        assertFalse(todoElTexto.contains("r-1"), todoElTexto)
        assertEquals("Audio del caso clínico", rows.single().title)
        assertEquals("Audio", rows.single().typeLabel)
        assertTrue(rows.single().accessible)
    }

    @Test
    fun `un recurso sin direccion publica se dice, y uno de texto se sostiene solo`() {
        val sinUrl = QuestionResource(
            id = "r-2", questionId = "q-1", type = QuestionResourceType.IMAGE, title = "",
            textBody = null, storagePath = "p/x.png", publicUrl = null, mimeType = null, sortOrder = 1,
        )
        val texto = QuestionResource(
            id = "r-3", questionId = "q-1", type = QuestionResourceType.TEXT, title = "Enunciado largo",
            textBody = "Contenido del texto", storagePath = null, publicUrl = null, mimeType = null, sortOrder = 0,
        )
        val rows = questionResourceRows(listOf(sinUrl, texto))
        assertEquals(listOf("Enunciado largo", "Recurso sin título"), rows.map { it.title })
        assertEquals("Texto incluido", rows[0].accessLabel)
        assertTrue(rows[0].accessible)
        assertEquals("Sin dirección pública", rows[1].accessLabel)
        assertFalse(rows[1].accessible)
    }

    /** P0-4: subir recursos no funciona y la UI lo dice en vez de ofrecer un botón que mienta. */
    @Test
    fun `subir recursos se declara bloqueado`() {
        assertTrue(QUESTION_RESOURCE_UPLOAD_BLOCKED_NOTE.contains("todavía no está disponible"))
        assertEquals(
            QuestionCapabilityLevel.BLOCKED,
            questionCapability("Subir recursos").level,
        )
    }

    // -----------------------------------------------------------------------
    // Matriz de honestidad
    // -----------------------------------------------------------------------

    /**
     * La UI se pinta desde esta matriz, así que lo que declare tiene que
     * coincidir con lo que el repositorio ofrece de verdad.
     */
    @Test
    fun `la matriz declara bloqueado lo que el contrato no tiene`() {
        assertEquals(QuestionCapabilityLevel.BLOCKED, questionCapability("Filtrar por tema").level)
        assertEquals(QuestionCapabilityLevel.BLOCKED, questionCapability("Desvincular").level)
        assertEquals(QuestionCapabilityLevel.BLOCKED, questionCapability("Subir recursos").level)

        assertEquals(QuestionCapabilityLevel.REAL, questionCapability("Editar contenido").level)
        assertEquals(QuestionCapabilityLevel.REAL, questionCapability("Archivar").level)
        assertEquals(QuestionCapabilityLevel.REAL, questionCapability("Vincular a examen").level)
        assertEquals(QuestionCapabilityLevel.REAL, questionCapability("Ver recursos").level)

        assertEquals(QuestionCapabilityLevel.PARTIAL, questionCapability("Crear").level)
        assertEquals(QuestionCapabilityLevel.PARTIAL, questionCapability("Editar respuestas").level)

        // KQ0-4: buscar y filtrar ya son del servidor, paginados, y lo dicen.
        listOf("Buscar", "Filtrar por tipo", "Filtrar por estado", "Filtrar por examen", "Filtrar por especialidad").forEach { name ->
            val capability = questionCapability(name)
            assertEquals(QuestionCapabilityLevel.REAL, capability.level, name)
            assertTrue(capability.note.contains("En el servidor, paginado"), "$name: ${capability.note}")
            assertFalse(capability.note.contains("cargad"), "$name sigue hablando de lo cargado: ${capability.note}")
        }
    }

    @Test
    fun `toda capacidad declarada lleva su motivo`() {
        questionCapabilities().forEach { capability ->
            assertTrue(capability.note.isNotBlank(), "«${capability.name}» no dice por qué")
        }
    }
}
