package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportDuplicate
import com.oposicontrol.admin.domain.model.ContentImportSummary
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pantalla de Importación afirma, comprobado sin pintar.
 *
 * El eje de estos tests es la honestidad: que los tres tipos digan lo que de
 * verdad admiten, que no aparezca ningún identificador interno ni ruta local, y
 * que lo que el contrato NO tiene esté declarado como tal en vez de simulado.
 */
class ImportPresentationTest {

    private fun row(
        id: String = "s-1",
        position: Int = 1,
        title: String = "¿Cuál de las siguientes es correcta?",
        correct: String = "A",
        topic: String = "Tema 28",
        reasoning: String = "",
        source: String = "",
        status: String = "ok",
        error: String? = null,
        annulled: Boolean = false,
        reason: String? = null,
        duplicate: ContentImportDuplicate? = null,
        link: String? = null,
        questionId: String? = null,
        options: String = "Opción",
    ) = ContentImportStagingItem(
        id = id,
        position = position,
        title = title,
        optionA = if (options.isEmpty()) "" else "$options A",
        optionB = "$options B",
        optionC = "$options C",
        optionD = "$options D",
        correctAnswer = correct,
        reasoning = reasoning,
        sourceText = source,
        tagText = topic,
        originalBlock = "",
        parseStatus = status,
        parseError = error,
        questionId = questionId,
        linkQuestionId = link,
        duplicate = duplicate,
        annulled = annulled,
        annulmentReason = reason,
    )

    private fun batch(
        rows: List<ContentImportStagingItem> = listOf(row()),
        status: String = "parseado",
        type: String = "rafaga",
        imported: Int = 0,
        summary: ContentImportSummary? = null,
        examId: String = "ex-1",
    ) = ContentImportBatch(
        id = "5f2c9a10-3b4d-4e6f-8a9b-0c1d2e3f4a5b",
        examId = examId,
        fileName = "simulacro_tema_28.pdf",
        examType = type,
        status = status,
        detectedCount = rows.size,
        importedCount = imported,
        staging = rows,
        summary = summary,
    )

    // -----------------------------------------------------------------------
    // Tipos y sus reglas
    // -----------------------------------------------------------------------

    @Test
    fun `los tres tipos se nombran con el vocabulario de pregunta`() {
        assertEquals("Ráfaga", importTypeLabel(ContentExamType.RAFAGA))
        assertEquals("Razonada", importTypeLabel(ContentExamType.RAZONADO))
        assertEquals("Inmersiva", importTypeLabel(ContentExamType.INMERSIVO))
    }

    /** El alias legacy se LEE; nunca se ofrece como opción ni se escribe. */
    @Test
    fun `el alias legacy guiado se lee como razonada y no se ofrece`() {
        assertEquals(ContentExamType.RAZONADO, importTypeFromWire("guiado"))
        assertEquals("razonado", ContentExamType.RAZONADO.apiValue)
        assertTrue(importTypeChoices(ContentExamType.RAFAGA).none { it.rule.label.lowercase().contains("guiad") })
    }

    @Test
    fun `rafaga admite lo basico y rechaza explicacion, fuente y recursos`() {
        val rule = importTypeRule(ContentExamType.RAFAGA)
        assertEquals("Ráfaga", rule.label)
        assertTrue(rule.admits.contains("Opciones A–D"))
        assertTrue(rule.admits.contains("Respuesta correcta"))
        assertTrue(rule.rejects.contains("Explicación"))
        assertTrue(rule.rejects.contains("Fuente"))
        assertTrue(rule.rejects.contains("Recursos"))
    }

    @Test
    fun `razonada admite explicacion y fuente pero no recursos`() {
        val rule = importTypeRule(ContentExamType.RAZONADO)
        assertTrue(rule.admits.contains("Explicación"))
        assertTrue(rule.admits.contains("Fuente"))
        assertTrue(rule.rejects.any { it.contains("Recursos") })
    }

