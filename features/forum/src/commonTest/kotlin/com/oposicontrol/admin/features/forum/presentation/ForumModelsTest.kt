package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.domain.model.ForumThread
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForumModelsTest {
    private val now = Instant.parse("2026-08-22T12:00:00Z")

    private fun thread(
        title: String = "Duda sobre el Tema 12",
        replyCount: Int = 14,
        pinned: Boolean = false,
        hasPendingReport: Boolean = false,
        categoryName: String = "Policía Nacional",
        createdAt: String = "2026-08-22T11:52:00Z",
    ) = ForumThread(
        id = "t1",
        categoryId = "c1",
        categoryName = categoryName,
        title = title,
        snippet = "No entiendo por qué esta respuesta...",
        status = "publicada",
        pinned = pinned,
        authorName = "Arturo",
        createdAt = createdAt,
        updatedAt = createdAt,
        replyCount = replyCount,
        hasPendingReport = hasPendingReport,
    )

    @Test
    fun toUi_mapsReplyCountSingularAndPlural() {
        assertEquals("1 respuesta", thread(replyCount = 1).toUi(now).replyCountLabel)
        assertEquals("14 respuestas", thread(replyCount = 14).toUi(now).replyCountLabel)
        assertEquals("0 respuestas", thread(replyCount = 0).toUi(now).replyCountLabel)
    }

    @Test
    fun toUi_blankTitleFallsBackToAnHonestPlaceholder_neverBlank() {
        val ui = thread(title = "").toUi(now)
        assertEquals("Hilo sin título", ui.title)
    }

    @Test
    fun toUi_semanticsLabel_readsAsOneCoherentUnit() {
        val ui = thread().toUi(now)
        assertTrue(ui.semanticsLabel.startsWith("Hilo. Duda sobre el Tema 12"))
        assertTrue(ui.semanticsLabel.contains("Policía Nacional"))
        assertTrue(ui.semanticsLabel.contains("14 respuestas"))
        assertTrue(ui.semanticsLabel.endsWith("Abrir hilo."))
    }

    @Test
    fun toUi_semanticsLabel_mentionsAPendingReportOnlyWhenThereIsOne() {
        assertTrue(thread(hasPendingReport = true).toUi(now).semanticsLabel.contains("reporte pendiente"))
        assertFalse(thread(hasPendingReport = false).toUi(now).semanticsLabel.contains("reporte pendiente"))
    }

    @Test
    fun toUi_reflectsPinnedAndReportedFlagsAsIs() {
        val ui = thread(pinned = true, hasPendingReport = true).toUi(now)
        assertTrue(ui.pinned)
        assertTrue(ui.hasPendingReport)
    }

    @Test
    fun toUi_blankCategory_hasNoCategoryLabel() {
        val ui = thread(categoryName = "").toUi(now)
        assertEquals(null, ui.categoryLabel)
    }

    @Test
    fun formatForumRelativeTime_neverFabricatesAValue_whenTimestampIsUnparseable() {
        assertEquals("", formatForumRelativeTime("not-a-real-timestamp", now))
        assertEquals("", formatForumRelativeTime("", now))
    }

    @Test
    fun formatForumRelativeTime_humanizesRecentTimestamps() {
        assertEquals("hace 8 min", formatForumRelativeTime("2026-08-22T11:52:00Z", now))
    }
}
