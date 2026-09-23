package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportContentPreview
import com.oposicontrol.admin.domain.model.ForumReportDetail
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportsModelsTest {
    private val now = Instant.parse("2026-08-22T12:00:00Z")

    private fun report(
        status: String = "pendiente",
        entityType: String = "hilo",
        resolvedAt: String = "",
    ) = ForumReport(
        id = "r1",
        entityType = entityType,
        entityId = "e1",
        reason = "Información incorrecta",
        description = "La respuesta correcta es otra.",
        status = status,
        createdAt = "2026-08-22T10:00:00Z",
        reporterId = "u1",
        reporterName = "Arturo",
        resolvedAt = resolvedAt,
    )

    @Test
    fun toDetailUi_withNoContentPreview_reportsAnHonestGapInsteadOfFabricating() {
        val detail = ForumReportDetail(report = report(), contentPreview = null)

        val ui = detail.toDetailUi(now)

        assertEquals(ReportStatusKind.PENDING, ui.statusKind)
        assertEquals("Pendiente", ui.statusLabel)
        assertNull(ui.contentPreview)
    }

    @Test
    fun toDetailUi_withRealContentPreview_mapsThreadTitleAuthorAndState() {
        val preview = ForumReportContentPreview(
            type = "comentario",
            text = "Esto no es correcto.",
            author = "Marta",
            createdAt = "2026-08-22T11:30:00Z",
            threadTitle = "Duda sobre el tema 4",
            currentState = "publicado",
        )
        val detail = ForumReportDetail(report = report(entityType = "comentario"), contentPreview = preview)

        val ui = detail.toDetailUi(now)

        checkNotNull(ui.contentPreview).let {
            assertEquals("Comentario", it.typeLabel)
            assertEquals("Marta", it.author)
            assertEquals("Esto no es correcto.", it.text)
            assertEquals("Duda sobre el tema 4", it.threadTitle)
            assertEquals("Publicado", it.currentStateLabel)
            assertTrue(it.timeLabel.isNotBlank())
        }
    }

    @Test
    fun toDetailUi_resolvedLabel_onlyAppearsWhenReportIsNotPending() {
        val pending = ForumReportDetail(report = report(status = "pendiente"), contentPreview = null)
        val resolved = ForumReportDetail(
            report = report(status = "resuelto", resolvedAt = "2026-08-22T11:00:00Z"),
            contentPreview = null,
        )

        assertNull(pending.toDetailUi(now).resolvedLabel)
        assertEquals("22/08/2026 · 11:00", resolved.toDetailUi(now).resolvedLabel)
    }

    @Test
    fun toUi_forListItem_labelsStatusAndBuildsSemanticsDescription() {
        val ui = report(status = "descartado").toUi(now)

        assertEquals(ReportStatusKind.DISCARDED, ui.statusKind)
        assertEquals("Descartado", ui.statusLabel)
        assertTrue(ui.semanticsLabel.contains("Descartado"))
        assertTrue(ui.semanticsLabel.endsWith("Abrir informe."))
    }

    @Test
    fun formatRelativeTime_neverFabricatesAValue_whenTimestampIsUnparseable() {
        assertEquals("", formatRelativeTime("no-es-una-fecha", now))
        assertEquals("", formatRelativeTime("", now))
    }
}