    /**
     * Inmersiva admite explicación y fuente, pero la IMPORTACIÓN no trae recursos:
     * el staging no tiene ningún campo donde ponerlos. Se dice en la tarjeta en vez
     * de dejar que alguien prepare un PDF con códigos de audio.
     */
    @Test
    fun `inmersiva no promete recursos en la importacion`() {
        val rule = importTypeRule(ContentExamType.INMERSIVO)
        assertTrue(rule.admits.contains("Explicación"))
        val recursos = rule.rejects.single { it.startsWith("Recursos") }
        assertTrue(recursos.contains("Preguntas"), recursos)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Resolver recursos").level)
    }

    @Test
    fun `solo hay un tipo elegido y los otros dos se pueden pulsar`() {
        val choices = importTypeChoices(ContentExamType.INMERSIVO)
        assertEquals(3, choices.size)
        assertEquals(1, choices.count { it.selected })
        assertEquals(2, choices.count { it.selectable })
        assertEquals(
            "Inmersiva. Con explicación. Elegido.",
            importTypeChoiceDescription(choices.single { it.selected }),
        )
    }

    // -----------------------------------------------------------------------
    // Etapas
    // -----------------------------------------------------------------------

    @Test
    fun `sin archivo el flujo esta en la etapa de archivo`() {
        val steps = importStages(ImportState())
        assertEquals(listOf("Tipo", "Archivo", "Revisión", "Publicación"), steps.map { it.label })
        assertEquals(ImportStepState.DONE, steps[0].state)
        assertEquals(ImportStepState.PENDING, steps[1].state)
        assertEquals("Sin archivo", steps[1].detail)
    }

    @Test
    fun `con el PDF elegido la etapa de archivo esta en curso`() {
        val steps = importStages(ImportState(pickedPdfName = "bloque.pdf"))
        assertEquals(ImportStepState.ACTIVE, steps[1].state)
        assertEquals("bloque.pdf", steps[1].detail)
    }

    @Test
    fun `mientras sube, la revision se marca en curso y lo dice`() {
        val steps = importStages(ImportState(pickedPdfName = "b.pdf", actionState = UiAsync.Loading))
        assertEquals(ImportStepState.ACTIVE, steps[2].state)
        assertEquals("Analizando…", steps[2].detail)
    }

    @Test
    fun `con lote abierto la revision esta en curso y la publicacion pendiente`() {
        val steps = importStages(ImportState(activeBatch = UiAsync.Success(batch())))
        assertEquals(ImportStepState.ACTIVE, steps[2].state)
        assertEquals("1 filas", steps[2].detail)
        assertEquals(ImportStepState.PENDING, steps[3].state)
    }

    /** Un lote `importado` es un final feliz: la etapa de publicación queda hecha y dice «Publicado». */
    @Test
    fun `un lote importado marca la publicacion como hecha`() {
        val steps = importStages(ImportState(activeBatch = UiAsync.Success(batch(status = "importado", imported = 1))))
        assertEquals(ImportStepState.DONE, steps[3].state)
        assertEquals("Publicado", steps[3].detail)
        assertEquals(ImportStepState.DONE, steps[2].state)
    }

    /** Un lote `fallido` sí es un problema, y no se disfraza. */
    @Test
    fun `un lote fallido marca la etapa de publicacion como problema`() {
        val steps = importStages(ImportState(activeBatch = UiAsync.Success(batch(status = "fallido"))))
        assertEquals(ImportStepState.BLOCKED, steps[3].state)
        assertEquals("Fallido", steps[3].detail)
    }

    // -----------------------------------------------------------------------
    // El lote: nunca un identificador interno
    // -----------------------------------------------------------------------

    /** No existe código humano de lote: se nombra por su archivo, jamás por su id. */
    @Test
    fun `el lote se nombra por su archivo y nunca por su identificador interno`() {
        val b = batch()
        assertEquals("simulacro_tema_28.pdf", importBatchLabel(b))
        assertFalse(importBatchLabel(b).contains(b.id))
        assertFalse(importBatchLabel(b).contains("-4e6f-"))
        assertEquals("Lote sin nombre de archivo", importBatchLabel(b.copy(fileName = "  ")))
    }

