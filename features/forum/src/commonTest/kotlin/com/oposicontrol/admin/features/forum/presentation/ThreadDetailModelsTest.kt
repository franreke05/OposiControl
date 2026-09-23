package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadDetailModelsTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    private fun threadDetail(
        title: String = "Duda sobre el Tema 12",
        authorName: String = "Arturo",
        replyCount: Int = 14,
        associatedReportsCount: Int = 0,
        createdAt: String = "2026-08-23T11:42:00Z",
    ) = ForumThreadDetail(
        id = "t1",
        categoryId = "c1",
        categoryName = "Policía Nacional",
        title = title,
        content = "No entiendo por qué esta respuesta es la correcta según el temario oficial.",
        status = "publicada",
        pinned = false,
        authorName = authorName,
        createdAt = createdAt,
        updatedAt = createdAt,
        replyCount = replyCount,
        associatedReportsCount = associatedReportsCount,
    )

    @Test
    fun toUi_mapsReplyCountLabel() {
        assertEquals("Respuestas · 14", threadDetail(replyCount = 14).toUi(now).replyCountLabel)
        assertEquals("Respuestas · 0", threadDetail(replyCount = 0).toUi(now).replyCountLabel)
    }

    @Test
    fun toUi_veryLongTitle_isPreservedAsIs_noTruncation() {
        val longTitle = "a".repeat(500)
        val ui = threadDetail(title = longTitle).toUi(now)
        assertEquals(longTitle, ui.title)
    }

    @Test
    fun toUi_blankTitle_fallsBackToAnHonestPlaceholder() {
        assertEquals("Hilo sin título", threadDetail(title = "").toUi(now).title)
    }

    @Test
    fun toUi_missingAuthor_fallsBackToUsuario_neverAUuid() {
        assertEquals("Usuario", threadDetail(authorName = "").toUi(now).authorName)
    }

    @Test
    fun toUi_associatedReportsCount_passedThroughAsIs() {
        assertEquals(0, threadDetail(associatedReportsCount = 0).toUi(now).associatedReportsCount)
        assertEquals(3, threadDetail(associatedReportsCount = 3).toUi(now).associatedReportsCount)
    }

    @Test
    fun toUi_relativeTime_humanizesRecentTimestamps() {
        assertEquals("hace 18 min", threadDetail(createdAt = "2026-08-23T11:42:00Z").toUi(now).timeLabel)
    }

    @Test
    fun toUi_neverFabricatesATime_whenTimestampIsInvalid() {
        assertEquals("", threadDetail(createdAt = "not-a-timestamp").toUi(now).timeLabel)
    }

    private fun reply(
        authorName: String = "Marta",
        content: String = "Yo tampoco lo entendía al principio.",
        createdAt: String = "2026-08-23T11:50:00Z",
    ) = ForumReply(id = "r1", authorName = authorName, content = content, status = "publicado", createdAt = createdAt)

    @Test
    fun replyToUi_missingAuthor_fallsBackToUsuario() {
        assertEquals("Usuario", reply(authorName = "").toUi(now).authorName)
    }

    @Test
    fun replyToUi_veryLongContent_isPreservedAsIs() {
        val longContent = "b".repeat(1000)
        assertEquals(longContent, reply(content = longContent).toUi(now).content)
    }

    @Test
    fun replyToUi_semanticsLabel_readsAsOneCoherentUnit() {
        val ui = reply().toUi(now)
        assertTrue(ui.semanticsLabel.contains("Marta"))
        assertTrue(ui.semanticsLabel.contains("Yo tampoco lo entendía al principio."))
    }
}
