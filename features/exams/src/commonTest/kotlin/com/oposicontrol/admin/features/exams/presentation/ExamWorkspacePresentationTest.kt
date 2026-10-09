package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ExamCoverResolver
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que este rediseño AFIRMA sobre el dominio, comprobado mecánicamente.
 *
 * Cinco de los doce elementos de la maqueta de referencia no tenían respaldo
 * real, y la forma de que no vuelvan a colarse no es acordarse de ellos: es que
 * un test falle si alguien los reintroduce. Cada bloque de abajo corresponde a
 * una de esas afirmaciones.
 */
class ExamWorkspacePresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    // -----------------------------------------------------------------------
    // Vocabulario
    // -----------------------------------------------------------------------

    @Test
    fun `los tres tipos son rafaga razonado e inmersivo`() {
        assertEquals(
            listOf("Ráfaga", "Razonado", "Inmersivo"),
            ExamTypeOption.entries.map { it.label },
        )
        assertEquals(
            listOf("rafaga", "razonado", "inmersivo"),
            ExamTypeOption.entries.map { it.wireValue },
        )
    }

    /**
     * «Tipo test» y «Guiado» no son NOMBRES de tipo.
     *
     * La distinción importa y por eso el test la respeta: la descripción de
     * Ráfaga dice —correctamente— «preguntas tipo test encadenadas», porque eso
     * es lo que son. Lo que está prohibido es que «Tipo test» aparezca como
     * etiqueta de un tipo, que es como lo pintaba la maqueta de referencia.
     * «Guiado» es el alias legacy de la base: se lee, nunca se muestra.
     */
    @Test
    fun `ni tipo test ni guiado son nombres de tipo`() {
        val labels = ExamTypeOption.entries.map { it.label.lowercase() }
        listOf("tipo test", "guiado", "guiada").forEach { prohibido ->
            assertFalse(
                labels.any { it.contains(prohibido) },
                "«$prohibido» no puede ser la etiqueta de un tipo: $labels",
            )
        }

        val vocabulary = ExamTypeOption.entries
            .joinToString(" ") { "${it.label} ${it.description}" }
            .lowercase()
        listOf("guiado", "guiada").forEach { prohibido ->
            assertFalse(
                vocabulary.contains(prohibido),
                "El vocabulario de tipo no puede contener «$prohibido»: $vocabulary",
            )
        }
    }

    // Las descripciones de la referencia estaban inventadas: «preguntas sucesivas
    // sin regreso» describe una restricción de navegación que `responder` no
    // impone. Si alguien la reintroduce, este test cae.
    @Test
    fun `ninguna descripcion promete restricciones de navegacion que no existen`() {
        val descriptions = ExamTypeOption.entries.joinToString(" ") { it.description }.lowercase()
        listOf("sin regreso", "sucesiva", "no se puede volver", "sin retroceso").forEach { inventado ->
            assertFalse(
                descriptions.contains(inventado),
                "Ninguna descripción de tipo puede prometer «$inventado»",
            )
        }
    }

    @Test
    fun `rafaga es el unico tipo cuya descripcion menciona el acceso sin sesion`() {
        assertTrue(ExamTypeOption.RAFAGA.description.contains("sin sesión"))
        assertFalse(ExamTypeOption.RAZONADO.description.contains("sin sesión"))
        assertFalse(ExamTypeOption.INMERSIVO.description.contains("sin sesión"))
    }

    @Test
    fun `el alias legacy guiado se lee como razonado pero nunca se muestra`() {
        assertEquals(ExamTypeOption.RAZONADO, ExamTypeOption.fromWire("guiado"))
        assertEquals("Razonado", ExamTypeOption.labelOf("guiado"))
    }

    @Test
    fun `un tipo desconocido no se disfraza de tipo conocido`() {
        assertNull(ExamTypeOption.fromWire("mixto"))
        assertEquals("Tipo sin identificar", ExamTypeOption.labelOf("mixto"))
    }

    // -----------------------------------------------------------------------
    // Estados: sólo tres
    // -----------------------------------------------------------------------

    @Test
    fun `un examen solo tiene tres estados`() {
        assertEquals(listOf("Borrador", "Publicado", "Archivado"), examStatusLabels)
        assertEquals(3, ExamStatus.entries.size)
    }

    @Test
    fun `en revision no es un estado de examen`() {
        assertFalse(examStatusLabels.any { it.equals("En revisión", ignoreCase = true) })
    }

    // -----------------------------------------------------------------------
    // Capacidades por especialidad
    // -----------------------------------------------------------------------

    @Test
    fun `un tipo que la especialidad no permite queda deshabilitado`() {
        val onlyRafagas = UiAsync.Success(capabilities(rafagas = true))
        val availability = examTypeAvailability(onlyRafagas).associateBy { it.option }

        assertTrue(availability.getValue(ExamTypeOption.RAFAGA).allowed)
        assertFalse(availability.getValue(ExamTypeOption.RAZONADO).allowed)
        assertFalse(availability.getValue(ExamTypeOption.INMERSIVO).allowed)
    }

    @Test
    fun `el motivo del bloqueo es el mensaje de la especialidad y no uno inventado`() {
        val limited = UiAsync.Success(
            capabilities(rafagas = true, limitation = "Esta especialidad sólo tiene material de test."),
        )
        val razonado = examTypeAvailability(limited).first { it.option == ExamTypeOption.RAZONADO }
        assertEquals("Esta especialidad sólo tiene material de test.", razonado.note)
    }

    // El guard más importante del rediseño: si las capacidades no se pudieron
    // cargar, NINGÚN tipo se puede elegir. `saveExam` rechaza guardar un tipo que
    // no ha podido comprobar, así que habilitar el control prometería algo que el
    // guardado va a denegar.
    @Test
    fun `sin capacidades cargadas ningun tipo esta permitido`() {
        val unavailable = UiAsync.Error(AppError.Network("boom"))
        assertTrue(examTypeAvailability(unavailable).none { it.allowed })
        assertTrue(examTypeAvailability(UiAsync.Loading).none { it.allowed })
        assertTrue(examTypeAvailability(UiAsync.Idle).none { it.allowed })
    }

    @Test
    fun `un fallo de capacidades se explica y se distingue de estar cargando`() {
        assertEquals(
            ExamCapabilitiesNotice.UNAVAILABLE,
            examCapabilitiesNotice(UiAsync.Error(AppError.Network("boom"))),
        )
        assertEquals(ExamCapabilitiesNotice.PENDING, examCapabilitiesNotice(UiAsync.Loading))
        assertNotNull(examCapabilitiesMessage(UiAsync.Error(AppError.Network("boom"))))
    }

    // «Nadie ha decidido esto» y «se ha decidido todo que no» son cosas distintas
    // y la pantalla tiene que poder decirlo.
    @Test
    fun `no configurada se distingue de decidida con todo desactivado`() {
        val neverConfigured = UiAsync.Success(capabilities(rafagas = true, configured = false))
        val decidedAllOff = UiAsync.Success(capabilities(configured = true))

        assertEquals(ExamCapabilitiesNotice.NOT_CONFIGURED, examCapabilitiesNotice(neverConfigured))
        assertEquals(ExamCapabilitiesNotice.RESOLVED, examCapabilitiesNotice(decidedAllOff))
        assertNotNull(examCapabilitiesMessage(neverConfigured))
        assertNull(examCapabilitiesMessage(decidedAllOff))
    }

    // -----------------------------------------------------------------------
    // Preguntas vinculadas: un número, no dos
    // -----------------------------------------------------------------------

    @Test
    fun `las preguntas vinculadas son un solo numero`() {
        assertEquals("Sin preguntas vinculadas", linkedQuestionsText(0))
        assertEquals("1 pregunta vinculada", linkedQuestionsText(1))
        assertEquals("118 preguntas vinculadas", linkedQuestionsText(118))
    }

    // Regresión de un defecto que apareció en la captura de «examen sin
    // preguntas»: la lista cargada y vacía caía al `total_preguntas` de la fila
    // del catálogo, y la pantalla se contradecía sola —«12 preguntas vinculadas»
    // encima de «Este examen todavía no tiene preguntas asociadas»—.
    @Test
    fun `una lista vacia cuenta cero y no el recuento viejo del catalogo`() {
        val conDoce = exam("a").copy(questionCount = 12)
        assertEquals(0, linkedQuestionCount(UiAsync.Empty, conDoce))
    }

    /**
     * **Mientras carga, el número de la insignia y de las comprobaciones es `null`.**
     *
     * Ese número habla de la lista que se va a ver en la pestaña, y la lista
     * todavía no ha llegado. Desde M1 `total_preguntas` sí lo mantiene la base y
     * las ETIQUETAS lo enseñan como dato mientras carga (K2-2, ver
     * `mientras carga el recuento servido se ensena como un dato`); lo que no hace
     * es sustituir a la lista donde se lee como un hecho sobre ella.
     */
    @Test
    fun `mientras carga no se toma el total del catalogo como recuento`() {
        val conDoce = exam("a").copy(questionCount = 12)
        assertNull(linkedQuestionCount(UiAsync.Loading, conDoce))
        assertNull(linkedQuestionCount(UiAsync.Idle, conDoce))
    }

    /** Cargando y fallido son dos `null` que significan cosas opuestas. */
    @Test
    fun `contando y no haber podido contar no se dicen igual`() {
        val cargando = linkedQuestionCountLabel(UiAsync.Loading, provisional = null)
        val fallido = linkedQuestionCountLabel(UiAsync.Error(AppError.Network("boom")), provisional = null)
        assertEquals(ExamFieldOrigin.PENDING, cargando.origin)
        assertEquals(ExamFieldOrigin.UNAVAILABLE, fallido.origin)
        assertTrue(cargando.text.lowercase().contains("contando"))
        assertNotEquals(cargando.text, fallido.text)
        // Y ninguno de los dos se parece a un número.
        assertNull(cargando.text.toIntOrNull())
        assertNull(fallido.text.toIntOrNull())

        // La lista cargada sí es un valor, tenga o no preguntas.
        assertEquals(ExamFieldOrigin.VALUE, linkedQuestionCountLabel(UiAsync.Empty, provisional = null).origin)
        assertEquals("0", linkedQuestionCountLabel(UiAsync.Empty, provisional = null).text)
    }

    // Un fallo de carga no se rellena con la cifra anterior: no saber cuántas hay
    // es distinto de saber que hay doce.
    @Test
    fun `un fallo al cargar preguntas no inventa un recuento`() {
        val conDoce = exam("a").copy(questionCount = 12)
        assertNull(linkedQuestionCount(UiAsync.Error(AppError.Network("boom")), conDoce))
    }

    @Test
    fun `el texto de preguntas no habla de un objetivo ni de lo que falta`() {
        val texts = listOf(0, 1, 118).map(::linkedQuestionsText).joinToString(" ").lowercase()
        listOf("requerid", "faltan", " de 12", "objetivo").forEach { inventado ->
            assertFalse(texts.contains(inventado), "El recuento no puede insinuar «$inventado»")
        }
    }

    // -----------------------------------------------------------------------
    // Recursos: derivados de las preguntas
    // -----------------------------------------------------------------------

    @Test
    fun `los recursos salen de las preguntas del examen`() {
        val questions = listOf(
            question("q1", order = 0, resources = listOf(resource("r1", QuestionResourceType.IMAGE, "Radiografía"))),
            question("q2", order = 1),
            question(
                "q3",
                order = 2,
                resources = listOf(
                    resource("r2", QuestionResourceType.AUDIO, "Auscultación"),
                    resource("r3", QuestionResourceType.TEXT, "Protocolo", textBody = "Paso 1…"),
                ),
            ),
        )

        val groups = examResourceGroups(questions)

        // Sólo las preguntas que TIENEN recursos, y con su posición en el examen.
        assertEquals(listOf("q1", "q3"), groups.map { it.questionId })
        assertEquals(listOf(1, 3), groups.map { it.position })
        assertEquals(3, examResourceTotals(questions).resourceCount)
        assertEquals(2, examResourceTotals(questions).questionsWithResources)
        assertEquals(3, examResourceTotals(questions).totalQuestions)
    }

    @Test
    fun `un examen sin recursos lo dice sin fingir una tabla vacia`() {
        val questions = listOf(question("q1", order = 0))
        val totals = examResourceTotals(questions)
        assertEquals(0, totals.resourceCount)
        assertTrue(examResourceGroups(questions).isEmpty())
        assertEquals("La única pregunta de este examen no tiene recursos.", examResourceTotalsText(totals))
    }

    // Los cinco tipos reales de `recursos_pregunta`, ni uno más.
    @Test
    fun `solo hay cinco tipos de recurso`() {
        assertEquals(
            listOf("Imagen", "Audio", "Vídeo", "Texto", "Documento"),
            QuestionResourceType.entries.map(::resourceTypeLabel),
        )
    }

    // Un recurso no tiene estado que enseñar: `activo` no llega siquiera al
    // cliente, y «válido/pendiente/revisado» eran una deducción del panel.
    @Test
    fun `un recurso no expone ningun estado de revision`() {
        val entry = examResourceGroups(
            listOf(question("q1", order = 0, resources = listOf(resource("r1", QuestionResourceType.IMAGE, "Foto")))),
        ).first().resources.first()
        val rendered = "${entry.title} ${entry.typeLabel} ${entry.preview}".lowercase()
        listOf("pendiente", "válido", "valido", "revisado").forEach { inventado ->
            assertFalse(rendered.contains(inventado), "Un recurso no puede mostrar el estado «$inventado»")
        }
    }

    // -----------------------------------------------------------------------
    // Catálogo: filtros y paginación, los dos de cliente
    // -----------------------------------------------------------------------

    @Test
    fun `los filtros del catalogo se combinan sobre la lista completa`() {
        val exams = listOf(
            exam("a", specialtyId = "s1", type = "rafaga", year = 2025),
            exam("b", specialtyId = "s1", type = "inmersivo", year = 2026),
            exam("c", specialtyId = "s2", type = "rafaga", year = 2026),
        )

        assertEquals(
            listOf("a", "b"),
            applyCatalogFilters(exams, ExamCatalogFilter(specialtyId = "s1")).map { it.id },
        )
        assertEquals(
            listOf("a", "c"),
            applyCatalogFilters(exams, ExamCatalogFilter(typeWire = "rafaga")).map { it.id },
        )
        assertEquals(
            listOf("c"),
            applyCatalogFilters(exams, ExamCatalogFilter(typeWire = "rafaga", year = 2026)).map { it.id },
        )
    }

    @Test
    fun `el filtro de tipo entiende el alias legacy guardado en la base`() {
        val exams = listOf(exam("a", type = "guiado"))
        assertEquals(
            listOf("a"),
            applyCatalogFilters(exams, ExamCatalogFilter(typeWire = "razonado")).map { it.id },
        )
    }

    @Test
    fun `la paginacion trocea la lista que ya esta en memoria`() {
        val items = (1..25).toList()
        val first = examCatalogPage(items, pageIndex = 0, pageSize = 10)

        assertEquals(10, first.items.size)
        assertEquals(3, first.pageCount)
        assertEquals(25, first.totalItems)
        assertEquals(1, first.firstItemNumber)
        assertEquals(10, first.lastItemNumber)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)

        val last = examCatalogPage(items, pageIndex = 2, pageSize = 10)
        assertEquals(5, last.items.size)
        assertEquals(21, last.firstItemNumber)
        assertEquals(25, last.lastItemNumber)
        assertFalse(last.hasNext)
    }

    @Test
    fun `una pagina fuera de rango se corrige en vez de dejar la lista vacia`() {
        val page = examCatalogPage((1..5).toList(), pageIndex = 9, pageSize = 10)
        assertEquals(0, page.pageIndex)
        assertEquals(5, page.items.size)
    }

    // La barra dice el total porque el total está aquí: no hay páginas que pedir
    // al servidor y la interfaz no puede sugerir que las haya.
    @Test
    fun `la barra de paginas dice cuantos examenes hay en total`() {
        val page = examCatalogPage((1..25).toList(), pageIndex = 0, pageSize = 10)
        assertEquals("1–10 de 25 exámenes", examCatalogRangeText(page))
        assertEquals("4 exámenes", examCatalogRangeText(examCatalogPage((1..4).toList(), 0, 10)))
        assertEquals("Sin exámenes que mostrar", examCatalogRangeText(examCatalogPage(emptyList<Int>(), 0, 10)))
    }

    // -----------------------------------------------------------------------
    // Resumen
    // -----------------------------------------------------------------------

    /**
     * Falta de título o de especialidad: BLOCKING, con el texto de D9.
     *
     * El detalle dice lo que impide —guardar—, no que el servidor lo exija para
     * publicar: no lo exige.
     */
    @Test
    fun `el resumen bloquea por titulo especialidad y tipo no permitido`() {
        val vacio = ExamsState(
            editorTitle = "",
            editorSpecialtyId = "",
            editorTypeExam = "inmersivo",
        )
        val checks = examChecks(vacio, exam = null, questionCount = 0).associateBy { it.id }

        assertEquals(ExamCheckStatus.BLOCKING, checks.getValue(ExamCheckId.TITLE).status)
        assertEquals("Sin título no se puede guardar.", checks.getValue(ExamCheckId.TITLE).detail)
        assertEquals(ExamCheckStatus.BLOCKING, checks.getValue(ExamCheckId.SPECIALTY).status)
        assertEquals("Selecciona una especialidad.", checks.getValue(ExamCheckId.SPECIALTY).detail)
        // Sin especialidad no hay tipo que comprobar: ni bueno ni malo (D10).
        assertEquals(ExamCheckStatus.UNKNOWN, checks.getValue(ExamCheckId.TYPE_ALLOWED).status)

        val noPermitido = vacio.copy(
            editorTitle = "TCAE Marzo",
            editorSpecialtyId = "s1",
            specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
        )
        assertEquals(
            ExamCheckStatus.BLOCKING,
            examChecks(noPermitido, exam = null, questionCount = 0)
                .first { it.id == ExamCheckId.TYPE_ALLOWED }.status,
        )
    }

    // Un examen NUEVO sin preguntas: guardar no las exige —se vinculan después de
    // crearlo—, así que es aviso y no bloqueo. El examen guardado y vacío, que sí
    // bloquea la publicación (K-DEC01), está en el caso siguiente.
    @Test
    fun `un examen sin preguntas avisa pero no bloquea`() {
        val state = ExamsState(
            editorTitle = "TCAE Marzo",
            editorSpecialtyId = "s1",
            editorTypeExam = "rafaga",
            specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
        )
        val checks = examChecks(state, exam = null, questionCount = 0).associateBy { it.id }
        assertEquals(ExamCheckStatus.WARNING, checks.getValue(ExamCheckId.LINKED_QUESTIONS).status)
        assertEquals(ExamCheckStatus.OK, checks.getValue(ExamCheckId.TYPE_ALLOWED).status)
    }

    /**
     * K-DEC01 (DEC-EX-01 = A): un examen GUARDADO con la lista cargada y vacía
     * bloquea la publicación, porque el servidor la rechaza con 409
     * `EXAM_PUBLISH_REQUIRES_QUESTIONS`. El detalle dice la regla; en uno ya
     * publicado, la consecuencia. Mutación: volver a `WARNING` pone este test en rojo.
     */
    @Test
    fun `un examen guardado sin preguntas bloquea la publicacion`() {
        listOf(ExamStatus.DRAFT, ExamStatus.ARCHIVED).forEach { status ->
            val guardado = exam("a").copy(status = status, questionCount = 0)
            val abierto = editorStateFor(guardado).copy(catalogExams = listOf(guardado))
            listOf(UiAsync.Empty, UiAsync.Success(emptyList<ExamQuestion>())).forEach { vacia ->
                val fila = examChecks(abierto.copy(questions = vacia), guardado, questionCount = 0)
                    .single { it.id == ExamCheckId.LINKED_QUESTIONS }
                assertEquals(ExamCheckStatus.BLOCKING, fila.status, "$status con $vacia")
                assertEquals(EXAM_EMPTY_QUESTIONS_DETAIL, fila.detail)
                assertFalse(fila.transient, "No tener preguntas no se resuelve solo")
            }
        }

        val publicado = exam("b").copy(status = ExamStatus.PUBLISHED, questionCount = 0)
        val fila = examChecks(
            editorStateFor(publicado).copy(catalogExams = listOf(publicado), questions = UiAsync.Empty),
            publicado,
            questionCount = 0,
        ).single { it.id == ExamCheckId.LINKED_QUESTIONS }
        assertEquals(ExamCheckStatus.BLOCKING, fila.status)
        assertEquals(EXAM_PUBLISHED_EMPTY_QUESTIONS_DETAIL, fila.detail)

        // Con preguntas, nada que bloquear.
        val conUna = exam("c").copy(questionCount = 1)
        assertEquals(
            ExamCheckStatus.OK,
            examChecks(
                editorStateFor(conUna).copy(catalogExams = listOf(conUna), questions = UiAsync.Success(listOf(question("q1", 0)))),
                conUna,
                questionCount = 1,
            ).single { it.id == ExamCheckId.LINKED_QUESTIONS }.status,
        )
    }

    @Test
    fun `lo que no se ha podido comprobar no se da por bueno`() {
        // Un examen EXISTENTE: en uno nuevo las preguntas no se cuentan —sólo
        // pueden ser cero— y la fila avisa de que se vinculan después (D1).
        val state = ExamsState(
            selectedExamId = "a",
            editorTitle = "TCAE Marzo",
            editorSpecialtyId = "s1",
            editorTypeExam = "rafaga",
            specialtyCapabilities = UiAsync.Error(AppError.Network("boom")),
            questions = UiAsync.Error(AppError.Network("boom")),
        )
        val checks = examChecks(state, exam = exam("a"), questionCount = null).associateBy { it.id }
        assertEquals(ExamCheckStatus.UNKNOWN, checks.getValue(ExamCheckId.TYPE_ALLOWED).status)
        assertEquals(ExamCheckStatus.UNKNOWN, checks.getValue(ExamCheckId.LINKED_QUESTIONS).status)
    }

    @Test
    fun `el resumen no inventa una comprobacion de configuracion valida`() {
        val state = ExamsState(
            editorTitle = "TCAE Marzo",
            editorSpecialtyId = "s1",
            editorTypeExam = "rafaga",
            specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
        )
        val labels = examChecks(state, exam = null, questionCount = 5).map { it.label.lowercase() }
        assertFalse(labels.any { it.contains("configuración válida") || it.contains("configuracion valida") })
    }

    @Test
    fun `un examen recien cargado no aparece con cambios sin guardar`() {
        val exam = exam("a", specialtyId = "s1", type = "rafaga", year = 2026)
        assertFalse(hasUnsavedExamChanges(editorStateFor(exam), exam))
    }

    @Test
    fun `cambiar un campo del editor se detecta como cambio sin guardar`() {
        val exam = exam("a", specialtyId = "s1", type = "rafaga", year = 2026)
        val dirty = editorStateFor(exam).copy(editorDurationMinutes = "90")
        assertTrue(hasUnsavedExamChanges(dirty, exam))
        assertTrue(
            examChecks(dirty, exam, questionCount = 4).any { it.id == ExamCheckId.UNSAVED_CHANGES },
        )
    }

    // -----------------------------------------------------------------------
    // Etiquetas
    // -----------------------------------------------------------------------

    private fun community(id: String, name: String) = Community(
        id = id,
        name = name,
        code = name.take(2).uppercase(),
        slug = name.lowercase(),
        isActive = true,
        sortOrder = 0,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `sin comunidad el examen es nacional, y eso no depende del catalogo`() {
        val cargado = UiAsync.Success(listOf(community("c1", "Andalucía")))
        // «Nacional» se resuelve ANTES de mirar la lista: un examen sin
        // `comunidad_id` es nacional aunque la tabla no exista en este entorno.
        listOf(
            cargado,
            UiAsync.Error(AppError.Network("boom")),
            UiAsync.Loading,
            UiAsync.Empty,
        ).forEach { estado ->
            val etiqueta = communityLabel(null, estado)
            assertEquals("Nacional", etiqueta.text)
            assertEquals(ExamFieldOrigin.VALUE, etiqueta.origin)
        }
        assertEquals("Andalucía", communityLabel("c1", cargado).text)
    }

    /**
     * **La regresión que este fichero afirmaba al revés.**
     *
     * El test anterior decía `assertEquals("Nacional", communityLabel("c-desconocida", …))`
     * con el comentario «un identificador que no resuelve no se enseña crudo».
     * Cierto lo segundo y desastroso lo primero: no enseñar el id no obliga a
     * enseñar OTRO VALOR. Un examen andaluz se presentaba como nacional, con
     * aspecto de dato correcto, y el desplegable ofrecía «Nacional» como única
     * opción — confirmar ahí anulaba un `comunidad_id` real.
     */
    @Test
    fun `un id que no resuelve no se convierte en Nacional`() {
        val cargado = UiAsync.Success(listOf(community("c1", "Andalucía")))
        val huerfano = communityLabel("c-desconocida", cargado)
        assertEquals(ExamFieldOrigin.UNRESOLVED, huerfano.origin)
        assertNotEquals("Nacional", huerfano.text)
        assertNotNull(huerfano.detail)
        // Y no se filtra el identificador en ninguna de las dos líneas.
        assertFalse(huerfano.text.contains("c-desconocida"))
        assertFalse(huerfano.detail.orEmpty().contains("c-desconocida"))
    }

    /**
     * La semilla del repositorio no es un catálogo vacío legítimo, pero aunque lo
     * fuera: un id guardado sobre una lista vacía sigue sin ser «Nacional».
     */
    @Test
    fun `una lista vacia tampoco convierte un id guardado en Nacional`() {
        val vacio = communityLabel("c1", UiAsync.Success(emptyList<Community>()))
        assertEquals(ExamFieldOrigin.UNRESOLVED, vacio.origin)
        assertNotEquals("Nacional", vacio.text)
        assertEquals(ExamFieldOrigin.UNRESOLVED, communityLabel("c1", UiAsync.Empty).origin)
    }

    @Test
    fun `un fallo de catalogo no decide la comunidad de un examen`() {
        val caido = communityLabel("c1", UiAsync.Error(AppError.Network("boom")))
        assertEquals(ExamFieldOrigin.UNAVAILABLE, caido.origin)
        assertNotEquals("Nacional", caido.text)
        assertNotNull(caido.detail)

        val cargando = communityLabel("c1", UiAsync.Loading)
        assertEquals(ExamFieldOrigin.PENDING, cargando.origin)
        assertNotEquals("Nacional", cargando.text)
    }

    /**
     * «Sin especialidad» no es un estado posible: la columna es `NOT NULL`.
     *
     * Tres estados del catálogo caían todos en esa misma frase, que describe una
     * situación que la base de datos no permite. El único caso legítimo de
     * ausencia es un editor en blanco, y eso es «Sin elegir».
     */
    @Test
    fun `la especialidad no se declara ausente cuando lo que falta es el catalogo`() {
        val cargado = UiAsync.Success(listOf(specialty("s1", "TCAE")))
        assertEquals("TCAE", specialtyLabel("s1", cargado, savedExam = null).text)
        assertEquals(ExamFieldOrigin.VALUE, specialtyLabel("s1", cargado, savedExam = null).origin)

        // Editor en blanco: nadie ha elegido todavía. Eso sí es una ausencia.
        assertEquals(ExamFieldOrigin.ABSENT, specialtyLabel("", cargado, savedExam = null).origin)

        listOf(
            UiAsync.Error(AppError.Network("boom")) to ExamFieldOrigin.UNAVAILABLE,
            UiAsync.Loading to ExamFieldOrigin.PENDING,
            UiAsync.Success(emptyList<Specialty>()) to ExamFieldOrigin.UNRESOLVED,
            UiAsync.Empty to ExamFieldOrigin.UNRESOLVED,
        ).forEach { (estado, esperado) ->
            val etiqueta = specialtyLabel("s1", estado, savedExam = null)
            assertEquals(esperado, etiqueta.origin, "estado $estado")
            assertFalse(
                etiqueta.text.lowercase().contains("sin especialidad"),
                "«Sin especialidad» describe algo que la base no permite: $etiqueta",
            )
        }
    }

    /**
     * La cabecera no puede contradecir a la tarjeta del MISMO examen.
     *
     * Con el catálogo de especialidades caído, la cabecera y la ficha decían «No
     * disponible» mientras la tarjeta seleccionada del catálogo decía «TCAE ·
     * 2026»: la tarjeta lee `Exam.specialtyName` y la etiqueta sólo miraba el
     * catálogo. Si el editor sigue en la especialidad guardada y el examen trae
     * nombre, ése es el dato, y se pinta como dato.
     */
    @Test
    fun `con el catalogo sin resolver la especialidad sale del propio examen`() {
        val guardado = exam("e1", specialtyId = "s1").copy(specialtyName = "TCAE")
        listOf(
            UiAsync.Error(AppError.Network("boom")),
            UiAsync.Loading,
            UiAsync.Empty,
            UiAsync.Success(emptyList<Specialty>()),
        ).forEach { catalogo ->
            val etiqueta = specialtyLabel("s1", catalogo, savedExam = guardado)
            assertEquals("TCAE", etiqueta.text, "catálogo $catalogo")
            assertEquals(ExamFieldOrigin.VALUE, etiqueta.origin, "catálogo $catalogo")
        }
    }

    /**
     * El nombre guardado sólo vale para la especialidad guardada, y sólo si existe.
     *
     * Si el editor ya apunta a otra especialidad, el nombre del examen es el de la
     * anterior: pintarlo sería mentir. Si el examen no trae nombre, no hay nada que
     * enseñar y se dice por qué. Y cuando el catálogo sí la resuelve, manda él.
     */
    @Test
    fun `el nombre guardado no se aplica a otra especialidad ni en blanco`() {
        val caido = UiAsync.Error(AppError.Network("boom"))
        val guardado = exam("e1", specialtyId = "s1").copy(specialtyName = "TCAE")

        assertEquals(ExamFieldOrigin.UNAVAILABLE, specialtyLabel("s2", caido, savedExam = guardado).origin)
        assertEquals(
            ExamFieldOrigin.UNAVAILABLE,
            specialtyLabel("s1", caido, savedExam = guardado.copy(specialtyName = " ")).origin,
        )
        assertEquals(
            "TCAE renombrada",
            specialtyLabel("s1", UiAsync.Success(listOf(specialty("s1", "TCAE renombrada"))), savedExam = guardado).text,
        )
    }

    /** «Sin especialidad» sólo lo puede decir un borrador que todavía no existe. */
    @Test
    fun `sin especialidad solo lo dice un borrador nuevo`() {
        val cargado = UiAsync.Success(listOf(specialty("s1", "TCAE")))

        val nuevo = specialtyLabel("", cargado, savedExam = null)
        assertEquals("Sin especialidad", nuevo.text)
        assertEquals(ExamFieldOrigin.ABSENT, nuevo.origin)

        // Un examen guardado con el editor en blanco apunta a algo que no se
        // puede resolver (D4, rama 3). Nunca «Sin especialidad».
        val guardado = specialtyLabel("", cargado, savedExam = exam("e1"))
        assertEquals(ExamFieldOrigin.UNRESOLVED, guardado.origin)
        assertEquals("Especialidad no encontrada", guardado.text)
        assertFalse(
            guardado.text.lowercase().contains("sin especialidad"),
            "Un examen guardado siempre tiene especialidad: $guardado",
        )
    }

    /** El aviso del selector sin catálogo dice lo que el fallo implica, y el campo no dice «Sin especialidad» (D4). */
    @Test
    fun `el aviso del selector de especialidad dice lo que implica el fallo`() {
        val caido = UiAsync.Error(AppError.Network("boom"))
        assertEquals(
            "No se ha podido cargar el catálogo. El examen conserva su especialidad.",
            specialtyPickerNotice(caido, isNew = false)?.message,
        )
        assertEquals(
            "No se ha podido cargar el catálogo. Sin especialidad no se puede crear el examen.",
            specialtyPickerNotice(caido, isNew = true)?.message,
        )
        assertEquals(true, specialtyPickerNotice(caido, isNew = false)?.canRetry)
        assertNull(specialtyPickerNotice(UiAsync.Success(listOf(specialty("s1", "TCAE"))), isNew = false))

        // Un borrador con el catálogo caído: el campo dice el estado del catálogo,
        // no «Sin especialidad» como si fuera la consecuencia del fallo.
        val nuevoCaido = ExamsState(selectedExamId = null, specialties = caido)
        assertEquals("No disponible", specialtyPickerValue(nuevoCaido, exam = null).text)
        // Uno guardado enseña la suya, que llega con el propio examen.
        val guardado = exam("a").copy(specialtyName = "TCAE")
        assertEquals("TCAE", specialtyPickerValue(editorStateFor(guardado).copy(specialties = caido), guardado).text)
    }

    /** Sin especialidad elegida no se está comprobando nada, y la nota de tipos no puede decir que sí (D15). */
    @Test
    fun `sin especialidad elegida la nota de tipos no dice que se esta comprobando`() {
        assertEquals("Selecciona una especialidad para comprobar qué tipos admite.", examCapabilitiesMessage(UiAsync.Idle))
        assertEquals("Comprobando qué modos permite esta especialidad…", examCapabilitiesMessage(UiAsync.Loading))
    }

    // -----------------------------------------------------------------------
    // Código (D2) — objetivo de la mutación D
    // -----------------------------------------------------------------------

    @Test
    fun `el codigo de un examen nuevo se genera solo`() {
        val nuevo = ExamsState(selectedExamId = null)
        assertEquals("Se genera solo", examCodePlaceholder(nuevo, exam = null))
        assertEquals("Opcional. Debe ser único. Si lo dejas en blanco, se crea uno al guardar.", examCodeSupport(nuevo))
        assertEquals(ExamFieldLabel("Se genera solo", ExamFieldOrigin.ABSENT), examSummaryCode(nuevo, exam = null))
        // La cabecera no predice un código que todavía no existe.
        assertNull(examHeaderCode(nuevo, exam = null))

        // Con uno escrito, es ése: es lo que se va a guardar.
        val escrito = nuevo.copy(editorCode = " EX-PROPIO ")
        assertEquals("EX-PROPIO", examHeaderCode(escrito, exam = null))
        assertEquals(ExamFieldLabel("EX-PROPIO", ExamFieldOrigin.VALUE), examSummaryCode(escrito, exam = null))
    }

    /**
     * El código visible de un examen existente es su código humano y nunca su
     * identificador interno, también con el campo vaciado (objetivo de la
     * mutación D).
     */
    @Test
    fun `el codigo visible de un examen existente es su codigo humano y nunca su id`() {
        val uuid = "3f9c2a1e-8b7d-4c6e-9a15-2d4f6b8e0c31"
        val guardado = exam("x").copy(id = uuid, code = "EX-2026-0001")
        val abierto = editorStateFor(guardado)
        val vaciado = abierto.copy(editorCode = "")
        listOf(abierto, vaciado).forEach { state ->
            val cabecera = examHeaderCode(state, guardado).orEmpty()
            assertEquals("EX-2026-0001", cabecera)
            assertFalse(cabecera.contains(uuid), "La cabecera enseña el identificador interno: $cabecera")
            val ficha = examSummaryCode(state, guardado)
            assertEquals(ExamFieldLabel("EX-2026-0001", ExamFieldOrigin.VALUE), ficha)
        }
        assertEquals("Se conserva EX-2026-0001", examCodePlaceholder(vaciado, guardado))
        assertEquals("Debe ser único. Si lo dejas en blanco, se conserva el código actual.", examCodeSupport(vaciado))
    }

    // -----------------------------------------------------------------------
    // Comunidad (D5) — objetivo de la mutación A
    // -----------------------------------------------------------------------

    /** Catálogo caído no es Nacional: ni en la etiqueta ni en el aviso del selector. */
    @Test
    fun `sin catalogo de comunidades una comunidad guardada no se vuelve nacional`() {
        val caido = UiAsync.Error(AppError.Network("boom"))
        listOf(true, false).forEach { isNew ->
            val aviso = communityPickerNotice("com-an", caido, isNew = isNew)
            assertNotNull(aviso)
            assertFalse(
                aviso.message.lowercase().contains("nacional"),
                "Un catálogo caído no convierte una comunidad guardada en Nacional: ${aviso.message}",
            )
            assertTrue(aviso.canRetry)
        }
        val etiqueta = communityLabel("com-an", caido)
        assertEquals("No disponible", etiqueta.text)
        assertEquals(ExamFieldOrigin.UNAVAILABLE, etiqueta.origin)

        // SIN comunidad guardada sí es nacional, lo sepa o no el catálogo.
        assertEquals(
            "No se ha podido cargar el catálogo de comunidades. Este examen es nacional.",
            communityPickerNotice(null, caido, isNew = false)?.message,
        )
        assertEquals(
            "No se ha podido cargar el catálogo de comunidades. El examen se creará como nacional.",
            communityPickerNotice(null, caido, isNew = true)?.message,
        )
        // Con catálogo no hay aviso: hay desplegable.
        assertNull(communityPickerNotice("com-an", UiAsync.Success(listOf(community("com-an", "Andalucía"))), isNew = false))
    }

    // -----------------------------------------------------------------------
    // Recuento de preguntas (D1)
    // -----------------------------------------------------------------------

    /**
     * K2-2: desde M1 `total_preguntas` lo mantiene la base, así que la cifra
     * servida mientras la lista carga es un dato y se pinta como tal, sin «sin
     * confirmar». Mutación: devolver `PENDING` o añadir el sufijo en
     * `pendingQuestionsLabel` pone este test en rojo.
     */
    @Test
    fun `mientras carga el recuento servido se ensena como un dato`() {
        listOf(UiAsync.Loading, UiAsync.Idle).forEach { cargando ->
            assertEquals(ExamFieldLabel("12", ExamFieldOrigin.VALUE), linkedQuestionCountLabel(cargando, provisional = 12))
            val largo = linkedQuestionsLabel(cargando, provisional = 12)
            assertEquals(ExamFieldLabel("12 preguntas vinculadas", ExamFieldOrigin.VALUE), largo)
            assertTrue(largo.isValue)
            assertEquals("12 preguntas", linkedQuestionsShortLabel(cargando, provisional = 12))
            assertEquals(ExamFieldLabel("Sin preguntas vinculadas", ExamFieldOrigin.VALUE), linkedQuestionsLabel(cargando, provisional = 0))
        }
        // Sin cifra servida (examen que todavía no existe) se sigue diciendo que se cuenta.
        val contando = linkedQuestionsLabel(UiAsync.Loading, provisional = null)
        assertEquals("Contando preguntas…", contando.text)
        assertEquals(ExamFieldOrigin.PENDING, contando.origin)
    }

    @Test
    fun `un error al contar no ensena ninguna cifra`() {
        val fallo = UiAsync.Error(AppError.Network("boom"))
        listOf(
            linkedQuestionCountLabel(fallo, provisional = 12),
            linkedQuestionsLabel(fallo, provisional = 12),
        ).forEach { etiqueta ->
            assertEquals("Sin contar", etiqueta.text)
            assertEquals(ExamFieldOrigin.UNAVAILABLE, etiqueta.origin)
            assertFalse(etiqueta.text.any { it.isDigit() }, "Un fallo al contar no enseña una cifra: ${etiqueta.text}")
        }
        assertEquals("Sin contar", linkedQuestionsShortLabel(fallo, provisional = 12))
        // Una lista vacía es cero, y eso sí es un valor.
        assertEquals(
            ExamFieldLabel("Sin preguntas vinculadas", ExamFieldOrigin.VALUE),
            linkedQuestionsLabel(UiAsync.Empty, provisional = 12),
        )
    }

    @Test
    fun `la cifra provisional sale del recuento de la sesion antes que del servido`() {
        val servido = exam("a").copy(questionCount = 12)
        assertEquals(12, provisionalQuestionCount(ExamsState(), servido))
        assertEquals(7, provisionalQuestionCount(ExamsState(countedQuestions = mapOf("a" to 7)), servido))
        assertNull(provisionalQuestionCount(ExamsState(countedQuestions = mapOf("a" to 7)), exam = null))
    }

    @Test
    fun `la fila de preguntas sigue D1 y no usa la cifra provisional`() {
        val nuevo = linkedQuestionsCheck(ExamsState(), questionCount = 0)
        assertEquals(ExamCheckStatus.WARNING, nuevo.status)
        assertEquals("Todavía sin preguntas: se vinculan después de crear el examen.", nuevo.detail)

        val existente = ExamsState(selectedExamId = "a", countedQuestions = mapOf("a" to 9))
        val contando = linkedQuestionsCheck(existente.copy(questions = UiAsync.Loading), questionCount = null)
        assertEquals(ExamCheckStatus.UNKNOWN, contando.status)
        assertEquals("Contando las preguntas de este examen…", contando.detail)
        assertFalse(contando.detail.orEmpty().any { it.isDigit() }, "La comprobación no puede usar la cifra provisional")

        // K-DEC01: vacío en un examen guardado bloquea la publicación.
        val vacio = linkedQuestionsCheck(existente.copy(questions = UiAsync.Empty), questionCount = 0)
        assertEquals(ExamCheckStatus.BLOCKING, vacio.status)
        assertEquals(EXAM_EMPTY_QUESTIONS_DETAIL, vacio.detail)

        val fallo = linkedQuestionsCheck(existente.copy(questions = UiAsync.Error(AppError.Network("boom"))), null)
        assertEquals(ExamCheckStatus.UNKNOWN, fallo.status)
        assertEquals("No se ha podido contar las preguntas de este examen.", fallo.detail)

        val contadas = existente.copy(questions = UiAsync.Success(listOf(question("q1", order = 0))))
        assertEquals(ExamCheckStatus.OK, linkedQuestionsCheck(contadas, questionCount = 1).status)
    }

    // -----------------------------------------------------------------------
    // Barra de guardado (D8) — objetivo de la mutación B
    // -----------------------------------------------------------------------

    @Test
    fun `la barra de guardado solo avisa de cambios cuando los hay`() {
        val guardado = exam("a")
        val limpio = examSaveBarStatus(editorStateFor(guardado), guardado)
        assertEquals(ExamSaveBarMode.CLEAN, limpio.mode)
        assertNull(limpio.message, "Sin cambios la barra no dice nada, y dijo: ${limpio.message}")
        assertFalse(limpio.canSave)
        assertFalse(limpio.canDiscard)
        assertFalse(limpio.prominent)

        val sucio = examSaveBarStatus(editorStateFor(guardado).copy(editorTitle = "Otro título"), guardado)
        assertEquals(ExamSaveBarMode.DIRTY, sucio.mode)
        assertEquals(ExamSaveBarMessage(EXAM_UNSAVED_CHANGES_MESSAGE, ExamSaveBarTone.WARNING), sucio.message)
        assertTrue(sucio.canSave)
        assertTrue(sucio.canDiscard)
        assertTrue(sucio.prominent)
        assertEquals("Guardar cambios", sucio.primaryLabel)

        val nuevo = examSaveBarStatus(ExamsState(editorTitle = "Nuevo", editorSpecialtyId = "s1"), exam = null)
        assertEquals("Crear examen", nuevo.primaryLabel)
        assertTrue(nuevo.canSave)
        assertFalse(nuevo.message?.text.orEmpty().lowercase().contains("cambios sin guardar"))
    }

    /** Un «guardado» anterior no puede tapar un cambio nuevo. */
    @Test
    fun `tras guardar con exito un cambio nuevo vuelve a avisar`() {
        val guardado = exam("a")
        val trasGuardar = editorStateFor(guardado).copy(actionState = UiAsync.Success(Unit))
        assertEquals("Cambios guardados.", examSaveBarStatus(trasGuardar, guardado).message?.text)
        val otraVezSucio = trasGuardar.copy(editorDurationMinutes = "15")
        assertEquals(EXAM_UNSAVED_CHANGES_MESSAGE, examSaveBarStatus(otraVezSucio, guardado).message?.text)
    }

    @Test
    fun `un codigo en blanco y un precio 00 no son cambios`() {
        val guardado = exam("a")
        assertFalse(hasUnsavedExamChanges(editorStateFor(guardado).copy(editorCode = "  "), guardado))
        assertFalse(hasUnsavedExamChanges(editorStateFor(guardado).copy(editorPriceTickets = "00"), guardado))
        // Un código distinto y un precio vaciado, sí.
        assertTrue(hasUnsavedExamChanges(editorStateFor(guardado).copy(editorCode = "EX-OTRO"), guardado))
        assertTrue(hasUnsavedExamChanges(editorStateFor(guardado).copy(editorPriceTickets = ""), guardado))
    }

    // -----------------------------------------------------------------------
    // Compatibilidad del tipo (D10)
    // -----------------------------------------------------------------------

    @Test
    fun `la compatibilidad del tipo dice la verdad en cada caso`() {
        val base = ExamsState(editorSpecialtyId = "s1", editorTypeExam = "razonado")

        examTypeCompatibility(base.copy(editorSpecialtyId = "")).let {
            assertEquals(ExamTypeCompatibilityCase.NO_SPECIALTY, it.case)
            assertEquals("Selecciona una especialidad para comprobar qué tipos admite.", it.label)
            assertEquals(ExamCheckStatus.UNKNOWN, it.status)
        }
        examTypeCompatibility(base.copy(specialtyCapabilities = UiAsync.Loading)).let {
            assertEquals(ExamTypeCompatibilityCase.CHECKING, it.case)
            assertEquals("Comprobando los tipos que permite esta especialidad…", it.label)
        }
        examTypeCompatibility(
            base.copy(specialtyCapabilities = UiAsync.Success(capabilities(razonados = true, configured = false))),
        ).let {
            assertEquals(ExamTypeCompatibilityCase.ALLOWED, it.case)
            assertEquals("Razonado está permitido en esta especialidad.", it.label)
            assertEquals(
                "Esta especialidad usa la configuración por defecto: nadie ha decidido todavía sus tipos.",
                it.detail,
            )
            assertEquals(ExamCheckStatus.OK, it.status)
        }
        examTypeCompatibility(
            base.copy(specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true, limitation = "Sólo test."))),
        ).let {
            assertEquals(ExamTypeCompatibilityCase.NOT_ALLOWED, it.case)
            assertEquals("Razonado no está permitido en esta especialidad.", it.label)
            assertEquals("Sólo test.", it.detail)
            assertEquals(ExamCheckStatus.BLOCKING, it.status)
        }
        assertEquals(
            "Cambia el tipo en Configuración o elige otra especialidad.",
            examTypeCompatibility(base.copy(specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)))).detail,
        )
        examTypeCompatibility(base.copy(specialtyCapabilities = UiAsync.Error(AppError.Network("boom")))).let {
            assertEquals(ExamTypeCompatibilityCase.UNAVAILABLE, it.case)
            assertEquals("No se han podido comprobar los tipos de esta especialidad.", it.label)
            assertEquals("Reintenta en Configuración. Hasta entonces no se puede guardar.", it.detail)
            assertEquals(ExamCheckStatus.UNKNOWN, it.status)
        }
        examTypeCompatibility(base.copy(editorTypeExam = "mixto")).let {
            assertEquals(ExamTypeCompatibilityCase.UNKNOWN_TYPE, it.case)
            assertEquals("Tipo sin identificar", it.label)
            assertEquals(ExamCheckStatus.BLOCKING, it.status)
        }
    }

    // -----------------------------------------------------------------------
    // Año, cabecera e información (D6, D11, D12)
    // -----------------------------------------------------------------------

    @Test
    fun `el placeholder del ano es el mismo texto que la ficha`() {
        assertEquals("Sin año", EXAM_NO_YEAR)
        assertEquals(ExamFieldLabel(EXAM_NO_YEAR, ExamFieldOrigin.ABSENT), examYearLabel(ExamsState(editorYear = " ")))
        assertEquals(ExamFieldLabel("2026", ExamFieldOrigin.VALUE), examYearLabel(ExamsState(editorYear = "2026")))
    }

    @Test
    fun `la cabecera de un examen nuevo ensena solo lo que el editor sabe`() {
        val nuevo = ExamsState(selectedExamId = null, questions = UiAsync.Empty)
        assertEquals(
            listOf("Ráfaga", "Sin preguntas vinculadas", "Sin duración"),
            examHeaderChips(nuevo, exam = null).map { it.text },
        )
        assertEquals("Sin guardar", EXAM_UNSAVED_STATUS)
        assertNull(examHeaderCode(nuevo, exam = null))
        assertEquals("Sin especialidad", specialtyLabel(nuevo.editorSpecialtyId, UiAsync.Success(emptyList()), null).text)
        assertEquals("Nacional", communityLabel(nuevo.editorCommunityId, UiAsync.Success(emptyList())).text)
    }

    @Test
    fun `la cabecera no repite la comunidad y solo dice oficial si lo es`() {
        val guardado = exam("a").copy(communityId = "com-an", durationMinutes = 45, year = 2026)
        val abierto = editorStateFor(guardado).copy(questions = UiAsync.Success(listOf(question("q1", order = 0))))
        val chips = examHeaderChips(abierto, guardado).map { it.text }
        assertEquals(listOf("Ráfaga", "1 pregunta vinculada", "45 min", "Año 2026"), chips)
        assertFalse(chips.any { it == "Nacional" || it.contains("Andalucía") }, "La comunidad ya sale en su par: $chips")
        assertTrue("Oficial" in examHeaderChips(abierto.copy(editorIsOfficial = true), guardado).map { it.text })
    }

    @Test
    fun `la tarjeta informacion no usa deicticos ni promete lo que no funciona`() {
        assertEquals(4, examInformationLines.size)
        examInformationLines.forEach { line ->
            listOf("esta pestaña", "su pestaña", "su propia pestaña").forEach { deictico ->
                assertFalse(line.lowercase().contains(deictico), "«$deictico» no señala nada desde el resumen: $line")
            }
        }
        assertTrue(examInformationLines.any { it.contains("todavía no funciona") })
    }

    // -----------------------------------------------------------------------
    // Portada automática: (tipo + comunidad) → previsualización
    // -----------------------------------------------------------------------

    private fun comunidadIso(id: String, name: String, iso: String) = Community(
        id = id,
        name = name,
        code = iso,
        slug = name.lowercase(),
        isActive = true,
        sortOrder = 0,
        createdAt = now,
        updatedAt = now,
    )

    private val comunidades = UiAsync.Success(
        listOf(comunidadIso("c-md", "Madrid", "ES-MD"), comunidadIso("c-an", "Andalucía", "ES-AN")),
    )

    @Test
    fun `una rafaga y un razonado muestran su portada comun sea cual sea la comunidad`() {
        listOf(null, "c-md", "c-an").forEach { comunidad ->
            val rafaga = examCoverPresentation(
                ExamsState(editorTypeExam = "rafaga", editorCommunityId = comunidad, communities = comunidades),
            )
            assertEquals("cover_rafaga", rafaga.cover.assetKey)
            assertEquals("Portada de Ráfaga", rafaga.headline)

            val razonado = examCoverPresentation(
                ExamsState(editorTypeExam = "razonado", editorCommunityId = comunidad, communities = comunidades),
            )
            assertEquals("cover_razonado", razonado.cover.assetKey)
            assertEquals("Portada de Razonado", razonado.headline)
        }
    }

    @Test
    fun `un inmersivo muestra el vector de su comunidad y lo nombra`() {
        val madrid = examCoverPresentation(
            ExamsState(editorTypeExam = "inmersivo", editorCommunityId = "c-md", communities = comunidades),
        )
        assertEquals("vector_madrid", madrid.cover.assetKey)
        assertEquals("Vector de Madrid", madrid.headline)
        assertFalse(madrid.cover.isFallback)

        val andalucia = examCoverPresentation(
            ExamsState(editorTypeExam = "inmersivo", editorCommunityId = "c-an", communities = comunidades),
        )
        assertEquals("vector_andalucia", andalucia.cover.assetKey)
    }

    @Test
    fun `cambiar el tipo o la comunidad cambia la portada sin guardar nada`() {
        val base = ExamsState(editorTypeExam = "inmersivo", editorCommunityId = "c-md", communities = comunidades)

        assertEquals("vector_madrid", examCoverPresentation(base).cover.assetKey)
        assertEquals("vector_andalucia", examCoverPresentation(base.copy(editorCommunityId = "c-an")).cover.assetKey)
        assertEquals("cover_razonado", examCoverPresentation(base.copy(editorTypeExam = "razonado")).cover.assetKey)
        assertEquals("cover_rafaga", examCoverPresentation(base.copy(editorTypeExam = "rafaga")).cover.assetKey)
    }

    /** PROVISIONAL y sin decidir: un inmersivo sin comunidad cae a la portada genérica y lo explica. */
    @Test
    fun `un inmersivo sin comunidad muestra la portada provisional y dice como conseguir el vector`() {
        val sinComunidad = examCoverPresentation(ExamsState(editorTypeExam = "inmersivo", communities = comunidades))

        assertTrue(sinComunidad.cover.isFallback)
        assertEquals("Portada provisional", sinComunidad.headline)
        assertTrue(sinComunidad.detail.contains("elige una comunidad"))
    }

    @Test
    fun `un inmersivo con una comunidad sin vector o con el catalogo caido no inventa el vector`() {
        val sinVector = examCoverPresentation(
            ExamsState(
                editorTypeExam = "inmersivo",
                editorCommunityId = "c-xx",
                communities = UiAsync.Success(listOf(comunidadIso("c-xx", "Atlántida", "ES-XX"))),
            ),
        )
        assertTrue(sinVector.cover.isFallback)
        assertEquals("Todavía no hay vector para esta comunidad.", sinVector.detail)

        val catalogoCaido = examCoverPresentation(
            ExamsState(editorTypeExam = "inmersivo", editorCommunityId = "c-md", communities = UiAsync.Loading),
        )
        assertTrue(catalogoCaido.cover.isFallback, "sin catálogo no se sabe la comunidad: no se inventa")
    }

    @Test
    fun `un tipo irreconocible no rompe la previsualizacion`() {
        val cover = examCoverPresentation(ExamsState(editorTypeExam = "marciano"))

        assertTrue(cover.cover.isFallback)
        assertEquals("Portada provisional", cover.headline)
    }

    @Test
    fun `cada tipo de portada tiene su visual y los vectores llevan las iniciales de la comunidad`() {
        assertEquals("RÁF", examCoverVisual(ExamCoverResolver.RAFAGA_COVER).glyph)
        assertEquals("RAZ", examCoverVisual(ExamCoverResolver.RAZONADO_COVER).glyph)
        assertEquals("?", examCoverVisual(ExamCoverResolver.GENERIC_COVER).glyph)
        assertEquals("MD", examCoverVisual(ExamCoverResolver.resolve("inmersivo", "ES-MD")).glyph)
    }

    @Test
    fun `el subtitulo y el texto no hablan de subir elegir ni guardar la portada`() {
        assertEquals(
            "La portada se asigna automáticamente según el tipo de examen y la comunidad autónoma.",
            EXAM_COVER_SUBTITLE,
        )
        val textos = listOf(EXAM_COVER_SUBTITLE) + listOf("rafaga", "razonado", "inmersivo", "x").map {
            examCoverPresentation(ExamsState(editorTypeExam = it)).let { p -> "${p.headline} ${p.detail}" }
        }
        textos.forEach { texto ->
            listOf("subir", "archivo", "jpg", "elegir imagen", "guardar cambios").forEach { prohibido ->
                assertFalse(texto.lowercase().contains(prohibido), "«$prohibido» en: $texto")
            }
        }
    }

    /** La portada sale de campos que ya cuentan: no añade ningún cambio propio al editor. */
    @Test
    fun `la portada no ensucia el editor por si misma y el legacy de storage tampoco`() {
        val guardado = exam("ex-1").copy(coverStoragePath = "exam-covers/ex-1/x.png", coverMimeType = "image/png")

        assertFalse(hasUnsavedExamChanges(editorStateFor(guardado), guardado))
    }

    // -----------------------------------------------------------------------
    // Precio inerte
    // -----------------------------------------------------------------------

    @Test
    fun `un precio en un examen gratuito se avisa porque no se cobra nunca`() {
        val state = ExamsState(
            editorTitle = "TCAE Marzo",
            editorSpecialtyId = "s1",
            editorTypeExam = "rafaga",
            editorTypeAccess = "gratuito",
            editorPriceTickets = "1300",
            specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
        )
        val check = examChecks(state, exam = null, questionCount = 5)
            .firstOrNull { it.id == ExamCheckId.ACCESS_PRICE }
        assertNotNull(check)
        assertEquals(ExamCheckStatus.WARNING, check.status, "Guardar así es legal: avisa, no bloquea")

        // Y no aparece cuando el precio es coherente con el acceso.
        val premium = state.copy(editorTypeAccess = "premium")
        assertTrue(examChecks(premium, null, 5).none { it.id == ExamCheckId.ACCESS_PRICE })
        val sinPrecio = state.copy(editorPriceTickets = "0")
        assertTrue(examChecks(sinPrecio, null, 5).none { it.id == ExamCheckId.ACCESS_PRICE })
    }

    /**
     * Vacío es «sin duración» y 0 es un valor (P2-7): `null != 0`.
     *
     * Antes `durationLabel(0)` devolvía `null` y la pantalla decía «Sin duración»
     * encima de un editor que iba a guardar 0 minutos.
     */
    @Test
    fun `una duracion de cero minutos se ensena como cero y se avisa`() {
        assertNull(durationLabel(null))
        assertEquals("0 min", durationLabel(0))
        assertEquals("45 min", durationLabel(45))

        val cero = ExamsState(editorDurationMinutes = "0")
        assertEquals(ExamFieldLabel("0 min", ExamFieldOrigin.VALUE), examDurationLabel(cero))
        val aviso = examChecks(cero, exam = null, questionCount = 0).single { it.id == ExamCheckId.DURATION }
        assertEquals(ExamCheckStatus.WARNING, aviso.status, "Avisa, no bloquea: la regla al guardar es del ViewModel")
        assertEquals(
            "Una duración de 0 minutos no se puede cumplir: déjala vacía para no fijar duración.",
            aviso.detail,
        )
        assertTrue(examChecks(ExamsState(editorDurationMinutes = "00"), null, 0).any { it.id == ExamCheckId.DURATION })

        val vacia = ExamsState(editorDurationMinutes = "")
        assertEquals(ExamFieldLabel(EXAM_NO_DURATION, ExamFieldOrigin.ABSENT), examDurationLabel(vacia))
        assertTrue(examChecks(vacia, null, 0).none { it.id == ExamCheckId.DURATION })
        assertTrue(examChecks(ExamsState(editorDurationMinutes = "45"), null, 0).none { it.id == ExamCheckId.DURATION })
    }

    // -----------------------------------------------------------------------
    // Código repetido (P1-3, parte de presentación)
    // -----------------------------------------------------------------------

    /** Objetivo de la mutación T2a: sin `ignoreCase`, «ex-2026-0002» dejaría de chocar. */
    @Test
    fun `un codigo que ya usa otro examen choca sin distinguir mayusculas ni espacios`() {
        val otro = exam("b").copy(code = "EX-2026-0002")
        val nuevo = ExamsState(
            selectedExamId = null,
            catalogExams = listOf(exam("a"), otro),
            editorCode = "  ex-2026-0002 ",
        )
        assertEquals(otro, examCodeConflict(nuevo, exam = null))
        assertEquals(
            "Ya hay otro examen con el código EX-2026-0002.",
            examCodeSupport(nuevo, examCodeConflict(nuevo, exam = null)),
        )
        assertNull(examCodeConflict(nuevo.copy(editorCode = "EX-2026-0099"), exam = null))
    }

    /** Objetivo de la mutación T2b: sin excluir el propio examen, su código guardado le bloquearía. */
    @Test
    fun `el codigo guardado de un examen no choca consigo mismo`() {
        val propio = exam("a").copy(code = "EX-2026-0001")
        val otro = exam("b").copy(code = "EX-2026-0002")
        val abierto = editorStateFor(propio).copy(catalogExams = listOf(propio, otro))
        assertNull(examCodeConflict(abierto, propio), "Su propio código no puede bloquearle el guardado")
        assertNull(examCodeConflict(abierto.copy(editorCode = "ex-2026-0001"), propio))
        assertEquals(otro, examCodeConflict(abierto.copy(editorCode = "EX-2026-0002"), propio))
        assertTrue(
            examSaveBarStatus(abierto.copy(editorTitle = "Otro título"), propio).canSave,
            "Con su propio código y cambios, se tiene que poder guardar",
        )
    }

    @Test
    fun `un codigo vacio no choca con nada`() {
        val sinCodigo = exam("b").copy(code = "")
        listOf("", "   ").forEach { vacio ->
            val estado = ExamsState(selectedExamId = null, catalogExams = listOf(sinCodigo), editorCode = vacio)
            assertNull(examCodeConflict(estado, exam = null), "«$vacio» no es un código: el servidor genera uno")
        }
    }

    /**
     * La restricción `unique` también cubre a los archivados, y el catálogo los
     * trae: `GET /examenes` no filtra por `activo` y el repositorio los mapea a
     * `ARCHIVED`.
     */
    @Test
    fun `un examen archivado tambien reserva su codigo`() {
        val archivado = exam("c").copy(code = "EX-2026-0003", status = ExamStatus.ARCHIVED)
        val nuevo = ExamsState(selectedExamId = null, catalogExams = listOf(archivado), editorCode = "EX-2026-0003")
        assertEquals(archivado, examCodeConflict(nuevo, exam = null))
    }

    @Test
    fun `un codigo repetido bloquea el guardado y la comprobacion dice con quien choca`() {
        val otro = exam("b").copy(code = "EX-2026-0002")
        val nuevo = ExamsState(
            selectedExamId = null,
            catalogExams = listOf(otro),
            editorTitle = "TCAE Mayo",
            editorSpecialtyId = "s1",
            editorCode = "EX-2026-0002",
            specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
        )
        val codigo = examChecks(nuevo, exam = null, questionCount = 0).single { it.id == ExamCheckId.CODE }
        assertEquals(ExamCheckStatus.BLOCKING, codigo.status)
        assertEquals("Ya hay otro examen con el código EX-2026-0002.", codigo.detail)
        assertFalse(examSaveBarStatus(nuevo, exam = null).canSave, "Un código repetido no se puede guardar")

        val libre = nuevo.copy(editorCode = "EX-2026-0100")
        assertTrue(examChecks(libre, exam = null, questionCount = 0).none { it.id == ExamCheckId.CODE })
        assertTrue(examSaveBarStatus(libre, exam = null).canSave)
    }

    // -----------------------------------------------------------------------
    // El CAMPO culpable de un guardado rechazado
    //
    // El defecto: al crear un examen, el guardado fallaba y al pie aparecía una
    // sola línea roja que no decía qué campo. Estas pruebas fijan la traducción
    // código de dominio → campo, y a qué pestaña se lleva al usuario.
    // -----------------------------------------------------------------------

    /**
     * `EXAM_QUESTION_COUNT_INVALID` señala «Número de preguntas», que está en
     * Configuración: el caso que motivó todo esto.
     *
     * El mensaje que se pinta bajo el campo es EL DEL BACKEND, sin reescribir: es
     * quien conoce la restricción y ya viene redactado para quien usa la pantalla.
     * Es el texto actual (K1-3): la columna exige `>= 0` desde PW-2.
     */
    @Test
    fun `el recuento de preguntas rechazado señala su campo y abre Configuracion`() {
        val mensaje = "El número de preguntas no puede ser negativo."
        val rechazado = ExamsState(
            selectedExamId = null,
            editorTitle = "TCAE Mayo",
            editorSpecialtyId = "s1",
            actionState = UiAsync.Error(
                AppError.Validation(message = mensaje, code = EXAM_ERROR_QUESTION_COUNT_INVALID),
            ),
        )

        val señalado = assertNotNull(examFieldError(rechazado))
        assertEquals(ExamField.QUESTION_COUNT, señalado.field)
        assertEquals(mensaje, señalado.message)
        assertEquals(ExamFieldErrorSource.SAVE_REJECTED, señalado.source)
        assertEquals(ExamWorkspaceTabId.CONFIGURACION, señalado.field.tab)
        assertEquals(ExamWorkspaceTabId.CONFIGURACION, examFieldErrorTab(rechazado))

        // Y el mensaje al pie NO desaparece: sigue siendo la señal de que el
        // examen no se ha guardado. Marcar el campo añade, no sustituye.
        val barra = assertNotNull(examSaveBarStatus(rechazado, exam = null).message)
        assertEquals(mensaje, barra.text)
        assertEquals(ExamSaveBarTone.DANGER, barra.tone)
    }

    /** `EXAM_CODE_DUPLICATE` señala el código, que está en Información básica. */
    @Test
    fun `un codigo duplicado rechazado por el servidor señala el campo Codigo`() {
        val mensaje = "Ya existe otro examen con ese código. Elige un código distinto y vuelve a guardar."
        val rechazado = ExamsState(
            selectedExamId = null,
            editorCode = "EX-2026-0002",
            actionState = UiAsync.Error(
                AppError.Validation(message = mensaje, code = EXAM_ERROR_CODE_DUPLICATE),
            ),
        )

        val señalado = assertNotNull(examFieldError(rechazado))
        assertEquals(ExamField.CODE, señalado.field)
        assertEquals(mensaje, señalado.message)
        assertEquals(ExamWorkspaceTabId.BASICA, examFieldErrorTab(rechazado))
    }

    /**
     * `EXAM_CODE_REQUIRED` (K0-4) también señala el código y abre Información
     * básica. Se prueba con el código en minúsculas, que es como lo publica el
     * backend (`ErrorDto.error`). Mutación: quitar la constante del `when` de
     * [examFieldError] deja el rechazo sin campo y pone este test en rojo.
     */
    @Test
    fun `un codigo vacio rechazado por el servidor señala el campo Codigo`() {
        val mensaje = "El código del examen no puede quedar vacío."
        listOf(EXAM_ERROR_CODE_REQUIRED, "exam_code_required").forEach { codigo ->
            val rechazado = ExamsState(
                selectedExamId = "a",
                editorCode = "   ",
                actionState = UiAsync.Error(AppError.Validation(message = mensaje, code = codigo)),
            )

            val señalado = assertNotNull(examFieldError(rechazado), codigo)
            assertEquals(ExamField.CODE, señalado.field)
            assertEquals(mensaje, señalado.message)
            assertEquals(ExamFieldErrorSource.SAVE_REJECTED, señalado.source)
            assertEquals(ExamWorkspaceTabId.BASICA, examFieldErrorTab(rechazado))
        }
    }

    /**
     * El choque de código que el editor ya detectaba ([examCodeConflict]) marca el
     * campo, pero NO mueve la pestaña.
     *
     * Es la integración con lo que ya existía: la detección no se duplica, se
     * reutiliza, y lo único nuevo es que el campo se pinta en rojo en vez de
     * decirlo en gris. Y no mueve a nadie porque nadie ha pulsado Guardar: si lo
     * hiciera, escribir un código repetido y pasar a Configuración devolvería al
     * usuario a Información básica una y otra vez.
     */
    @Test
    fun `el choque de codigo del editor marca el campo sin arrastrar al usuario`() {
        val otro = exam("b").copy(code = "EX-2026-0002")
        val escribiendo = ExamsState(
            selectedExamId = null,
            catalogExams = listOf(otro),
            editorCode = "  ex-2026-0002 ",
        )

        val señalado = assertNotNull(examFieldError(escribiendo))
        assertEquals(ExamField.CODE, señalado.field)
        assertEquals("Ya hay otro examen con el código EX-2026-0002.", señalado.message)
        assertEquals(ExamFieldErrorSource.EDITOR, señalado.source)
        assertNull(examFieldErrorTab(escribiendo), "Un choque que se está escribiendo no puede cambiar de pestaña")
    }

    /**
     * Lo que NO se atribuye a ningún campo.
     *
     * Sin error, con un error sin código, con un código que esta pantalla no
     * conoce y con un error que no es de validación. En los cuatro casos la línea
     * al pie sigue siendo la señal —que es lo que era antes— en vez de marcar un
     * campo que el servidor no ha señalado.
     */
    @Test
    fun `un rechazo sin codigo atribuible no marca ningun campo`() {
        val base = ExamsState(selectedExamId = null, editorTitle = "TCAE Mayo", editorSpecialtyId = "s1")
        listOf(
            "sin error" to base,
            "validación sin código" to base.copy(
                actionState = UiAsync.Error(AppError.Validation("La duración debe ser numérica.")),
            ),
            "código desconocido" to base.copy(
                actionState = UiAsync.Error(AppError.Validation("No permitido.", code = "SOME_OTHER_CHECK")),
            ),
            "error que no es de validación" to base.copy(
                actionState = UiAsync.Error(AppError.Network("sin red", code = 503)),
            ),
        ).forEach { (caso, estado) ->
            assertNull(examFieldError(estado), "«$caso» no puede señalar un campo")
            assertNull(examFieldErrorTab(estado), "«$caso» no puede cambiar de pestaña")
        }
    }

    /**
     * Un rechazo del servidor manda sobre el choque que detecta el editor.
     *
     * Los dos apuntan al mismo campo, así que lo que se comprueba es cuál de los
     * dos textos gana y que la pestaña se abre: el rechazo es la razón por la que
     * el examen NO está guardado, y su mensaje lo redacta quien conoce la
     * restricción de la base.
     */
    @Test
    fun `el rechazo del servidor manda sobre el choque que detecta el editor`() {
        val otro = exam("b").copy(code = "EX-2026-0002")
        val estado = ExamsState(
            selectedExamId = null,
            catalogExams = listOf(otro),
            editorCode = "EX-2026-0002",
            actionState = UiAsync.Error(
                AppError.Validation(message = "Ya existe otro examen con ese código.", code = EXAM_ERROR_CODE_DUPLICATE),
            ),
        )

        val señalado = assertNotNull(examFieldError(estado))
        assertEquals("Ya existe otro examen con ese código.", señalado.message)
        assertEquals(ExamFieldErrorSource.SAVE_REJECTED, señalado.source)
        assertEquals(ExamWorkspaceTabId.BASICA, examFieldErrorTab(estado))
    }

    /** Cada campo del mapa vive en una pestaña de verdad del editor. */
    @Test
    fun `cada campo señalable tiene su pestaña y su etiqueta`() {
        assertEquals(
            listOf(ExamWorkspaceTabId.BASICA, ExamWorkspaceTabId.CONFIGURACION),
            ExamField.entries.map { it.tab },
        )
        ExamField.entries.forEach { campo ->
            assertTrue(campo.label.isNotBlank(), "$campo sin etiqueta: el usuario no sabría qué campo es")
            assertTrue(campo.tab in ExamWorkspaceTabId.entries, "$campo apunta a una pestaña que no existe")
        }
    }

    // -----------------------------------------------------------------------
    // Pendientes: tarjeta y franja (P0-2, P1-1, P2-6)
    // -----------------------------------------------------------------------

    /** «Contando preguntas» se resuelve solo: no hace aparecer «Antes de publicar» (P2-6). */
    @Test
    fun `contar preguntas no hace aparecer antes de publicar por si solo`() {
        val guardado = exam("a")
        val abierto = editorStateFor(guardado)

        val contando = abierto.copy(questions = UiAsync.Loading, countedQuestions = mapOf("a" to 4))
        val checks = examChecks(contando, guardado, questionCount = null)
        assertTrue(checks.single { it.id == ExamCheckId.LINKED_QUESTIONS }.transient)
        assertEquals(emptyList(), examPublishPendingChecks(checks), "Contar no es un problema del examen")

        // Terminar de contar y no tener preguntas SÍ es un hallazgo; y un fallo al
        // contar, también.
        val vacio = examChecks(abierto.copy(questions = UiAsync.Empty), guardado, questionCount = 0)
        assertEquals(listOf(ExamCheckId.LINKED_QUESTIONS), examPublishPendingChecks(vacio).map { it.id })
        val fallo = examChecks(abierto.copy(questions = UiAsync.Error(AppError.Network("boom"))), guardado, null)
        assertEquals(listOf(ExamCheckId.LINKED_QUESTIONS), examPublishPendingChecks(fallo).map { it.id })

        // Comprobar los tipos de la especialidad tampoco es un problema.
        val comprobando = examChecks(
            abierto.copy(specialtyCapabilities = UiAsync.Loading, questions = UiAsync.Success(listOf(question("q1", 0)))),
            guardado,
            questionCount = 1,
        )
        assertTrue(comprobando.single { it.id == ExamCheckId.TYPE_ALLOWED }.transient)
        val linea = examSummaryStripLine(comprobando)
        assertEquals("Comprobando el examen…", linea.text)
        assertNull(linea.status)
        assertTrue(linea.details.isEmpty(), "Mientras sólo se comprueba, no hay detalle que abrir")
    }

    /** La franja plegada es UNA línea que nombra lo pendiente (P0-2, P1-1). */
    @Test
    fun `la franja plegada es una sola linea que nombra lo pendiente`() {
        val nuevo = ExamsState(selectedExamId = null, questions = UiAsync.Empty)
        val linea = examSummaryStripLine(examChecks(nuevo, exam = null, questionCount = 0))
        assertEquals("Antes de publicar: título, especialidad, tipo y preguntas.", linea.text)
        assertEquals(ExamCheckStatus.BLOCKING, linea.status)
        assertEquals(
            listOf(ExamCheckId.TITLE, ExamCheckId.SPECIALTY, ExamCheckId.TYPE_ALLOWED, ExamCheckId.LINKED_QUESTIONS),
            linea.details.map { it.id },
        )

        val guardado = exam("a")
        val limpio = editorStateFor(guardado).copy(questions = UiAsync.Success(listOf(question("q1", 0))))
        assertEquals(
            ExamSummaryStripLine(
                text = "Sin nada pendiente: el examen se puede guardar tal y como está.",
                status = ExamCheckStatus.OK,
                details = emptyList(),
            ),
            examSummaryStripLine(examChecks(limpio, guardado, questionCount = 1)),
        )
        val sucio = examSummaryStripLine(examChecks(limpio.copy(editorTitle = "Otro título"), guardado, questionCount = 1))
        assertEquals("Antes de publicar: cambios sin guardar.", sucio.text)
        assertEquals(ExamCheckStatus.WARNING, sucio.status)
        assertEquals(listOf(ExamCheckId.UNSAVED_CHANGES), sucio.details.map { it.id })
    }

    /**
     * El alto mínimo del formulario (P0-2): si con la cabecera a la vista no llega,
     * la cabecera y la franja pasan a desplazarse con la página.
     */
    @Test
    fun `si el formulario no llega a su minimo la cabecera se desplaza con la pagina`() {
        // Columna de 567, cabecera + franja + huecos 245, marco fuera del formulario 139.
        assertEquals(0..245, examEditorCollapseRange(viewportPx = 567, headerBlockPx = 245, chromePx = 139, minFormPx = 200))
        // Con 699 de columna el formulario tiene 315: no se desplaza nada.
        assertEquals(0..0, examEditorCollapseRange(viewportPx = 699, headerBlockPx = 245, chromePx = 139, minFormPx = 200))
        // Justo en el mínimo, tampoco.
        assertEquals(0..0, examEditorCollapseRange(viewportPx = 584, headerBlockPx = 245, chromePx = 139, minFormPx = 200))
        // Tan baja que el marco no cabe ni con el formulario a cero: la cabecera
        // arranca desplazada lo justo para que pestañas y barra se vean enteras.
        assertEquals(84..245, examEditorCollapseRange(viewportPx = 300, headerBlockPx = 245, chromePx = 139, minFormPx = 200))
    }

    // -----------------------------------------------------------------------
    // Rejilla común de seis columnas (P2-2)
    // -----------------------------------------------------------------------

    @Test
    fun `las filas del formulario comparten los bordes de una rejilla de seis columnas`() {
        listOf(860, 672, 1241).forEach { ancho ->
            val identificacion = examFieldGridCells(ancho, 16, listOf(4, 2))
            val ambito = examFieldGridCells(ancho, 16, listOf(2, 2, 2))
            assertEquals(ambito[1].end, identificacion[0].end, "Título y Comunidad no terminan en el mismo píxel ($ancho)")
            assertEquals(ambito[2].start, identificacion[1].start, "Código y Año no empiezan en el mismo píxel ($ancho)")
            assertEquals(0, identificacion.first().start)
            assertEquals(ancho, identificacion.last().end)
            assertEquals(ancho, ambito.last().end)
        }
        // Dos a partes iguales miden lo mismo que con pesos: Configuración no cambia.
        assertEquals(listOf(422, 422), examFieldGridCells(860, 16, examFieldSpans(2, emptyList())).map { it.width })
        assertEquals(listOf(2, 2, 2), examFieldSpans(3, emptyList()))
    }

    // -----------------------------------------------------------------------
    // Especialidad sin catálogo (P2-15)
    // -----------------------------------------------------------------------

    @Test
    fun `con el catalogo caido la comprobacion de especialidad no pide lo imposible`() {
        fun detalle(specialties: UiAsync<List<Specialty>>) =
            examChecks(ExamsState(selectedExamId = null, specialties = specialties), null, 0)
                .single { it.id == ExamCheckId.SPECIALTY }.detail
        assertEquals(
            "No se puede elegir especialidad hasta que cargue el catálogo.",
            detalle(UiAsync.Error(AppError.Network("boom"))),
        )
        assertEquals("Selecciona una especialidad.", detalle(UiAsync.Success(listOf(specialty("s1", "TCAE")))))
        assertEquals("Todavía no hay ninguna especialidad creada.", detalle(UiAsync.Empty))
        assertEquals("Todavía no hay ninguna especialidad creada.", detalle(UiAsync.Success(emptyList())))
    }

    // -----------------------------------------------------------------------
    // Recuento en las tarjetas cerradas (P2-8)
    // -----------------------------------------------------------------------

    /**
     * K2-2: el `total_preguntas` servido de una tarjeta cerrada es un dato desde M1
     * y ya no lleva la marca «≈». Mutación: volver a anteponer la marca pone este
     * test en rojo.
     */
    @Test
    fun `una tarjeta cerrada ensena el recuento servido como un dato`() {
        val abierto = exam("a").copy(questionCount = 5)
        val cerrado = exam("b").copy(questionCount = 12)
        val uno = exam("c").copy(questionCount = 1)
        val vacio = exam("d").copy(questionCount = 0)
        val state = editorStateFor(abierto).copy(questions = UiAsync.Success(listOf(question("q1", 0), question("q2", 1))))

        // El abierto, lo mismo que la cabecera.
        assertEquals("2 preguntas", examCatalogQuestionsLabel(state, abierto))
        // Uno cerrado, el servido tal cual.
        assertEquals("12 preguntas", examCatalogQuestionsLabel(state, cerrado))
        assertEquals("1 pregunta", examCatalogQuestionsLabel(state, uno))
        assertEquals("Sin preguntas", examCatalogQuestionsLabel(state, vacio))
        // Uno cerrado que ya se contó en esta sesión: el recuento de la sesión.
        assertEquals("3 preguntas", examCatalogQuestionsLabel(state.copy(countedQuestions = mapOf("b" to 3)), cerrado))
        assertEquals("Sin preguntas", examCatalogQuestionsLabel(state.copy(countedQuestions = mapOf("b" to 0)), cerrado))
        // Ninguna tarjeta lleva ya la marca de no confirmado.
        listOf(cerrado, uno, vacio).forEach { tarjeta ->
            assertFalse(examCatalogQuestionsLabel(state, tarjeta).contains("≈"), examCatalogQuestionsLabel(state, tarjeta))
        }
    }

    // -----------------------------------------------------------------------
    // Reparto de columnas
    // -----------------------------------------------------------------------

    @Test
    fun `en ventana ancha caben catalogo editor y resumen`() {
        val layout = examWorkspaceColumns(
            totalWidth = 1520.dp,
            isWide = true,
            supportsMasterDetail = true,
        )
        assertTrue(layout.showSummaryColumn)
        assertFalse(layout.stacked)
        assertTrue(layout.editorWidth >= ExamWorkspaceSizing.editorMinWidth)
    }

    // La regla que protege al editor: si las tres columnas lo dejarían por debajo
    // de su mínimo, la que se pliega es la del resumen.
    @Test
    fun `cuando el editor no cabe se pliega el resumen y no el editor`() {
        val layout = examWorkspaceColumns(
            totalWidth = 1180.dp,
            isWide = true,
            supportsMasterDetail = true,
        )
        assertFalse(layout.showSummaryColumn)
        assertTrue(layout.editorWidth >= ExamWorkspaceSizing.editorMinWidth)
    }

    @Test
    fun `en ventana estrecha las columnas se apilan sin volverse movil`() {
        val layout = examWorkspaceColumns(
            totalWidth = 980.dp,
            isWide = false,
            supportsMasterDetail = false,
        )
        assertTrue(layout.stacked)
        assertFalse(layout.showSummaryColumn)
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

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

    private fun capabilities(
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
        specialtyId = "s1",
        limitationMessage = limitation,
        configured = configured,
    )

    private fun exam(
        id: String,
        specialtyId: String = "s1",
        type: String = "rafaga",
        year: Int? = 2026,
    ) = Exam(
        id = id,
        code = "EX-$id",
        specialtyId = specialtyId,
        specialtyName = "Especialidad",
        communityId = null,
        title = "Examen $id",
        description = "Descripción",
        typeExam = type,
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.DRAFT,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = 4,
        durationMinutes = 60,
        year = year,
        isOfficial = false,
        coverUrl = null,
        coverStoragePath = null,
        coverMimeType = null,
        createdAt = now,
        updatedAt = now,
    )

    /** El estado del editor tal y como `selectExam` lo deja al abrir [exam]. */
    private fun editorStateFor(exam: Exam) = ExamsState(
        selectedExamId = exam.id,
        editorTitle = exam.title,
        editorDescription = exam.description,
        editorCode = exam.code,
        editorSpecialtyId = exam.specialtyId,
        editorCommunityId = exam.communityId,
        editorTypeExam = exam.typeExam,
        editorTypeAccess = exam.typeAccess,
        editorPriceTickets = exam.priceTickets.toString(),
        editorYear = exam.year?.toString().orEmpty(),
        editorDurationMinutes = exam.durationMinutes?.toString().orEmpty(),
        editorIsOfficial = exam.isOfficial,
        specialtyCapabilities = UiAsync.Success(capabilities(rafagas = true)),
    )

    private fun question(
        id: String,
        order: Int,
        resources: List<QuestionResource> = emptyList(),
    ) = ExamQuestion(
        id = id,
        examId = "exam",
        title = "Pregunta ${order + 1}",
        statement = "Enunciado ${order + 1}",
        sortOrder = order,
        options = listOf("A", "B").mapIndexed { index, label ->
            QuestionOption(
                id = "$id-$label",
                questionId = id,
                label = label,
                content = "Opción $label",
                isCorrect = index == 0,
                sortOrder = index,
            )
        },
        resources = resources,
        createdAt = now,
        updatedAt = now,
    )

    private fun resource(
        id: String,
        type: QuestionResourceType,
        title: String,
        textBody: String? = null,
    ) = QuestionResource(
        id = id,
        questionId = "q",
        type = type,
        title = title,
        textBody = textBody,
        storagePath = null,
        publicUrl = if (textBody == null) "https://cdn.example/$id" else null,
        mimeType = null,
        sortOrder = 0,
    )
}