    @Test
    fun `los estados del lote son los que el backend manda`() {
        assertEquals("Pendiente", importBatchStatusLabel("pendiente"))
        assertEquals("Pendiente de revisión", importBatchStatusLabel("parseado"))
        assertEquals("Revisado", importBatchStatusLabel("revisado"))
        assertEquals("Publicado", importBatchStatusLabel("importado"))
        assertEquals("Fallido", importBatchStatusLabel("fallido"))
        assertEquals("Sin estado", importBatchStatusLabel(""))
        // Un estado desconocido se enseña tal cual, no se traduce a «Desconocido».
        assertEquals("revisando", importBatchStatusLabel("revisando"))
    }

    /** F1: sólo `importado` es publicado, con tono positivo; `fallido` es negativo; el vocabulario muerto ya no lo es. */
    @Test
    fun `importado es publicado y el vocabulario muerto ya no lo es`() {
        assertTrue(importBatchIsPublished("importado"))
        assertEquals(StatusTone.POSITIVE, importBatchStatusTone("importado"))
        assertEquals(StatusTone.NEGATIVE, importBatchStatusTone("fallido"))
        assertEquals(StatusTone.ATTENTION, importBatchStatusTone("parseado"))
        listOf("parseado", "revisado", "pendiente", "fallido", "publicado", "parcial").forEach {
            assertFalse(importBatchIsPublished(it), "«$it» no es un lote publicado")
        }
        // `publicado` y `parcial` ya no son estados del lote: se enseñan tal cual, sin traducirlos.
        assertEquals("parcial", importBatchStatusLabel("parcial"))
        assertEquals(StatusTone.UNKNOWN, importBatchStatusTone("parcial"))
    }

    @Test
    fun `el tipo del lote se lee con vocabulario de pregunta`() {
        assertEquals("Ráfaga", importBatchTypeLabel(batch(type = "rafaga")))
        assertEquals("Razonada", importBatchTypeLabel(batch(type = "guiado")))
        assertEquals("Inmersiva", importBatchTypeLabel(batch(type = "inmersivo")))
    }

    // -----------------------------------------------------------------------
    // Filas
    // -----------------------------------------------------------------------

    @Test
    fun `una fila correcta sale como lista y sin problema`() {
        val item = row()
        assertEquals(ReviewFilter.LISTA, stagingTone(item))
        assertNull(importRowProblem(item))
        assertEquals("A", importRowCorrectAnswer(item))
    }

    /** El mensaje del servidor se enseña TAL CUAL: no se reescribe ni se traduce. */
    @Test
    fun `el motivo de un error es el que manda el servidor`() {
        val item = row(status = "ok", error = "No existe el examen EX-042.")
        assertEquals(ReviewFilter.ERROR, stagingTone(item))
        assertEquals("No existe el examen EX-042.", importRowProblem(item))
    }

    /** KI3-5 (IMP-18): `advertencia` es el valor VIVO del CHECK y es un aviso, no un error. */
    @Test
    fun `una advertencia del parser se pinta como aviso y no como error`() {
        val item = row(status = "advertencia", error = "Ráfaga no publica explicación ni fuente: se descartarán al publicar.")
        assertEquals(ReviewFilter.ADVERTENCIA, stagingTone(item))
    }

    @Test
    fun `un error sin explicacion del servidor no se deja mudo`() {
        val item = row(status = "fallida")
        assertEquals(ReviewFilter.ERROR, stagingTone(item))
        assertEquals("El servidor la ha rechazado sin explicar el motivo.", importRowProblem(item))
    }

