package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_MAIN
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_RESERVE
import com.oposicontrol.admin.domain.model.reserveOrderOf
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importación por lotes de un examen OFICIAL (FA37): bloque PRINCIPAL/RESERVA, lote duplicado y avisos de publicación.
 * El preflight es una previsión; el servidor vuelve a comprobarlo todo dentro de la transacción.
 */
class ImportReservesPreflightTest {
    private fun row(id: String, position: Int, block: String = IMPORT_BLOCK_MAIN, status: String = "ok", annulled: Boolean = false) =
        ContentImportStagingItem(
            id = id, position = position, title = "Pregunta $position", optionA = "A", optionB = "B", optionC = "C", optionD = "D",
            correctAnswer = "A", reasoning = "", sourceText = "", tagText = "", originalBlock = "", parseStatus = status,
            annulled = annulled, annulmentReason = if (annulled) "Anulada por el tribunal" else null, block = block,
        )

    private fun batch(rows: List<ContentImportStagingItem>, duplicateOf: String? = null, status: String = "parseado") =
        ContentImportBatch(
            id = "b-1", examId = "ex-1", fileName = "oficial.pdf", examType = "rafaga", status = status,
            detectedCount = rows.size, importedCount = 0, staging = rows, examCode = "OFI-2024", duplicateOfBatchId = duplicateOf,
        )

    private fun exam(official: Boolean = true, questions: Int = 50) = Exam(
        id = "ex-1", code = "OFI-2024", specialtyId = "sp", specialtyName = "Sp", title = "Oficial", description = "",
        typeExam = "rafaga", priceTickets = 0, status = ExamStatus.DRAFT, publishFrom = null, publishedAt = null, sortOrder = 0,
        questionCount = questions, isOfficial = official,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun kinds(p: ImportPublishPreflight) = p.blockers.map { it.kind }

    @Test
    fun `un lote repetido bloquea la publicacion y apunta al lote anterior`() {
        val p = importPublishPreflight(batch(listOf(row("a", 1)), duplicateOf = "lote-viejo"), exam())
        assertTrue(ImportPreflightKind.DUPLICATE_LOT in kinds(p))
        assertTrue(p.blockers.first { it.kind == ImportPreflightKind.DUPLICATE_LOT }.message.contains("lote-viejo"))
        assertFalse(p.canPublish)
    }

    @Test
    fun `sin lote repetido no hay bloqueo de ese tipo`() {
        val p = importPublishPreflight(batch(listOf(row("a", 1))), exam())
        assertFalse(ImportPreflightKind.DUPLICATE_LOT in kinds(p))
        assertTrue(p.canPublish)
    }

    @Test
    fun `las reservas en un examen que no es oficial bloquean`() {
        val p = importPublishPreflight(batch(listOf(row("a", 1), row("r", 2, IMPORT_BLOCK_RESERVE))), exam(official = false))
        assertTrue(ImportPreflightKind.RESERVES_NEED_OFFICIAL in kinds(p))
    }

    @Test
    fun `solo reservas en un examen sin principales bloquea`() {
        val p = importPublishPreflight(batch(listOf(row("r1", 1, IMPORT_BLOCK_RESERVE), row("r2", 2, IMPORT_BLOCK_RESERVE))), exam(questions = 0))
        assertTrue(ImportPreflightKind.RESERVES_WITHOUT_MAIN in kinds(p))
    }

    @Test
    fun `las reservas con principales ya publicadas se pueden publicar y avisan de como se numeran`() {
        val p = importPublishPreflight(batch(listOf(row("r1", 1, IMPORT_BLOCK_RESERVE), row("r2", 2, IMPORT_BLOCK_RESERVE))), exam(questions = 50))
        assertTrue(p.canPublish)
        assertEquals(2, p.reserves)
        assertTrue(p.warnings.any { it.kind == ImportPreflightKind.RESERVES_NOTE })
    }

    @Test
    fun `principales y reservas del mismo fichero publican juntas`() {
        val rows = listOf(row("a", 1), row("b", 2, annulled = true), row("r1", 3, IMPORT_BLOCK_RESERVE))
        val p = importPublishPreflight(batch(rows), exam(questions = 0))
        assertTrue(p.canPublish, p.blockers.toString())
        assertEquals(3, p.toPublish)
        assertEquals(1, p.reserves)
        assertEquals(1, p.annulled)
    }

    @Test
    fun `el numero de reserva sigue el orden del fichero e ignora las omitidas`() {
        val rows = listOf(
            row("a", 1), row("r1", 2, IMPORT_BLOCK_RESERVE), row("r2", 3, IMPORT_BLOCK_RESERVE, status = "omitida"),
            row("r3", 4, IMPORT_BLOCK_RESERVE),
        )
        assertNull(reserveOrderOf(rows, rows[0]))
        assertEquals(1, reserveOrderOf(rows, rows[1]))
        assertNull(reserveOrderOf(rows, rows[2]), "una omitida no se publica, no tiene número")
        assertEquals(2, reserveOrderOf(rows, rows[3]))
    }

    @Test
    fun `las metricas cuentan las reservas que se publicarian`() {
        val m = batch(listOf(row("a", 1), row("r1", 2, IMPORT_BLOCK_RESERVE), row("r2", 3, IMPORT_BLOCK_RESERVE, status = "omitida"))).metrics()
        assertEquals(1, m.reserves)
    }

    @Test
    fun `los avisos y problemas del servidor tienen texto de operador`() {
        listOf("MORE_RESERVES_THAN_MAX", "ANNULLED_RESERVE_SKIPPED").forEach {
            assertFalse(importPublishWarningText(it).startsWith("Aviso del servidor"), it)
        }
        listOf("RESERVE_WITHOUT_MAIN", "RESERVE_ORDER_GAPS", "RESERVE_BEFORE_MAIN", "REEMPLAZO_NOT_ENOUGH_RESERVES", "DEMO_CONFLICT").forEach {
            assertFalse(importPublishProblemText(it).startsWith("Falta completar"), it)
        }
        assertTrue(importPublishProblemText("OTRO").startsWith("Falta completar"))
    }

    @Test
    fun `una explicacion con marcado invalido bloquea y una valida o sin etiquetas no`() {
        fun withReasoning(text: String) = row("a", 1).copy(reasoning = text)
        val bad = importPublishPreflight(batch(listOf(withReasoning("**sin cerrar"))), exam())
        assertTrue(ImportPreflightKind.MARKUP_INVALID in kinds(bad))
        val good = importPublishPreflight(batch(listOf(withReasoning("Con **negrita** y [color:red]color[/color]"))), exam())
        assertFalse(ImportPreflightKind.MARKUP_INVALID in kinds(good))
        val plain = importPublishPreflight(batch(listOf(withReasoning("Texto sin etiquetas"))), exam())
        assertFalse(ImportPreflightKind.MARKUP_INVALID in kinds(plain))
    }
}
