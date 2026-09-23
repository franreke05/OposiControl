package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.domain.model.TesterReport
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TesterReportModelsTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    private fun report(
        authorName: String = "Arturo García",
        type: String = "pregunta_incorrecta",
        description: String = "Al finalizar el examen la respuesta marcada como correcta no coincide con el temario.",
        status: String = "pendiente",
        createdAt: String = "2026-08-23T11:48:00Z",
    ) = TesterReport(
        id = "t1",
        authorName = authorName,
        type = type,
        description = description,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun toUi_mapsAllFourRealStatuses() {
        assertEquals(TesterReportStatusKind.PENDING, report(status = "pendiente").toUi(now).statusKind)
        assertEquals("Pendiente", report(status = "pendiente").toUi(now).statusLabel)
        assertEquals(TesterReportStatusKind.RESOLVED, report(status = "resuelto").toUi(now).statusKind)
        assertEquals(TesterReportStatusKind.DISCARDED, report(status = "descartado").toUi(now).statusKind)
        assertEquals(TesterReportStatusKind.REVIEWED, report(status = "revisado").toUi(now).statusKind)
    }

    @Test
    fun toUi_neverInterpretsAnUnknownStatus_showsItAsIs() {
        val ui = report(status = "algo_no_confirmado").toUi(now)
        assertEquals(TesterReportStatusKind.UNKNOWN, ui.statusKind)
        assertEquals("Algo_no_confirmado", ui.statusLabel)
    }

    @Test
    fun toUi_humanizesTheRealTypeValue_withoutInventingACatalog() {
        assertEquals("Pregunta incorrecta", report(type = "pregunta_incorrecta").toUi(now).typeLabel)
        assertEquals("Otro tipo raro", report(type = "otro_tipo_raro").toUi(now).typeLabel)
    }

    @Test
    fun toUi_blankType_fallsBackToAnHonestPlaceholder() {
        assertEquals("Incidencia", report(type = "").toUi(now).typeLabel)
    }

    @Test
    fun toUi_missingAuthor_fallsBackToUsuario_neverAUuid() {
        assertEquals("Usuario", report(authorName = "").toUi(now).authorName)
    }

    @Test
    fun toUi_veryLongSubjectAndPreview_arePreservedAsIs() {
        val longType = "a".repeat(300)
        val longDescription = "b".repeat(1000)
        val ui = report(type = longType, description = longDescription).toUi(now)
        assertEquals(longType.replaceFirstChar { it.uppercase() }, ui.typeLabel)
        assertEquals(longDescription, ui.descriptionPreview)
    }

    @Test
    fun toUi_semanticsLabel_readsAsOneCoherentUnit() {
        val ui = report().toUi(now)
        assertTrue(ui.semanticsLabel.startsWith("Incidencia pendiente."))
        assertTrue(ui.semanticsLabel.contains("Pregunta incorrecta"))
        assertTrue(ui.semanticsLabel.contains("Arturo García"))
        assertTrue(ui.semanticsLabel.endsWith("Abrir."))
    }

    @Test
    fun formatTesterRelativeTime_neverFabricatesAValue_whenTimestampIsInvalid() {
        assertEquals("", formatTesterRelativeTime("not-a-timestamp", now))
        assertEquals("", formatTesterRelativeTime("", now))
    }

    @Test
    fun formatTesterRelativeTime_humanizesRecentTimestamps() {
        assertEquals("hace 12 min", formatTesterRelativeTime("2026-08-23T11:48:00Z", now))
    }
}
