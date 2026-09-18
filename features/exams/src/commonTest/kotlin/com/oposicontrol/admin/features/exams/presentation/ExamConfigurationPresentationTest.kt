package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pestaña Configuración afirma, comprobado sin pintar.
 *
 * Disponibilidad por tipo en tres estados, la compatibilidad D10 tal y como se lee
 * dentro de la pestaña, la duración sin prometer un límite (DEBT-034) y el número
 * de preguntas con la regla D1.
 */
class ExamConfigurationPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun caps(
        rafagas: Boolean = false,
        razonados: Boolean = false,
        inmersivos: Boolean = false,
        configured: Boolean = true,
        limitation: String? = null,
    ) = SpecialtyCapabilities(
        permiteRafagas = rafagas,
        permiteRazonados = razonados,
        permiteInmersivos = inmersivos,
        permiteDemos = false,
        permiteOposimarket = false,
        specialtyId = "esp-1",
        limitationMessage = limitation,
        configured = configured,
    )

    private fun state(
        type: String = "rafaga",
        specialty: String = "esp-1",
        capabilities: UiAsync<SpecialtyCapabilities> = UiAsync.Success(caps(rafagas = true, razonados = true, inmersivos = true)),
    ) = ExamsState(
        editorTypeExam = type,
        editorSpecialtyId = specialty,
        specialtyCapabilities = capabilities,
    )

    private val exam = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        title = "TCAE · Simulacro",
        description = "",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.DRAFT,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = 5,
        createdAt = now,
        updatedAt = now,
    )

    private fun existing(questions: UiAsync<List<com.oposicontrol.admin.domain.model.ExamQuestion>>) = ExamsState(
        catalogExams = listOf(exam),
        selectedExamId = exam.id,
        editorSpecialtyId = exam.specialtyId,
        editorTypeExam = exam.typeExam,
        questions = questions,
    )

    // -----------------------------------------------------------------------
    // Disponibilidad por tipo
    // -----------------------------------------------------------------------

    @Test
    fun `las tarjetas son los tres tipos canonicos y ninguno se llama guiado`() {
        val labels = examTypeChoices(state()).map { it.option.label }
        assertEquals(listOf("Ráfaga", "Razonado", "Inmersivo"), labels)
        assertFalse(labels.any { it.contains("guiad", ignoreCase = true) })
    }

    /**
     * Un examen nuevo puede elegir los tres tipos.
     *
     * Éste es el bug que lo motivó: sin especialidad las capacidades están en
     * `Idle`, los tres tipos salen `UNCHECKED` y `selectable` exigía `ALLOWED`, así
     * que no se podía pulsar ninguno. Como el borrador arranca en «rafaga», lo
     * único que existía en pantalla era Ráfaga.
     */
    @Test
    fun `sin especialidad los tres tipos estan sin comprobar y se pueden elegir`() {
        val choices = examTypeChoices(state(specialty = "", capabilities = UiAsync.Idle, type = "rafaga"))
            .associateBy { it.option }

        assertTrue(choices.values.all { it.permission == ExamTypePermission.UNCHECKED })
        // El elegido no se «vuelve a elegir»; los otros dos sí se pueden pulsar.
        assertFalse(choices.getValue(ExamTypeOption.RAFAGA).selectable)
        assertTrue(choices.getValue(ExamTypeOption.RAZONADO).selectable)
        assertTrue(choices.getValue(ExamTypeOption.INMERSIVO).selectable)
    }

    /** Elegir Razonado o Inmersivo sin especialidad los deja elegidos: nada los devuelve a Ráfaga. */
    @Test
    fun `un tipo elegido sin especialidad permanece elegido`() {
        listOf("razonado" to ExamTypeOption.RAZONADO, "inmersivo" to ExamTypeOption.INMERSIVO)
            .forEach { (wire, option) ->
                val choices = examTypeChoices(state(specialty = "", capabilities = UiAsync.Idle, type = wire))
                    .associateBy { it.option }
                assertTrue(choices.getValue(option).selected, "$wire no quedó elegido")
                assertFalse(choices.getValue(option).selectable, "El tipo ya elegido no se vuelve a pulsar")
            }
    }

    /**
     * «No lo sé» no es «no», y tampoco impide elegir.
     *
     * Con las capacidades cargando, vacías o caídas las tres tarjetas decían «no
     * permitido»: una decisión de la especialidad que nadie ha tomado. Ahora dicen
     * «sin comprobar» y se pueden pulsar. Lo que sí bloquea un tipo sin comprobar
     * es GUARDAR, y eso lo hace el ViewModel diciéndolo.
     */
    @Test
    fun `comprobando o con la consulta caida ningun tipo sale como no permitido y se pueden elegir`() {
        listOf(UiAsync.Loading, UiAsync.Empty, UiAsync.Error(AppError.Network("sin red"))).forEach { capabilities ->
            val choices = examTypeChoices(state(capabilities = capabilities, type = "rafaga"))
            assertTrue(
                choices.all { it.permission == ExamTypePermission.UNCHECKED },
                "Con capacidades $capabilities un tipo sale decidido: ${choices.map { it.permission }}",
            )
            assertEquals(
                2,
                choices.count { it.selectable },
                "Con capacidades $capabilities deberían poder elegirse los dos tipos no elegidos",
            )
        }
    }

    /** Unas capacidades que quedaron en memoria tras vaciar la especialidad no deciden nada. */
    @Test
    fun `capacidades antiguas sin especialidad no cuentan`() {
        val choices = examTypeChoices(state(specialty = "", capabilities = UiAsync.Success(caps(rafagas = true))))
        assertTrue(choices.all { it.permission == ExamTypePermission.UNCHECKED })
    }

    @Test
    fun `la especialidad decide que tipos se pueden elegir`() {
        val choices = examTypeChoices(state(capabilities = UiAsync.Success(caps(rafagas = true)))).associateBy { it.option }
        assertEquals(ExamTypePermission.ALLOWED, choices.getValue(ExamTypeOption.RAFAGA).permission)
        assertEquals(ExamTypePermission.NOT_ALLOWED, choices.getValue(ExamTypeOption.RAZONADO).permission)
        assertEquals(ExamTypePermission.NOT_ALLOWED, choices.getValue(ExamTypeOption.INMERSIVO).permission)
        // El elegido no se «vuelve a elegir», y uno prohibido no se pulsa.
        assertTrue(choices.getValue(ExamTypeOption.RAFAGA).selected)
        assertTrue(choices.values.none { it.selectable })
    }

    @Test
    fun `solo un tipo permitido y no elegido se puede pulsar`() {
        val choices = examTypeChoices(state(type = "rafaga", capabilities = UiAsync.Success(caps(rafagas = true, inmersivos = true))))
            .associateBy { it.option }
        assertFalse(choices.getValue(ExamTypeOption.RAFAGA).selectable)
        assertFalse(choices.getValue(ExamTypeOption.RAZONADO).selectable)
        assertTrue(choices.getValue(ExamTypeOption.INMERSIVO).selectable)
    }

    /** Un examen antiguo guardado como `guiado` ES un Razonado: su tarjeta sale marcada. */
    @Test
    fun `el alias legacy guiado marca la tarjeta de razonado`() {
        val elegido = examTypeChoices(state(type = "guiado")).single { it.selected }
        assertEquals(ExamTypeOption.RAZONADO, elegido.option)
    }

    @Test
    fun `un tipo desconocido no marca ninguna tarjeta`() {
        assertTrue(examTypeChoices(state(type = "mixto")).none { it.selected })
    }

    @Test
    fun `la palabra del permiso acompana siempre a la marca`() {
        assertEquals("Permitido", examTypePermissionLabel(ExamTypePermission.ALLOWED))
        assertEquals("No permitido", examTypePermissionLabel(ExamTypePermission.NOT_ALLOWED))
        assertEquals("Sin comprobar", examTypePermissionLabel(ExamTypePermission.UNCHECKED))
        val descripcion = examTypeCardDescription(examTypeChoices(state(capabilities = UiAsync.Loading)).first())
        assertTrue(descripcion.contains("Sin comprobar"), descripcion)
    }

    // -----------------------------------------------------------------------
    // Compatibilidad D10 dentro de la pestaña
    // -----------------------------------------------------------------------

    @Test
    fun `los seis casos de D10 llegan a la pestana con la etiqueta del resumen`() {
        val sinEspecialidad = examTypeCompatibilityPanel(state(specialty = "", capabilities = UiAsync.Idle))
        assertEquals(ExamTypeCompatibilityCase.NO_SPECIALTY, sinEspecialidad.case)
        assertEquals("Selecciona una especialidad para comprobar qué tipos admite.", sinEspecialidad.label)
        assertTrue(sinEspecialidad.asksForSpecialty)
        assertFalse(sinEspecialidad.canRetry)

        val comprobando = examTypeCompatibilityPanel(state(capabilities = UiAsync.Loading))
        assertEquals("Comprobando los tipos que permite esta especialidad…", comprobando.label)
        assertFalse(comprobando.canRetry)

        val permitido = examTypeCompatibilityPanel(state())
        assertEquals("Ráfaga está permitido en esta especialidad.", permitido.label)
        assertEquals(ExamCheckStatus.OK, permitido.status)

        val caidas = examTypeCompatibilityPanel(state(capabilities = UiAsync.Error(AppError.Network("sin red"))))
        assertEquals("No se han podido comprobar los tipos de esta especialidad.", caidas.label)
        assertTrue(caidas.canRetry)

        val desconocido = examTypeCompatibilityPanel(state(type = "mixto"))
        assertEquals("Tipo sin identificar", desconocido.label)
        assertEquals(ExamCheckStatus.BLOCKING, desconocido.status)

        val prohibido = examTypeCompatibilityPanel(state(type = "inmersivo", capabilities = UiAsync.Success(caps(rafagas = true))))
        assertEquals("Inmersivo no está permitido en esta especialidad.", prohibido.label)
        assertEquals(ExamCheckStatus.BLOCKING, prohibido.status)
    }

    /**
     * Las etiquetas son las del rail —no pueden discrepar—, pero dentro de
     * Configuración «Reintenta en Configuración» manda al sitio donde ya se está.
     */
    @Test
    fun `dentro de la pestana ningun detalle manda a configuracion`() {
        val casos = listOf(
            state(capabilities = UiAsync.Error(AppError.Network("sin red"))),
            state(type = "inmersivo", capabilities = UiAsync.Success(caps(rafagas = true))),
        )
        casos.forEach { estado ->
            val panel = examTypeCompatibilityPanel(estado)
            assertEquals(examTypeCompatibility(estado).label, panel.label)
            assertFalse(panel.detail.orEmpty().contains("en Configuración"), "Detalle deíctico: ${panel.detail}")
        }
        assertEquals(
            EXAM_TYPE_UNAVAILABLE_IN_TAB,
            examTypeCompatibilityPanel(state(capabilities = UiAsync.Error(AppError.Network("x")))).detail,
        )
        assertEquals(
            EXAM_TYPE_NOT_ALLOWED_IN_TAB,
            examTypeCompatibilityPanel(state(type = "inmersivo", capabilities = UiAsync.Success(caps(rafagas = true)))).detail,
        )
    }

    /** El `mensaje_limitacion` es de la especialidad y se dice UNA vez. */
    @Test
    fun `el mensaje de limitacion no se repite`() {
        val limitacion = "Esta especialidad todavía no tiene material razonado ni inmersivo cargado."
        val soloRafaga = UiAsync.Success(caps(rafagas = true, limitation = limitacion))

        // Elegido el permitido: el mensaje explica por qué las OTRAS están apagadas.
        val conRafaga = examTypeCompatibilityPanel(state(type = "rafaga", capabilities = soloRafaga))
        assertNull(conRafaga.detail)
        assertEquals(limitacion, conRafaga.note)

        // Elegido el prohibido: el mensaje es el porqué del veredicto, y no sale dos veces.
        val conInmersivo = examTypeCompatibilityPanel(state(type = "inmersivo", capabilities = soloRafaga))
        assertEquals(limitacion, conInmersivo.detail)
        assertNull(conInmersivo.note)
    }

    @Test
    fun `una especialidad sin configurar lo dice una vez y no como limitacion`() {
        val panel = examTypeCompatibilityPanel(
            state(capabilities = UiAsync.Success(caps(rafagas = true, configured = false, limitation = "x"))),
        )
        assertEquals(
            "Esta especialidad usa la configuración por defecto: nadie ha decidido todavía sus tipos.",
            panel.detail,
        )
        assertNull(panel.note)
    }

    // -----------------------------------------------------------------------
    // Duración (DEBT-034)
    // -----------------------------------------------------------------------

    @Test
    fun `la duracion vacia dice sin duracion y no promete un limite`() {
        assertEquals("Sin duración", EXAM_DURATION_PLACEHOLDER)
        assertEquals(EXAM_NO_DURATION, EXAM_DURATION_PLACEHOLDER)
        listOf(EXAM_DURATION_PLACEHOLDER, examDurationSupport(""), examDurationSupport("45")).forEach { texto ->
            assertFalse(texto.contains("límite", ignoreCase = true), "Promete un límite: «$texto»")
        }
    }

    @Test
    fun `la duracion avisa donde se escribe`() {
        assertEquals(EXAM_DURATION_OPTIONAL, examDurationSupport(""))
        assertEquals(EXAM_DURATION_OPTIONAL, examDurationSupport("  45 "))
        assertEquals(EXAM_ZERO_DURATION_WARNING, examDurationSupport("00"))
        assertEquals("Escribe los minutos con cifras.", examDurationSupport("una hora"))
        assertEquals("Escribe un número de minutos mayor que cero.", examDurationSupport("-5"))
    }

    // -----------------------------------------------------------------------
    // Número de preguntas (D1)
    // -----------------------------------------------------------------------

    @Test
    fun `el numero de preguntas de un examen nuevo es cero y dice cuando se vinculan`() {
        val nuevo = ExamsState(questions = UiAsync.Empty)
        val campo = examQuestionCountField(nuevo)
        assertEquals("0", campo.text)
        assertEquals(ExamFieldOrigin.VALUE, campo.origin)
        assertEquals("Las preguntas se vinculan después de crear el examen.", examQuestionCountSupport(nuevo, campo))
    }

    /** K2-2: la cifra servida mientras se cuenta es un dato (M1), con el apoyo de un dato. */
    @Test
    fun `mientras se cuenta la cifra servida es un dato`() {
        val contando = existing(UiAsync.Loading)
        val campo = examQuestionCountField(contando)
        assertEquals("5", campo.text)
        assertEquals(ExamFieldOrigin.VALUE, campo.origin)
        assertEquals("Sale de las preguntas vinculadas; no se escribe a mano.", examQuestionCountSupport(contando, campo))
    }

    @Test
    fun `un fallo al contar no ensena ninguna cifra`() {
        val fallo = existing(UiAsync.Error(AppError.Network("sin red")))
        val campo = examQuestionCountField(fallo)
        assertEquals("Sin contar", campo.text)
        assertEquals(ExamFieldOrigin.UNAVAILABLE, campo.origin)
        assertEquals("No se ha podido contar las preguntas de este examen.", examQuestionCountSupport(fallo, campo))
    }

    @Test
    fun `un recuento cargado es un dato`() {
        val cargado = existing(UiAsync.Success(emptyList()))
        val campo = examQuestionCountField(cargado)
        assertEquals("0", campo.text)
        assertEquals(ExamFieldOrigin.VALUE, campo.origin)
        assertEquals("Sale de las preguntas vinculadas; no se escribe a mano.", examQuestionCountSupport(cargado, campo))
    }
}