    @Test
    fun `un duplicado se dice y no se fusiona`() {
        val item = row(status = "duplicada_candidata")
        assertEquals(ReviewFilter.DUPLICADA, stagingTone(item))
        assertTrue(importRowProblem(item)!!.contains("ya existe"), importRowProblem(item)!!)
        assertEquals("Duplicada", importRowToneLabel(ReviewFilter.DUPLICADA))
        // Ver la pregunta existente no es posible: no llega ninguna referencia.
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Ver la pregunta existente").level)
        // Omitirla sí, y no borra nada.
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Omitir duplicado").level)
    }

    @Test
    fun `una fila omitida se distingue de un error y de una advertencia`() {
        val item = row(status = "omitida")
        assertEquals(ReviewFilter.OMITIDA, stagingTone(item))
        assertEquals("Omitida", importRowToneLabel(ReviewFilter.OMITIDA))
        assertEquals("Advertencia", importRowToneLabel(ReviewFilter.ADVERTENCIA))
        assertEquals("Marcada para omitir: no se publicará.", importRowProblem(item))
    }

    /** F1: una fila `importada` ya está en el examen; con o sin texto de error suelto NUNCA es un error. */
    @Test
    fun `una fila importada tiene su propio tono y nunca es error`() {
        val item = row(status = "importada", questionId = "q-1")
        assertEquals(ReviewFilter.IMPORTADA, stagingTone(item))
        assertEquals("Importada", importRowToneLabel(ReviewFilter.IMPORTADA))
        assertEquals(StatusTone.POSITIVE, importRowToneStatusTone(ReviewFilter.IMPORTADA))
        assertNull(importRowProblem(item))
        // Aunque arrastre un error viejo del parser o un duplicado.
        assertEquals(ReviewFilter.IMPORTADA, stagingTone(item.copy(parseError = "error viejo")))
        assertEquals(ReviewFilter.IMPORTADA, stagingTone(item.copy(duplicate = ContentImportDuplicate("banco", "q-1", "PREG-1"))))
    }

    /** F1: `aprobada` es lista, `advertencia` exige decidir y `error` sigue siendo error. */
    @Test
    fun `el vocabulario vivo de la fila se lee entero`() {
        assertEquals(ReviewFilter.LISTA, stagingTone(row(status = "ok")))
        assertEquals(ReviewFilter.LISTA, stagingTone(row(status = "aprobada")))
        assertEquals(ReviewFilter.ADVERTENCIA, stagingTone(row(status = "advertencia")))
        assertEquals(ReviewFilter.OMITIDA, stagingTone(row(status = "omitida")))
        assertEquals(ReviewFilter.IMPORTADA, stagingTone(row(status = "importada")))
        assertEquals(ReviewFilter.ERROR, stagingTone(row(status = "error")))
    }

    /** La anulación NO es un estado de parseo: `ok` + anulada es una fila lista y anulada. */
    @Test
    fun `anulada no cambia el tono de la fila`() {
        val item = row(status = "ok", annulled = true, reason = "Error del tribunal")
        assertEquals(ReviewFilter.LISTA, stagingTone(item))
        assertEquals("Anulada por el tribunal: Error del tribunal", importRowAnnulmentNote(item))
        assertEquals("Anulada por el tribunal", importRowAnnulmentNote(item.copy(annulmentReason = "  ")))
        assertNull(importRowAnnulmentNote(row()))
        // Misma etiqueta y mismo tono que la pregunta anulada de la lista del examen (features/exams).
        assertEquals("Anulada", IMPORT_ANNULLED_CHIP)
        assertEquals(StatusTone.ATTENTION, IMPORT_ANNULLED_TONE)
    }

    @Test
    fun `sin enunciado o sin tema se dice, no se deja un hueco`() {
        assertEquals("Sin enunciado", importRowTitle(row(title = "   ")))
        assertEquals("Sin tema", importRowTopic(row(topic = "")))
        assertEquals("Sin marcar", importRowCorrectAnswer(row(correct = "")))
    }

