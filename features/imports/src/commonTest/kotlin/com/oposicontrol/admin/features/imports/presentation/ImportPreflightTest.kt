package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportDuplicate
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.ContentImportSummary
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El preflight: lo que la UI decide ANTES de pulsar «Publicar», una regla por test.
 *
 * Cada regla espeja una de la RPC de publicación, pero la autoridad sigue siendo el
 * servidor: estos tests fijan la previsión del cliente, no el SQL.
 */
class ImportPreflightTest {

    private fun row(
        id: String = "s-1",
        position: Int = 1,
        status: String = "ok",
        title: String = "Enunciado",
        options: Boolean = true,
        correct: String = "A",
        reasoning: String = "",
        error: String? = null,
        duplicate: ContentImportDuplicate? = null,
        link: String? = null,
        annulled: Boolean = false,
        reason: String? = if (annulled) "Anulada por el tribunal" else null,
        questionId: String? = null,
    ) = ContentImportStagingItem(
        id = id,
        position = position,
        title = title,
        optionA = if (options) "A" else "",
        optionB = "B",
        optionC = "C",
        optionD = "D",
        correctAnswer = correct,
        reasoning = reasoning,
        sourceText = "",
        tagText = "",
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
        rows: List<ContentImportStagingItem>,
        status: String = "parseado",
        type: String = "rafaga",
        summary: ContentImportSummary? = null,
    ) = ContentImportBatch(
        id = "b-1",
        examId = "ex-1",
        fileName = "bloque.pdf",
        examType = type,
        status = status,
        detectedCount = rows.size,
        importedCount = 0,
        staging = rows,
        summary = summary,
    )

