package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pestaña Publicación afirma, comprobado sin pintar: estado → acción,
 * confirmaciones, avisos (orientación, no validación) y resultado.
 */
class ExamPublicationPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun exam(status: ExamStatus) = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        title = "TCAE · Simulacro",
        description = "Simulacro.",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = status,
        publishFrom = null,
        publishedAt = if (status == ExamStatus.PUBLISHED) now else null,
        sortOrder = 0,
        questionCount = 1,
        durationMinutes = 45,
        year = 2026,
        createdAt = now,
        updatedAt = now,
    )

    private val oneQuestion = ExamQuestion(
        id = "q-1",
        examId = "ex-1",
        title = "Pregunta",
        statement = "Pregunta",
        sortOrder = 0,
        options = emptyList(),
        resources = emptyList(),
        createdAt = now,
        updatedAt = now,
    )

    /** Un examen abierto y limpio, como lo deja `selectExam`, con una pregunta y su tipo permitido. */
    private fun open(exam: Exam, questions: UiAsync<List<ExamQuestion>> = UiAsync.Success(listOf(oneQuestion))) = ExamsState(
        catalogExams = listOf(exam),
        exams = UiAsync.Success(listOf(exam)),
        selectedExamId = exam.id,
        questions = questions,
        specialtyCapabilities = UiAsync.Success(
            SpecialtyCapabilities(
                permiteRafagas = true,
                permiteRazonados = true,
                permiteInmersivos = true,
                permiteDemos = false,
                permiteOposimarket = false,
                specialtyId = "esp-1",
            ),
        ),
        editorTitle = exam.title,
        editorDescription = exam.description,
        editorCode = exam.code,
        editorSpecialtyId = exam.specialtyId,
        editorCommunityId = exam.communityId,
        editorTypeExam = exam.typeExam,
        editorStatus = exam.status.name.lowercase(),
        editorTypeAccess = exam.typeAccess,
        editorPriceTickets = exam.priceTickets.toString(),
        editorYear = exam.year?.toString().orEmpty(),
        editorDurationMinutes = exam.durationMinutes?.toString().orEmpty(),
        editorIsOfficial = exam.isOfficial,
    )

    private fun count(state: ExamsState, exam: Exam?) = linkedQuestionCount(state.questions, exam)

    // -----------------------------------------------------------------------
    // Estado → acción
    // -----------------------------------------------------------------------

    @Test
    fun `cada estado ofrece solo las acciones que existen`() {
        assertEquals(emptyList(), examPublicationActions(null))
        assertEquals(
            listOf(ExamPublicationAction.PUBLISH, ExamPublicationAction.ARCHIVE, ExamPublicationAction.DELETE),
            examPublicationActions(ExamStatus.DRAFT),
        )
        assertEquals(
            listOf(ExamPublicationAction.UNPUBLISH, ExamPublicationAction.ARCHIVE, ExamPublicationAction.DELETE),
            examPublicationActions(ExamStatus.PUBLISHED),
        )
        // Archivado: ni archivar otra vez ni despublicar. Publicar lo recupera, y
        // borrar sigue estando — es justo donde más falta hace, porque un examen
        // archivado es lo que alguien quiso quitar de en medio.
        assertEquals(
            listOf(ExamPublicationAction.PUBLISH, ExamPublicationAction.DELETE),
            examPublicationActions(ExamStatus.ARCHIVED),
        )
    }

    /**
     * **Archivar y eliminar son dos acciones, y ninguna de las dos puede leerse
     * como la otra.**
     *
     * La consola ofrecía una sola, llamada «Eliminar», que archivaba: el examen
     * seguía en la base y no había forma de sacarlo del catálogo real. Ahora el
     * botón que dice «Archivar» archiva y el que dice «Eliminar» borra, y este
     * caso fija que sus textos lo digan — que es lo único que ve quien pulsa.
     */
    @Test
    fun `archivar y eliminar no se pueden confundir por su texto`() {
        val archivar = examPublicationActionCopy(ExamPublicationAction.ARCHIVE, ExamStatus.DRAFT)
        val eliminar = examPublicationActionCopy(ExamPublicationAction.DELETE, ExamStatus.DRAFT)

        assertEquals("Archivar", archivar.label)
        assertEquals(EXAM_DELETE_ACTION_LABEL, eliminar.label)
        assertTrue(eliminar.label.contains("Eliminar"), eliminar.label)
        // Lo irreversible se dice en la explicación que va al lado del botón, no
        // sólo en el diálogo: el diálogo llega cuando ya has decidido pulsar.
        assertTrue(
            eliminar.explanation.contains("no se puede deshacer", ignoreCase = true),
            eliminar.explanation,
        )
        // Y dice la otra mitad de la verdad: que puede no poder borrarse.
        assertTrue(eliminar.explanation.contains("usuarios", ignoreCase = true), eliminar.explanation)
        assertFalse(
            archivar.explanation.contains("no se puede deshacer", ignoreCase = true),
            "archivar es reversible y no puede asustar como si no lo fuera: ${archivar.explanation}",
        )
    }

    /** Las dos destructivas confirman con el diálogo del ViewModel, no aquí. */
    @Test
    fun `eliminar confirma con el dialogo del viewmodel igual que archivar`() {
        val publicado = exam(ExamStatus.PUBLISHED)
        assertNull(examPublicationConfirmation(ExamPublicationAction.DELETE, open(publicado), publicado, 1))
    }

    /**
     * Las dos confirmaciones dicen lo que pasa DE VERDAD, y la del borrado es más
     * seria que la de archivar: nombra lo que se pierde y lo que no se puede
     * deshacer.
     */
    @Test
    fun `la confirmacion de borrar es mas seria que la de archivar`() {
        assertTrue(EXAM_ARCHIVE_WARNING.contains("dejará de estar disponible"), EXAM_ARCHIVE_WARNING)
        assertTrue(
            EXAM_ARCHIVE_WARNING.contains("preguntas asociadas no se eliminan"),
            EXAM_ARCHIVE_WARNING,
        )

        assertTrue(EXAM_DELETE_FOREVER_WARNING.contains("no se puede deshacer"), EXAM_DELETE_FOREVER_WARNING)
        // Y dice qué se lleva por delante y qué no: el banco de preguntas se
        // queda. Prometer que borra las preguntas sería mentir — `preguntas_examen`
        // es una M:N y sólo se borra la asociación.
        assertTrue(EXAM_DELETE_FOREVER_WARNING.contains("banco"), EXAM_DELETE_FOREVER_WARNING)
        // Ni una palabra sobre archivar dentro del aviso de borrar: son dos
        // botones distintos, y el diálogo no es el sitio para ofrecer el otro.
        assertTrue(
            EXAM_DELETE_FOREVER_WARNING.length > EXAM_ARCHIVE_WARNING.length / 2,
            "el aviso del borrado no puede ser más escueto que el de archivar",
        )
    }

    // -----------------------------------------------------------------------
    // El rechazo por datos de usuario: aviso de la ACCIÓN, no error de campo
    // -----------------------------------------------------------------------

    /**
     * `EXAM_HAS_USER_DATA` no tiene campo culpable, y por eso NO se atribuye a
     * ninguno.
     *
     * `examFieldError` existe para señalar el campo del formulario que provocó un
     * rechazo del guardado. Aquí no hay ninguno: el examen está perfectamente
     * guardado y lo que no se puede es borrarlo. Atribuirlo a un campo —o dejar
     * que la barra de guardado hable de un guardado que nadie pidió— sería mandar
     * al administrador a corregir algo que no está mal.
     */
    @Test
    fun `el rechazo por datos de usuario no senala ningun campo del formulario`() {
        val estado = open(exam(ExamStatus.PUBLISHED)).copy(
            actionState = UiAsync.Error(
                AppError.Validation(message = "No se puede borrar…", code = EXAM_ERROR_HAS_USER_DATA),
            ),
        )

        assertNull(examFieldError(estado), "no hay campo culpable en un borrado rechazado")
        assertNull(examFieldErrorTab(estado), "y por tanto tampoco se cambia de pestaña")
    }

    /** Se presenta como aviso de la acción, con el mensaje que redactó el backend. */
    @Test
    fun `el rechazo por datos de usuario se presenta como aviso de la accion`() {
        val delBackend = "No se puede borrar este examen porque los usuarios ya lo han usado: hay intentos de usuarios."
        val estado = open(exam(ExamStatus.PUBLISHED)).copy(
            examDeleteState = UiAsync.Error(
                AppError.Validation(message = delBackend, code = EXAM_ERROR_HAS_USER_DATA),
            ),
        )

        val aviso = assertNotNull(examDeletionNotice(estado))
        assertEquals(ExamDeletionNoticeKind.BLOCKED, aviso.kind)
        assertEquals(delBackend, aviso.detail, "el mensaje lo redacta el backend y aquí no se reescribe")
        assertEquals(ExamCheckStatus.BLOCKING, examDeletionNoticeStatus(aviso.kind))
    }

    /**
     * «No se puede» y «no ha podido comprobarse» no son lo mismo, y el aviso los
     * distingue por el código y no por el texto: el primero es definitivo y el
     * segundo se reintenta.
     */
    @Test
    fun `el aviso distingue un bloqueo definitivo de un fallo que se reintenta`() {
        val abierto = open(exam(ExamStatus.DRAFT))

        val bloqueado = assertNotNull(
            examDeletionNotice(
                abierto.copy(
                    examDeleteState = UiAsync.Error(AppError.Validation("x", EXAM_ERROR_HAS_USER_DATA)),
                ),
            ),
        )
        val sinComprobar = assertNotNull(
            examDeletionNotice(
                abierto.copy(
                    examDeleteState = UiAsync.Error(
                        AppError.Validation("y", EXAM_ERROR_DELETE_GUARD_UNAVAILABLE),
                    ),
                ),
            ),
        )
        val caido = assertNotNull(
            examDeletionNotice(abierto.copy(examDeleteState = UiAsync.Error(AppError.Network("timeout")))),
        )

        assertEquals(ExamDeletionNoticeKind.BLOCKED, bloqueado.kind)
        assertEquals(ExamDeletionNoticeKind.FAILED, sinComprobar.kind)
        assertEquals(ExamDeletionNoticeKind.FAILED, caido.kind)
        assertTrue(bloqueado.title != sinComprobar.title, "los dos títulos no pueden ser el mismo")
    }

    @Test
    fun `mientras borra lo dice y cuando no hay nada que decir no dice nada`() {
        val abierto = open(exam(ExamStatus.DRAFT))

        assertEquals(
            ExamDeletionNoticeKind.WORKING,
            examDeletionNotice(abierto.copy(examDeleteState = UiAsync.Loading))?.kind,
        )
        assertEquals(ExamCheckStatus.UNKNOWN, examDeletionNoticeStatus(ExamDeletionNoticeKind.WORKING))
        assertNull(examDeletionNotice(abierto), "sin acción no hay aviso")
        // Un borrado que sale bien deja el editor vacío: no hay examen del que
        // avisar, y un «Examen borrado.» junto a un formulario en blanco
        // describiría algo que ya no está en pantalla.
        assertNull(examDeletionNotice(abierto.copy(examDeleteState = UiAsync.Success(Unit))))
    }

    /**
     * **El código del backend llega en MINÚSCULAS.**
     *
     * `Application.kt` responde `ErrorDto(error = cause.errorCode.lowercase())`, y
     * `examFieldError` comparaba contra las constantes en MAYÚSCULAS: la
     * atribución de campo no se disparaba nunca contra el servidor real, sólo en
     * los tests, que construyen el `AppError` a mano con la constante. Este caso
     * entra por donde entra la respuesta de verdad.
     */
    @Test
    fun `un codigo en minusculas se atribuye igual que en mayusculas`() {
        val estado = open(exam(ExamStatus.DRAFT)).copy(
            actionState = UiAsync.Error(
                AppError.Validation(message = "Ya existe otro examen con ese código.", code = "exam_code_duplicate"),
            ),
        )

        val señalado = assertNotNull(examFieldError(estado), "el código en minúsculas no señaló el campo")
        assertEquals(ExamField.CODE, señalado.field)
    }

    /** `publicarExamen` pone `activo=true`: publicar un archivado lo reactiva, y se dice. */
    @Test
    fun `publicar un archivado dice que lo reactiva y que no vuelve a borrador`() {
        val copy = examPublicationActionCopy(ExamPublicationAction.PUBLISH, ExamStatus.ARCHIVED)
        assertEquals("Publicar ahora", copy.label)
        assertTrue(copy.explanation.contains("reactiva"), copy.explanation)
        assertTrue(copy.explanation.contains("no puede volver a borrador"), copy.explanation)
    }

    @Test
    fun `las etiquetas son las de las acciones reales y ninguna programa ni previsualiza`() {
        val textos = ExamStatus.entries.flatMap { status ->
            examPublicationActions(status).map { examPublicationActionCopy(it, status) }
        }
        assertEquals(
            setOf("Publicar ahora", "Despublicar", "Archivar", EXAM_DELETE_ACTION_LABEL),
            textos.map { it.label }.toSet(),
        )
        (textos.flatMap { listOf(it.label, it.explanation) } + EXAM_PUBLICATION_NEEDS_SAVE).forEach { texto ->
            val t = texto.lowercase()
            listOf("program", "vista previa", "previsualiz", "en revisión").forEach { prohibido ->
                assertFalse(t.contains(prohibido), "«$texto» promete «$prohibido»")
            }
        }
        assertTrue(EXAM_NO_SCHEDULED_PUBLICATION.contains("No hay publicación programada"))
    }

    @Test
    fun `nada se lanza mientras otra accion esta en curso`() {
        val borrador = open(exam(ExamStatus.DRAFT))
        assertFalse(examPublicationBusy(borrador))
        assertTrue(examPublicationBusy(borrador.copy(lifecycleActionState = UiAsync.Loading)))
        assertTrue(examPublicationBusy(borrador.copy(actionState = UiAsync.Loading)))
    }

    // -----------------------------------------------------------------------
    // Confirmación
    // -----------------------------------------------------------------------

    @Test
    fun `publicar confirma y avisa de la version guardada solo si hay cambios`() {
        val examen = exam(ExamStatus.DRAFT)
        val limpio = open(examen)
        val confirmacion = assertNotNull(examPublicationConfirmation(ExamPublicationAction.PUBLISH, limpio, examen, count(limpio, examen)))
        assertEquals("Publicar examen", confirmacion.title)
        // El botón del diálogo no repite el del formulario.
        assertEquals("Publicar", confirmacion.confirmLabel)
        assertFalse(confirmacion.message.contains("versión guardada"))

        val sucio = limpio.copy(editorTitle = "TCAE · Simulacro revisado")
        val conCambios = assertNotNull(examPublicationConfirmation(ExamPublicationAction.PUBLISH, sucio, examen, count(sucio, examen)))
        assertTrue(conCambios.message.contains("Se publica la versión guardada"), conCambios.message)
    }

    /**
     * Con avisos, la confirmación dice que son orientación y cuál es la ÚNICA regla
     * del servidor (K-DEC01). Antes decía «el servidor no los exige», que dejó de
     * ser verdad para las preguntas. El caso usa una lista que no se ha podido
     * contar: es el único con avisos en el que publicar sigue ofreciéndose.
     */
    @Test
    fun `publicar con avisos dice cual es la unica regla del servidor`() {
        val examen = exam(ExamStatus.DRAFT)
        val sinContar = open(examen, questions = UiAsync.Error(AppError.Network("timeout")))
        val confirmacion = assertNotNull(
            examPublicationConfirmation(ExamPublicationAction.PUBLISH, sinContar, examen, count(sinContar, examen)),
        )
        assertTrue(confirmacion.message.contains(EXAM_SERVER_PUBLISH_RULE), confirmacion.message)
        assertFalse(confirmacion.message.contains("no los exige"), confirmacion.message)
    }

    /**
     * K-DEC01: con la lista CARGADA y vacía, publicar se apaga y la explicación del
     * botón es la regla del servidor. Mientras se cuenta o si no se pudo contar, no
     * se apaga: no se sabe, y el servidor es la guarda final. Mutaciones: devolver
     * `false` siempre, o apagarlo también con `Loading`/`Error`, ponen este test en rojo.
     */
    @Test
    fun `publicar se apaga solo con la lista cargada y vacia`() {
        val borrador = exam(ExamStatus.DRAFT)
        assertTrue(examPublishBlockedByQuestions(open(borrador, questions = UiAsync.Empty)))
        assertTrue(examPublishBlockedByQuestions(open(borrador, questions = UiAsync.Success(emptyList()))))
        assertFalse(examPublishBlockedByQuestions(open(borrador)))
        assertFalse(examPublishBlockedByQuestions(open(borrador, questions = UiAsync.Loading)))
        assertFalse(examPublishBlockedByQuestions(open(borrador, questions = UiAsync.Error(AppError.Network("boom")))))
        // Un examen que no existe no se publica por otra razón (hay que guardarlo).
        assertFalse(examPublishBlockedByQuestions(ExamsState(selectedExamId = null, questions = UiAsync.Empty)))

        listOf(ExamStatus.DRAFT, ExamStatus.ARCHIVED).forEach { status ->
            val vetado = examPublicationActionCopy(ExamPublicationAction.PUBLISH, status, questionsMissing = true)
            assertEquals("Publicar ahora", vetado.label)
            assertEquals(EXAM_PUBLISH_REQUIRES_QUESTIONS_EXPLANATION, vetado.explanation)
        }
        // El veto sólo cambia la explicación de publicar.
        assertEquals(
            examPublicationActionCopy(ExamPublicationAction.ARCHIVE, ExamStatus.DRAFT),
            examPublicationActionCopy(ExamPublicationAction.ARCHIVE, ExamStatus.DRAFT, questionsMissing = true),
        )
    }

    @Test
    fun `reactivar despublicar y archivar confirman lo que hacen`() {
        val archivado = exam(ExamStatus.ARCHIVED)
        assertEquals(
            "Reactivar y publicar",
            examPublicationConfirmation(ExamPublicationAction.PUBLISH, open(archivado), archivado, 1)?.title,
        )
        val publicado = exam(ExamStatus.PUBLISHED)
        val despublicar = assertNotNull(examPublicationConfirmation(ExamPublicationAction.UNPUBLISH, open(publicado), publicado, 1))
        assertTrue(despublicar.message.contains("vuelve a borrador"))
        // Archivar confirma con el diálogo del ViewModel.
        assertNull(examPublicationConfirmation(ExamPublicationAction.ARCHIVE, open(publicado), publicado, 1))
    }

    // -----------------------------------------------------------------------
    // Avisos: orientación, no validación
    // -----------------------------------------------------------------------

    @Test
    fun `con cambios sin guardar se avisa de que publicar publica lo guardado`() {
        val examen = exam(ExamStatus.DRAFT)
        val sucio = open(examen).copy(editorDurationMinutes = "90")
        val avisos = examPublicationWarnings(sucio, examen, count(sucio, examen))
        assertEquals(EXAM_PENDING_TITLE, avisos.title)
        assertEquals(EXAM_PUBLISH_SAVED_VERSION_NOTE, avisos.savedVersionNote)
        // «Cambios sin guardar» se dice UNA vez: con la nota, no también como fila.
        assertTrue(avisos.checks.none { it.id == ExamCheckId.UNSAVED_CHANGES })
    }

    @Test
    fun `en un publicado guardar cambia lo publicado y se dice`() {
        val examen = exam(ExamStatus.PUBLISHED)
        val sucio = open(examen).copy(editorDurationMinutes = "90")
        val avisos = examPublicationWarnings(sucio, examen, count(sucio, examen))
        assertEquals("Avisos", avisos.title)
        assertEquals(EXAM_PUBLISHED_SAVE_NOTE, avisos.savedVersionNote)
    }

    /**
     * Las filas son las del rail, y su nota dice que son orientación salvo la única
     * regla del servidor: tener preguntas (K-DEC01).
     */
    @Test
    fun `antes de publicar es la misma lista que el resumen y se presenta como orientacion`() {
        val examen = exam(ExamStatus.DRAFT)
        val sinPreguntas = open(examen, questions = UiAsync.Empty)
        val avisos = examPublicationWarnings(sinPreguntas, examen, count(sinPreguntas, examen))
        val delResumen = examPublishPendingChecks(examChecks(sinPreguntas, examen, count(sinPreguntas, examen)))
        assertEquals(delResumen.filter { it.id != ExamCheckId.UNSAVED_CHANGES }, avisos.checks)
        val preguntas = avisos.checks.single { it.id == ExamCheckId.LINKED_QUESTIONS }
        assertEquals(ExamCheckStatus.BLOCKING, preguntas.status)
        assertEquals(EXAM_PUBLISH_ORIENTATION, avisos.orientationNote)
        assertTrue(EXAM_PUBLISH_ORIENTATION.contains("al menos una pregunta activa"), EXAM_PUBLISH_ORIENTATION)
        assertFalse(EXAM_PUBLISH_ORIENTATION.contains("no exige nada"), EXAM_PUBLISH_ORIENTATION)
    }

    /** Con el rail delante, la lista vive sólo en el rail; sin rail, en la pestaña. */
    @Test
    fun `antes de publicar solo se pinta en la pestana cuando el rail no esta`() {
        assertFalse(examPublicationShowsChecklist(railVisible = true))
        assertTrue(examPublicationShowsChecklist(railVisible = false))
    }

    /**
     * Ocultarla en WIDE no pierde información: el rail enseña las mismas filas y
     * además «Cambios sin guardar», y la confirmación conserva las dos notas que
     * la pestaña dejaba de escribir.
     */
    @Test
    fun `lo que la pestana deja de enseñar con rail sigue estando en el rail y en la confirmacion`() {
        val examen = exam(ExamStatus.DRAFT)
        val sucioSinPreguntas = open(examen, questions = UiAsync.Empty).copy(editorDurationMinutes = "90")
        val n = count(sucioSinPreguntas, examen)

        val enPestana = examPublicationWarnings(sucioSinPreguntas, examen, n)
        val enRail = examPublishPendingChecks(examChecks(sucioSinPreguntas, examen, n))
        assertTrue(enRail.containsAll(enPestana.checks), "El rail no enseña todo lo que enseñaba la pestaña")
        assertTrue(
            enRail.any { it.id == ExamCheckId.UNSAVED_CHANGES },
            "El rail no dice «Cambios sin guardar», que en la pestaña era una nota aparte",
        )

        val confirmacion = assertNotNull(
            examPublicationConfirmation(ExamPublicationAction.PUBLISH, sucioSinPreguntas, examen, n),
        )
        assertTrue(confirmacion.message.contains("Se publica la versión guardada"), confirmacion.message)
        assertTrue(confirmacion.message.contains(EXAM_SERVER_PUBLISH_RULE), confirmacion.message)
    }

    @Test
    fun `sin nada pendiente no hay avisos y no se dice configuracion valida`() {
        val examen = exam(ExamStatus.DRAFT)
        val limpio = open(examen)
        val avisos = examPublicationWarnings(limpio, examen, count(limpio, examen))
        assertTrue(avisos.isEmpty, "Avisos inesperados: $avisos")
        assertNull(avisos.orientationNote)
    }

    // -----------------------------------------------------------------------
    // Estado actual
    // -----------------------------------------------------------------------

    @Test
    fun `la fecha de publicacion es un dato solo si existe`() {
        val publicado = examPublicationFacts(exam(ExamStatus.PUBLISHED))
        assertEquals(instantLabel(now), publicado.publishedAt.text)
        assertEquals(ExamFieldOrigin.VALUE, publicado.publishedAt.origin)

        val borrador = examPublicationFacts(exam(ExamStatus.DRAFT))
        assertEquals("Sin publicar", borrador.publishedAt.text)
        assertEquals(ExamFieldOrigin.ABSENT, borrador.publishedAt.origin)
    }

    // -----------------------------------------------------------------------
    // Resultado
    // -----------------------------------------------------------------------

    @Test
    fun `sin accion no hay resultado y un examen nuevo tampoco lo tiene`() {
        val examen = exam(ExamStatus.DRAFT)
        assertNull(examPublicationResult(open(examen), examen))
        assertNull(examPublicationResult(ExamsState(lifecycleActionState = UiAsync.Success(Unit)), null))
    }

    @Test
    fun `el resultado dice que accion se esta haciendo y como ha acabado`() {
        val borrador = exam(ExamStatus.DRAFT)
        val publicado = exam(ExamStatus.PUBLISHED)

        assertEquals("Publicando…", examPublicationResult(open(borrador).copy(lifecycleActionState = UiAsync.Loading), borrador)?.title)
        assertEquals("Despublicando…", examPublicationResult(open(publicado).copy(lifecycleActionState = UiAsync.Loading), publicado)?.title)

        val hecho = examPublicationResult(open(publicado).copy(lifecycleActionState = UiAsync.Success(Unit)), publicado)
        assertEquals(ExamPublicationResultKind.SUCCESS, hecho?.kind)
        assertEquals("Examen publicado.", hecho?.title)
        assertEquals(
            "Examen despublicado.",
            examPublicationResult(open(borrador).copy(lifecycleActionState = UiAsync.Success(Unit)), borrador)?.title,
        )
    }

    @Test
    fun `un fallo lleva el mensaje humano existente`() {
        val borrador = exam(ExamStatus.DRAFT)
        val error = AppError.Network("timeout")
        val fallo = assertNotNull(examPublicationResult(open(borrador).copy(lifecycleActionState = UiAsync.Error(error)), borrador))
        assertEquals(ExamPublicationResultKind.ERROR, fallo.kind)
        assertEquals("No se ha podido publicar.", fallo.title)
        assertEquals(error.humanMessage(), fallo.detail)
        assertEquals(ExamCheckStatus.BLOCKING, examPublicationResultStatus(fallo.kind))
    }
}
