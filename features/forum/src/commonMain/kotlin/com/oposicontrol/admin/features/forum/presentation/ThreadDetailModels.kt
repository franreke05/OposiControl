package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import kotlinx.datetime.Instant

data class ThreadDetailUi(
    val categoryLabel: String?,
    val pinned: Boolean,
    val title: String,
    val authorName: String,
    val timeLabel: String,
    val content: String,
    val associatedReportsCount: Int,
    val replyCountLabel: String,
)

fun ForumThreadDetail.toUi(now: Instant): ThreadDetailUi {
    val relative = formatForumRelativeTime(createdAt, now)
    return ThreadDetailUi(
        categoryLabel = categoryName.takeIf { it.isNotBlank() },
        pinned = pinned,
        title = title.ifBlank { "Hilo sin título" },
        authorName = authorName.ifBlank { "Usuario" },
        timeLabel = relative,
        content = content,
        associatedReportsCount = associatedReportsCount,
        replyCountLabel = if (replyCount == 1) "Respuestas · 1" else "Respuestas · $replyCount",
    )
}

data class ForumReplyUi(
    val id: String,
    val authorName: String,
    val timeLabel: String,
    val content: String,
    val semanticsLabel: String,
)

fun ForumReply.toUi(now: Instant): ForumReplyUi {
    val author = authorName.ifBlank { "Usuario" }
    val relative = formatForumRelativeTime(createdAt, now)
    return ForumReplyUi(
        id = id,
        authorName = author,
        timeLabel = relative,
        content = content,
        semanticsLabel = buildString {
            append("Respuesta de ")
            append(author)
            if (relative.isNotBlank()) {
                append(". Publicada ")
                append(relative)
            }
            append(". ")
            append(content)
        },
    )
}