    /** El tema llega como texto libre: no se disfraza de código canónico. */
    @Test
    fun `el tema se ensena tal cual y no como codigo`() {
        assertEquals("Tema 28", importRowTopic(row(topic = "Tema 28")))
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Resolver tema").level)
    }

    // -----------------------------------------------------------------------
    // Respuesta correcta: parser estricto
    // -----------------------------------------------------------------------

    @Test
    fun `solo A B C o D son respuesta valida y un uno no se corrige`() {
        assertTrue(importAnswerIsValid("A"))
        assertTrue(importAnswerIsValid(" d "))
        assertFalse(importAnswerIsValid("1"))
        assertFalse(importAnswerIsValid("E"))
        assertFalse(importAnswerIsValid(""))
        assertEquals(setOf("A", "B", "C", "D"), IMPORT_VALID_ANSWERS)
    }

    // -----------------------------------------------------------------------
    // Publicación
    // -----------------------------------------------------------------------

    /** Y concuerda: «1 errores» y «1 preguntas» se leían como una plantilla sin terminar. */
    @Test
    fun `con errores no se puede publicar y se dice cuantos con buena concordancia`() {
        val uno = batch(listOf(row(), row(id = "s-2", error = "CORRECTA debe ser A, B, C o D.")))
        val gateUno = importPublishGate(ImportState(), uno)
        assertFalse(gateUno.enabled)
        assertEquals("Corrige el error antes de publicar.", gateUno.reason)

        val dos = batch(
            listOf(
                row(),
                row(id = "s-2", error = "CORRECTA debe ser A, B, C o D."),
                row(id = "s-3", error = "No existe el examen EX-042."),
            ),
        )
        assertEquals("Corrige los 2 errores antes de publicar.", importPublishGate(ImportState(), dos).reason)
    }

    @Test
    fun `sin errores se puede publicar y se dice que va a pasar`() {
        val una = importPublishGate(ImportState(), batch())
        assertTrue(una.enabled)
        assertEquals("Se publicará 1 pregunta en el examen del lote.", una.reason)

        val varias = importPublishGate(ImportState(), batch(listOf(row(), row(id = "s-2"))))
        assertTrue(varias.enabled)
        assertEquals("Se publicarán 2 preguntas en el examen del lote.", varias.reason)
    }

    /** Omitir es una decisión, no un fallo: los avisos no bloquean. */
    @Test
    fun `las filas omitidas no bloquean la publicacion`() {
        val b = batch(listOf(row(), row(id = "s-2", status = "omitida")))
        assertTrue(importPublishGate(ImportState(), b).enabled)
    }

    @Test
    fun `un lote vacio o ya publicado no se publica`() {
        assertFalse(importPublishGate(ImportState(), batch(rows = emptyList())).enabled)
        assertFalse(importPublishGate(ImportState(), batch(status = "importado")).enabled)
    }

    @Test
    fun `si todo esta omitido no hay nada que publicar`() {
        val b = batch(listOf(row(status = "omitida")))
        val gate = importPublishGate(ImportState(), b)
        assertFalse(gate.enabled)
        assertTrue(gate.reason.contains("omitidas"), gate.reason)
    }

    /** F1: la publicación ES atómica, y ningún texto ni capacidad dice lo contrario. */
    @Test
    fun `la publicacion es atomica y ningun texto dice lo contrario`() {
        assertTrue(IMPORT_ATOMIC_NOTICE.contains("atómica"), IMPORT_ATOMIC_NOTICE)
        assertFalse(IMPORT_ATOMIC_NOTICE.contains("no es atómica"))
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Publicar atómicamente").level)
        importCapabilities().forEach { capability ->
            val text = "${capability.name} ${capability.note}".lowercase()
            assertFalse(text.contains("parcial"), "«${capability.name}» aún habla de publicación parcial")
            assertFalse(text.contains("no hay todo-o-nada"), text)
        }
    }