    private fun exam(
        type: String = "rafaga",
        status: ExamStatus = ExamStatus.DRAFT,
        questions: Int = 10,
    ) = Exam(
        id = "ex-1", code = "EX-1", specialtyId = "sp", specialtyName = "Sp", title = "Examen", description = "",
        typeExam = type, priceTickets = 0, status = status, publishFrom = null, publishedAt = null, sortOrder = 0,
        questionCount = questions,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun kinds(preflight: ImportPublishPreflight) = preflight.blockers.map { it.kind }

    // -----------------------------------------------------------------------
    // Un lote limpio
    // -----------------------------------------------------------------------

    @Test
    fun `un lote limpio se puede publicar y dice cuantas preguntas`() {
        val preflight = importPublishPreflight(batch(listOf(row(), row(id = "s-2"))), exam())
        assertTrue(preflight.canPublish)
        assertEquals(2, preflight.toPublish)
        assertEquals(12, preflight.examTotalAfter)
        assertFalse(preflight.requiresPublishedExamConfirmation)
    }

    // -----------------------------------------------------------------------
    // Filas en error, advertencias, duplicadas
    // -----------------------------------------------------------------------

    @Test
    fun `filas en error bloquean y se cuentan`() {
        val preflight = importPublishPreflight(batch(listOf(row(), row(id = "s-2", error = "mal"), row(id = "s-3", status = "error"))))
        assertEquals(listOf(ImportPreflightKind.ROW_ERRORS), kinds(preflight))
        assertEquals("Corrige los 2 errores antes de publicar.", preflight.blockers.single().message)
    }

    @Test
    fun `una advertencia sin aprobar ni omitir bloquea y aprobarla u omitirla lo levanta`() {
        val pendiente = importPublishPreflight(batch(listOf(row(), row(id = "s-2", status = "advertencia"))))
        assertEquals(listOf(ImportPreflightKind.WARNINGS_PENDING), kinds(pendiente))
        assertTrue(pendiente.blockers.single().message.contains("1 fila con advertencia"), pendiente.blockers.single().message)

        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", status = "aprobada")))).canPublish)
        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", status = "omitida")))).canPublish)
    }

    @Test
    fun `una duplicada del banco sin vincular ni omitir bloquea`() {
        val duplicate = ContentImportDuplicate("banco", "q-1", "PREG-0001")
        val bloqueada = importPublishPreflight(batch(listOf(row(), row(id = "s-2", duplicate = duplicate))))
        assertEquals(listOf(ImportPreflightKind.DUPLICATES_UNRESOLVED), kinds(bloqueada))
        assertTrue(bloqueada.blockers.single().message.contains("1 duplicada sin resolver"))

        // Vinculada (aprobada + vínculo): resuelta.
        val vinculada = row(id = "s-2", status = "aprobada", duplicate = duplicate, link = "q-1")
        assertTrue(importPublishPreflight(batch(listOf(row(), vinculada))).canPublish)
        // Omitida: resuelta.
        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", status = "omitida", duplicate = duplicate)))).canPublish)
    }

    @Test
    fun `una duplicada de lote bloquea hasta omitir una de las dos`() {
        val duplicate = ContentImportDuplicate("lote", null, "", "s-1")
        val bloqueada = importPublishPreflight(batch(listOf(row(), row(id = "s-2", duplicate = duplicate))))
        assertEquals(listOf(ImportPreflightKind.DUPLICATES_UNRESOLVED), kinds(bloqueada))

        // Omitida la gemela original, la otra ya no estorba: la RPC sólo compara filas ok/aprobada.
        val gemelaOmitida = batch(listOf(row(status = "omitida"), row(id = "s-2", duplicate = duplicate)))
        assertTrue(importPublishPreflight(gemelaOmitida).canPublish)
        // Omitida ella misma: resuelta.
        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", status = "omitida", duplicate = duplicate)))).canPublish)
    }

    // -----------------------------------------------------------------------
    // Completitud y razonamiento
    // -----------------------------------------------------------------------

    @Test
    fun `una fila incompleta que no esta omitida bloquea y omitirla lo levanta`() {
        val incompleta = row(id = "s-2", status = "aprobada", title = "")
        val bloqueada = importPublishPreflight(batch(listOf(row(), incompleta)))
        assertEquals(listOf(ImportPreflightKind.ROWS_INCOMPLETE), kinds(bloqueada))
        assertTrue(bloqueada.blockers.single().message.contains("1 fila incompleta"), bloqueada.blockers.single().message)

        assertTrue(importPublishPreflight(batch(listOf(row(), incompleta.copy(parseStatus = "omitida")))).canPublish)
        // Opción vacía o respuesta sin marcar: también incompleta.
        assertEquals(listOf(ImportPreflightKind.ROWS_INCOMPLETE), kinds(importPublishPreflight(batch(listOf(row(options = false))))))
        assertEquals(listOf(ImportPreflightKind.ROWS_INCOMPLETE), kinds(importPublishPreflight(batch(listOf(row(correct = ""))))))
    }

    @Test
    fun `una fila vinculada no necesita estar completa porque usa la pregunta existente`() {
        val vinculada = row(status = "aprobada", title = "", link = "q-9", duplicate = ContentImportDuplicate("banco", "q-9", "PREG-9"))
        assertTrue(importPublishPreflight(batch(listOf(vinculada, row(id = "s-2")))).canPublish)
    }

    @Test
    fun `razonada e inmersiva exigen razonamiento y rafaga no`() {
        val sinRazonamiento = listOf(row(), row(id = "s-2", reasoning = "Porque sí"))
        assertTrue(importPublishPreflight(batch(sinRazonamiento, type = "rafaga")).canPublish)

        listOf("razonado" to "Razonada", "inmersivo" to "Inmersiva").forEach { (wire, label) ->
            val preflight = importPublishPreflight(batch(sinRazonamiento, type = wire))
            assertEquals(listOf(ImportPreflightKind.REASONING_MISSING), kinds(preflight), wire)
            assertTrue(preflight.blockers.single().message.contains(label), preflight.blockers.single().message)
            assertTrue(preflight.blockers.single().message.startsWith("1 fila sin explicación"), preflight.blockers.single().message)
        }
        // Omitida sin razonamiento: no cuenta.
        val omitida = listOf(row(reasoning = "Sí"), row(id = "s-2", status = "omitida"))
        assertTrue(importPublishPreflight(batch(omitida, type = "razonado")).canPublish)
    }

    // -----------------------------------------------------------------------
    // Tipo del examen, tope de 200 y examen publicado
    // -----------------------------------------------------------------------

    @Test
    fun `un lote de un tipo distinto al del examen no se publica`() {
        val preflight = importPublishPreflight(batch(listOf(row(reasoning = "x")), type = "razonado"), exam(type = "rafaga"))
        assertEquals(listOf(ImportPreflightKind.TYPE_MISMATCH), kinds(preflight))
        assertTrue(preflight.blockers.single().message.contains("Razonada"))
        assertTrue(preflight.blockers.single().message.contains("Ráfaga"))
        // Sin examen conocido, la regla no se aplica: no se inventa.
        assertTrue(importPublishPreflight(batch(listOf(row(reasoning = "x")), type = "razonado"), exam = null).canPublish)
    }

    @Test
    fun `el limite de 200 preguntas avisa con el numero y no deja publicar`() {
        val rows = (1..5).map { row(id = "s-$it", position = it) }
        // 198 + 5 = 203 > 200.
        val preflight = importPublishPreflight(batch(rows), exam(questions = 198))
        assertEquals(listOf(ImportPreflightKind.EXAM_LIMIT), kinds(preflight))
        val message = preflight.blockers.single().message
        assertTrue(message.contains("198") && message.contains("203") && message.contains("200"), message)
        assertTrue(message.contains("Omite al menos 3"), message)

        // Justo en el tope: 195 + 5 = 200 cabe.
        assertTrue(importPublishPreflight(batch(rows), exam(questions = 195)).canPublish)
        // Omitiendo tres, cabe.
        val omitidas = rows.mapIndexed { i, r -> if (i < 3) r.copy(parseStatus = "omitida") else r }
        assertTrue(importPublishPreflight(batch(omitidas), exam(questions = 198)).canPublish)
    }

    @Test
    fun `las vinculadas cuentan para el tope como la RPC y el examen desconocido no lo aplica`() {
        // La RPC cuenta como relación nueva TODA fila ok/aprobada sin relación previa con el examen,
        // también las vinculadas a una pregunta del banco; el cliente no sabe cuáles ya están: cuenta todas.
        val vinculadas = (1..5).map { row(id = "s-$it", status = "aprobada", link = "q-$it", duplicate = ContentImportDuplicate("banco", "q-$it", "PREG-$it")) }
        val preflight = importPublishPreflight(batch(vinculadas), exam(questions = 199))
        assertEquals(listOf(ImportPreflightKind.EXAM_LIMIT), kinds(preflight))
        val message = preflight.blockers.single().message
        assertTrue(message.contains("199") && message.contains("204") && message.contains("200"), message)
        assertEquals(204, preflight.examTotalAfter)
        // Justo en el tope con vinculadas: 195 + 5 = 200 cabe.
        assertTrue(importPublishPreflight(batch(vinculadas), exam(questions = 195)).canPublish)
        assertTrue(importPublishPreflight(batch((1..5).map { row(id = "s-$it") }), exam = null).canPublish)
    }

    @Test
    fun `un lote pendiente no se publica y revisado o fallido siguen siendo publicables`() {
        val pendiente = importPublishPreflight(batch(listOf(row()), status = "pendiente"))
        assertEquals(listOf(ImportPreflightKind.BATCH_PENDING), kinds(pendiente))
        assertFalse(pendiente.canPublish)
        assertFalse(importPublishPreflight(batch(listOf(row()), status = " Pendiente ")).canPublish)

        listOf("parseado", "revisado", "fallido").forEach { estado ->
            assertTrue(importPublishPreflight(batch(listOf(row()), status = estado)).canPublish, estado)
        }
    }

    @Test
    fun `un examen publicado pide la confirmacion existente y lo dice antes`() {
        val preflight = importPublishPreflight(batch(listOf(row())), exam(status = ExamStatus.PUBLISHED))
        assertTrue(preflight.canPublish, "es un aviso, no un bloqueo")
        assertTrue(preflight.requiresPublishedExamConfirmation)
        val warning = preflight.warnings.single()
        assertEquals(ImportPreflightKind.EXAM_PUBLISHED, warning.kind)
        assertTrue(warning.message.contains("se verán al instante"))

        // Borrador: sin confirmación. Publicado pero sin nada que publicar: tampoco.
        assertFalse(importPublishPreflight(batch(listOf(row())), exam(status = ExamStatus.DRAFT)).requiresPublishedExamConfirmation)
        assertFalse(importPublishPreflight(batch(listOf(row(status = "omitida"))), exam(status = ExamStatus.PUBLISHED)).requiresPublishedExamConfirmation)
    }

    // -----------------------------------------------------------------------
    // Lote ya importado, vacío, todo omitido, en vuelo
    // -----------------------------------------------------------------------

    @Test
    fun `un lote ya importado no se publica y sus filas importadas no cuentan`() {
        val importado = batch(listOf(row(status = "importada", questionId = "q-1")), status = "importado")
        val preflight = importPublishPreflight(importado, exam(status = ExamStatus.PUBLISHED))
        assertEquals(listOf(ImportPreflightKind.ALREADY_PUBLISHED), kinds(preflight))
        assertFalse(preflight.requiresPublishedExamConfirmation)
        assertEquals(0, preflight.toPublish)
    }

    @Test
    fun `un lote vacio o con todo omitido no tiene nada que publicar`() {
        assertEquals(listOf(ImportPreflightKind.EMPTY_BATCH, ImportPreflightKind.NOTHING_READY).first(), kinds(importPublishPreflight(batch(emptyList()))).first())
        val todoOmitido = importPublishPreflight(batch(listOf(row(status = "omitida"))))
        assertEquals(listOf(ImportPreflightKind.NOTHING_READY), kinds(todoOmitido))
        assertTrue(todoOmitido.blockers.single().message.contains("omitidas"))
    }

    @Test
    fun `mientras se publica el boton esta apagado`() {
        val gate = importPublishGate(ImportState(actionState = UiAsync.Loading), batch(listOf(row())))
        assertFalse(gate.enabled)
        assertEquals("Publicando el lote…", gate.reason)
    }

    // -----------------------------------------------------------------------
    // El gate usa el preflight
    // -----------------------------------------------------------------------

    @Test
    fun `el gate enseña el examen de su estado y usa el preflight`() {
        val state = ImportState(exams = UiAsync.Success(listOf(exam(questions = 199))))
        val gate = importPublishGate(state, batch(listOf(row(), row(id = "s-2"))))
        assertFalse(gate.enabled)
        assertTrue(gate.reason.contains("199") && gate.reason.contains("201"), gate.reason)
        assertEquals(ImportPreflightKind.EXAM_LIMIT, gate.issues.first().kind)

        val publicado = ImportState(exams = UiAsync.Success(listOf(exam(status = ExamStatus.PUBLISHED))))
        val ok = importPublishGate(publicado, batch(listOf(row())))
        assertTrue(ok.enabled)
        assertTrue(ok.requiresPublishedExamConfirmation)
        assertEquals("Se publicará 1 pregunta en el examen del lote.", ok.reason)
    }

    @Test
    fun `el motivo del gate cuenta las vinculadas y las anuladas`() {
        val rows = listOf(
            row(annulled = true),
            row(id = "s-2", status = "aprobada", link = "q-2", duplicate = ContentImportDuplicate("banco", "q-2", "PREG-2")),
        )
        val gate = importPublishGate(ImportState(), batch(rows))
        assertTrue(gate.enabled)
        assertTrue(gate.reason.startsWith("Se publicarán 2 preguntas"), gate.reason)
        assertTrue(gate.reason.contains("1 vinculada a una existente"), gate.reason)
        assertTrue(gate.reason.contains("1 anulada por el tribunal"), gate.reason)
    }

    /** Decisión del propietario 2026-10-20: no se publica una pregunta anulada sin el motivo del tribunal. */
    @Test
    fun `una fila anulada sin motivo bloquea la publicacion y omitirla la desbloquea`() {
        val sinMotivo = importPublishPreflight(batch(listOf(row(), row(id = "s-2", position = 2, annulled = true, reason = "  "))))
        assertFalse(sinMotivo.canPublish)
        val issue = sinMotivo.blockers.single { it.kind == ImportPreflightKind.ANNULMENT_REASON_MISSING }
        assertEquals("1 pregunta anulada no tiene el motivo del tribunal: indícalo en la revisión antes de publicar.", issue.message)
        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", position = 2, annulled = true)))).canPublish, "con motivo se publica")
        assertTrue(importPublishPreflight(batch(listOf(row(), row(id = "s-2", position = 2, status = "omitida", annulled = true, reason = null)))).blockers
            .none { it.kind == ImportPreflightKind.ANNULMENT_REASON_MISSING }, "una fila omitida no se publica")
    }

    // -----------------------------------------------------------------------
    // Vincular a existente: acciones por fila
    // -----------------------------------------------------------------------

    @Test
    fun `una duplicada del banco ofrece omitir vincular y editar pero no aprobar`() {
        val item = row(duplicate = ContentImportDuplicate("banco", "q-77", "PREG-0077"))
        assertEquals(ReviewFilter.DUPLICADA, stagingTone(item))
        val actions = importRowActions(item, batchPublished = false)
        assertTrue(actions.canSkip)
        assertEquals("Vincular a existente (PREG-0077)", actions.linkLabel)
        assertEquals("q-77", actions.linkQuestionId)
        assertTrue(actions.editable)
        assertFalse(actions.canApprove)
        assertFalse(actions.canUnlink)
        assertTrue(importRowProblem(item)!!.contains("PREG-0077"))
    }

    @Test
    fun `una duplicada de lote solo ofrece omitir`() {
        val item = row(duplicate = ContentImportDuplicate("lote", null, "", "s-1"))
        val actions = importRowActions(item, batchPublished = false)
        assertTrue(actions.canSkip)
        assertNull(actions.linkLabel)
        assertNull(actions.linkQuestionId)
        assertEquals("El PDF trae esta pregunta dos veces: omite una de las dos.", importRowProblem(item))
    }

    @Test
    fun `una fila vinculada se lee como vinculada a PREG y permite deshacer`() {
        val item = row(status = "aprobada", link = "q-77", duplicate = ContentImportDuplicate("banco", "q-77", "PREG-0077"))
        assertEquals(ReviewFilter.LISTA, stagingTone(item))
        assertEquals("Vinculada a PREG-0077", importRowLinkLabel(item))
        val actions = importRowActions(item, batchPublished = false)
        assertTrue(actions.canUnlink)
        assertNull(actions.linkLabel)
        // Sin el código conocido, se dice sin inventarlo.
        assertEquals("Vinculada a una pregunta existente", importRowLinkLabel(item.copy(duplicate = null)))
        // Una fila que no está vinculada no dice nada.
        assertNull(importRowLinkLabel(row()))
    }

    @Test
    fun `un vinculo viejo en una fila omitida no cuenta como vinculada`() {
        val item = row(status = "omitida", link = "q-1", duplicate = ContentImportDuplicate("banco", "q-1", "PREG-1"))
        assertFalse(stagingIsLinked(item))
        assertNull(importRowLinkLabel(item))
        assertEquals(ReviewFilter.OMITIDA, stagingTone(item))
    }

    @Test
    fun `con el lote publicado o la fila importada no hay acciones`() {
        val item = row(duplicate = ContentImportDuplicate("banco", "q-77", "PREG-0077"))
        val published = importRowActions(item, batchPublished = true)
        assertFalse(published.editable)
        assertFalse(published.canSkip)
        assertNull(published.linkLabel)

        val imported = importRowActions(row(status = "importada", questionId = "q-1"), batchPublished = false)
        assertFalse(imported.editable)
        assertFalse(imported.canSkip)
        assertFalse(imported.canApprove)
    }

    @Test
    fun `una fila ya omitida no se puede omitir otra vez`() {
        val actions = importRowActions(row(status = "omitida"), batchPublished = false)
        assertFalse(actions.canSkip)
        assertNotNull(actions)
    }

    // -----------------------------------------------------------------------
    // Historial: resumen del servidor, o un guion — nunca un 0 inventado
    // -----------------------------------------------------------------------

    @Test
    fun `el historial usa el resumen del servidor cuando viene`() {
        val summary = ContentImportSummary(ready = 38, withErrors = 2, warnings = 1, skipped = 3, imported = 0)
        val line = importBatchHistoryLine(batch(emptyList(), summary = summary).copy(detectedCount = 44))
        assertEquals("Ráfaga · 44 detectadas · 38 listas · 2 con error · 1 con advertencia · 3 omitidas", line)
    }

    @Test
    fun `sin resumen ni staging el historial no inventa numeros y pone un guion`() {
        val line = importBatchHistoryLine(batch(emptyList()).copy(detectedCount = 40))
        assertTrue(line.contains("— listas"), line)
        assertTrue(line.contains("— con error"), line)
        assertFalse(line.contains("0 listas"), line)
        assertFalse(line.contains("0 con error"), line)
        assertNull(importBatchCounts(batch(emptyList())))
    }

    @Test
    fun `sin resumen pero con staging cargado se cuenta de las filas`() {
        val line = importBatchHistoryLine(batch(listOf(row(), row(id = "s-2", error = "mal"), row(id = "s-3", status = "omitida"))))
        assertEquals("Ráfaga · 3 detectadas · 1 listas · 1 con error · 1 omitidas", line)
    }

    @Test
    fun `una cifra que falta en el resumen no se rellena con cero`() {
        val line = importBatchHistoryLine(batch(emptyList(), summary = ContentImportSummary(ready = 5)).copy(detectedCount = 5))
        assertEquals("Ráfaga · 5 detectadas · 5 listas", line)
    }

    @Test
    fun `un lote publicado muestra sus importadas en el historial`() {
        val summary = ContentImportSummary(ready = 0, withErrors = 0, warnings = 0, skipped = 1, imported = 9)
        val line = importBatchHistoryLine(batch(emptyList(), status = "importado", summary = summary).copy(detectedCount = 10))
        assertTrue(line.contains("9 importadas"), line)
        assertTrue(line.contains("0 con error"), line)
    }

    // -----------------------------------------------------------------------
    // Inmersiva sin recursos
    // -----------------------------------------------------------------------

    @Test
    fun `la inmersiva se importa sin recursos y esta declarado`() {
        assertEquals("NOT_IMPLEMENTED", IMMERSIVE_RESOURCE_IMPORT)
        assertTrue(IMMERSIVE_RESOURCE_IMPORT_NOTICE.contains("sin recursos"))
        val recursos = importCapability("Resolver recursos")
        assertEquals(ImportCapabilityLevel.NOT_FOUND, recursos.level)
        assertTrue(recursos.note.contains("IMMERSIVE_RESOURCE_IMPORT = NOT_IMPLEMENTED"), recursos.note)
    }
}
