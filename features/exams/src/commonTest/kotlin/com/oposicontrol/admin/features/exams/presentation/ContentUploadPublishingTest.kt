package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * La revisión compacta debe decidir «¿se puede publicar?» con la MISMA regla que la RPC: publica las
 * filas `ok` y `aprobada`, salta las `omitida` y se bloquea con `error`/`advertencia`. Antes contaba
 * las `ok` como «requieren revisión» y sólo activaba el botón con `aprobada > 0`.
 */
class ContentUploadPublishingTest {

    private fun row(id: String, status: String, error: String? = null) = ContentImportStagingItem(
        id = id, position = 1, title = "T", optionA = "a", optionB = "b", optionC = "c", optionD = "d",
        correctAnswer = "A", reasoning = "", sourceText = "", tagText = "", originalBlock = "bloque",
        parseStatus = status, parseError = error,
    )

    private fun batch(vararg rows: ContentImportStagingItem, status: String = "parseado") = ContentImportBatch(
        id = "b1", examId = "e1", examType = "rafaga", fileName = "t.pdf", status = status,
        detectedCount = rows.size, importedCount = 0, staging = rows.toList(),
    )

    @Test
    fun aCleanBatchOfOkRowsIsPublishableWithoutApprovingAnything() {
        val summary = contentUploadPublishSummary(batch(row("1", "ok"), row("2", "ok")))

        assertEquals(2, summary.ok)
        assertEquals(0, summary.approved)
        assertEquals(2, summary.publishable)
        assertTrue(summary.canPublish("parseado", busy = false))
        assertTrue(summary.note().contains("publicarán 2 preguntas"), summary.note())
    }

    @Test
    fun okPlusApprovedAreCountedAndSkippedRowsAreNot() {
        val summary = contentUploadPublishSummary(batch(row("1", "ok"), row("2", "aprobada"), row("3", "omitida")))

        assertEquals(2, summary.publishable)
        assertEquals(0, summary.blocking)
        val note = summary.note()
        assertTrue(note.contains("publicarán 2 preguntas"), note)
        assertTrue(note.contains("1 listas y 1 aprobadas"), note)
        assertFalse(note.contains("sólo", ignoreCase = true), note)
    }

    @Test
    fun oneRowIsSingular() {
        val note = contentUploadPublishSummary(batch(row("1", "aprobada"))).note()

        assertTrue(note.contains("publicará 1 pregunta"), note)
    }

    @Test
    fun errorsAndWarningsBlockPublishing() {
        listOf("error", "advertencia").forEach { blocking ->
            val summary = contentUploadPublishSummary(batch(row("1", "ok"), row("2", blocking)))

            assertEquals(1, summary.blocking, blocking)
            assertFalse(summary.canPublish("parseado", busy = false), blocking)
            assertTrue(summary.note().contains("Resuelve la fila"), summary.note())
        }
    }

    @Test
    fun anApprovedRowNoLongerBlocksEvenIfItCarriesAStaleDiagnostic() {
        val summary = contentUploadPublishSummary(batch(row("1", "aprobada", error = "diagnóstico viejo")))

        assertEquals(0, summary.blocking)
        assertTrue(summary.canPublish("parseado", busy = false))
    }

    @Test
    fun allSkippedOrAlreadyImportedOrBusyDisablesThePublishButton() {
        val skipped = contentUploadPublishSummary(batch(row("1", "omitida")))
        assertFalse(skipped.canPublish("parseado", busy = false))

        val ready = contentUploadPublishSummary(batch(row("1", "ok")))
        assertFalse(ready.canPublish("importado", busy = false))
        assertFalse(ready.canPublish("parseado", busy = true))
    }

    @Test
    fun theButtonIsCalledPublishBatchNotPublishApproved() {
        assertEquals("Publicar lote", CONTENT_UPLOAD_PUBLISH_LABEL)
    }

    @Test
    fun theConfirmationErrorIsRewrittenAndAnyOtherErrorIsUntouched() {
        val confirmation = AppError.Validation("genérico", code = "IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED")
        val rewritten = confirmation.asPublishError()
        assertTrue(rewritten.message.contains("ya está publicado"), rewritten.message)
        assertTrue(rewritten.message.contains("escritorio"), rewritten.message)

        val other = AppError.Validation("otra cosa", code = "OTRO")
        assertSame(other, other.asPublishError())
        val network = AppError.Network("sin red")
        assertSame(network, network.asPublishError())
    }
}