    /** Tras publicar: lote «Publicado», filas importadas sin error, sin «Corrige N errores» y el botón apagado con su motivo. */
    @Test
    fun `tras publicar no hay errores ni se puede volver a publicar`() {
        val published = batch(
            listOf(
                row(status = "importada", questionId = "q-1"),
                row(id = "s-2", status = "importada", questionId = "q-2", error = "error viejo"),
            ),
            status = "importado",
            imported = 2,
        )
        val metrics = published.metrics()
        assertEquals(0, metrics.errors)
        assertEquals(2, metrics.imported)
        val gate = importPublishGate(ImportState(), published)
        assertFalse(gate.enabled)
        assertEquals("Este lote ya se ha publicado.", gate.reason)
        assertFalse(gate.issues.any { it.kind == ImportPreflightKind.ROW_ERRORS })
        assertEquals("Publicado", importBatchStatusLabel(published.status))
        assertEquals(StatusTone.POSITIVE, importBatchStatusTone(published.status))
    }

    // -----------------------------------------------------------------------
    // Guía del PDF: sin OCR y sin códigos que no existen
    // -----------------------------------------------------------------------

    @Test
    fun `se exige PDF con texto y no se promete OCR`() {
        assertTrue(IMPORT_PDF_REQUIREMENT.contains("texto seleccionable"))
        assertFalse(IMPORT_PDF_REQUIREMENT.lowercase().contains("ocr"))
        assertFalse(IMPORT_PDF_REQUIREMENT.lowercase().contains("escanead") && IMPORT_PDF_REQUIREMENT.contains("sí"))
    }

    /**
     * La guía anterior anunciaba `ID_GLOBAL`, `ID_EXAMEN`, `TEMA`, `ID_AUDIO`,
     * `ID_VIDEO` e `ID_IMAGEN`. Ninguno llega al staging: prometerlos hacía
     * preparar PDF con columnas que el servidor ignora.
     */
    @Test
    fun `la guia no anuncia campos que la importacion no lee`() {
        ContentExamType.entries.forEach { type ->
            val texto = importCodeGuide(type).joinToString(" ") { "${it.field} ${it.example} ${it.note}" }
            listOf("ID_GLOBAL", "ID_EXAMEN", "ID_AUDIO", "ID_VIDEO", "ID_IMAGEN", "PREG-", "AUD-", "VID-", "IMG-")
                .forEach { prohibido ->
                    assertFalse(texto.contains(prohibido), "«$type» sigue anunciando $prohibido")
                }
        }
        assertTrue(IMPORT_NO_CODES_NOTICE.contains("se elige aquí"), IMPORT_NO_CODES_NOTICE)
    }

    @Test
    fun `la guia crece con el tipo`() {
        assertEquals(4, importCodeGuide(ContentExamType.RAFAGA).size)
        assertEquals(6, importCodeGuide(ContentExamType.RAZONADO).size)
        assertEquals(6, importCodeGuide(ContentExamType.INMERSIVO).size)
    }

    @Test
    fun `la plantilla se declara no disponible en vez de ofrecer un enlace falso`() {
        assertEquals("Plantilla todavía no disponible", IMPORT_TEMPLATE_BLOCKED)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Descargar plantilla").level)
    }

    // -----------------------------------------------------------------------
    // Nada de rutas locales ni identificadores internos
    // -----------------------------------------------------------------------

    @Test
    fun `el tamano del archivo se ensena y la ruta local nunca`() {
        assertEquals("Tamaño desconocido", importFileSizeLabel(0))
        assertEquals("512 B", importFileSizeLabel(512))
        assertEquals("2 KB", importFileSizeLabel(2048))
        assertEquals("3 MB", importFileSizeLabel(3 * 1024 * 1024))
        // Ninguna de estas etiquetas puede contener una ruta.
        listOf(importFileSizeLabel(2048), importBatchLabel(batch())).forEach { texto ->
            assertFalse(texto.contains("\\"), texto)
            assertFalse(texto.contains("C:"), texto)
            assertFalse(texto.contains("file://"), texto)
        }
    }

    // -----------------------------------------------------------------------
    // Matriz de honestidad
    // -----------------------------------------------------------------------

    /**
     * La UI se pinta desde esta matriz. Lo que declare tiene que coincidir con lo
     * que el repositorio de importación ofrece de verdad.
     */
    @Test
    fun `la matriz declara lo que el contrato no tiene`() {
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Arrastrar y soltar").level)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Resolver examen por fila").level)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Resolver tema").level)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Resolver recursos").level)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Descargar plantilla").level)
        assertEquals(ImportCapabilityLevel.NOT_FOUND, importCapability("Descartar lote").level)

        assertEquals(ImportCapabilityLevel.REAL, importCapability("Publicar atómicamente").level)
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Vincular a existente").level)

        assertEquals(ImportCapabilityLevel.PARTIAL, importCapability("Parsear PDF").level)
        assertEquals(ImportCapabilityLevel.PARTIAL, importCapability("Validar filas").level)
        assertEquals(ImportCapabilityLevel.PARTIAL, importCapability("Detectar duplicado").level)

        assertEquals(ImportCapabilityLevel.REAL, importCapability("Seleccionar tipo").level)
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Seleccionar examen destino").level)
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Editar staging").level)
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Publicar lote").level)
        assertEquals(ImportCapabilityLevel.REAL, importCapability("Ver importaciones recientes").level)
    }

    @Test
    fun `toda capacidad declarada lleva su motivo`() {
        importCapabilities().forEach { capability ->
            assertTrue(capability.note.isNotBlank(), "«${capability.name}» no dice por qué")
        }
    }

    // -----------------------------------------------------------------------
    // Filtros y búsqueda de revisión
    // -----------------------------------------------------------------------

    @Test
    fun `el filtro y la busqueda acotan sobre lo cargado`() {
        val rows = listOf(
            row(id = "a", title = "Higiene de manos"),
            row(id = "b", title = "Constitución", status = "omitida"),
            row(id = "c", title = "Otra", error = "boom"),
        )
        val state = ImportState(activeBatch = UiAsync.Success(batch(rows)))
        assertEquals(listOf("a", "b", "c"), state.visibleStaging().map { it.id })
        assertEquals(listOf("a"), state.copy(reviewFilter = ReviewFilter.LISTA).visibleStaging().map { it.id })
        assertEquals(listOf("c"), state.copy(reviewFilter = ReviewFilter.ERROR).visibleStaging().map { it.id })
        assertEquals(listOf("b"), state.copy(reviewSearch = "constit").visibleStaging().map { it.id })
    }

    @Test
    fun `las metricas del lote cuentan cada estado una vez`() {
        val rows = listOf(
            row(id = "a"),
            row(id = "b", status = "omitida"),
            row(id = "c", status = "duplicada_candidata"),
            row(id = "d", error = "boom"),
            row(id = "e", status = "advertencia"),
            row(id = "f", status = "importada", questionId = "q-1", annulled = true),
        )
        val metrics = batch(rows).metrics()
        assertEquals(6, metrics.detected)
        assertEquals(1, metrics.ready)
        assertEquals(1, metrics.skipped)
        assertEquals(1, metrics.warnings)
        assertEquals(1, metrics.imported)
        assertEquals(1, metrics.duplicates)
        assertEquals(1, metrics.errors)
        assertEquals(1, metrics.annulled)
    }

    // -----------------------------------------------------------------------
    // Historial
    // -----------------------------------------------------------------------

    @Test
    fun `el historial distingue vacio de fallo`() {
        val vacio = ImportState(batches = UiAsync.Empty)
        val fallo = ImportState(batches = UiAsync.Error(AppError.Network("sin red")))
        assertTrue(vacio.batches is UiAsync.Empty)
        assertTrue(fallo.batches is UiAsync.Error)
        // Y un fallo del historial no impide preparar una importación nueva.
        assertEquals(ImportStepState.DONE, importStages(fallo)[0].state)
    }
}
