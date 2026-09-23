package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.domain.model.ForumThread
import kotlinx.datetime.Instant

/** Único selector real que soporta el backend hoy: actividad reciente, o
 * hilos con un reporte pendiente real (ver AdminV1Gateway.listForoHilos). */
enum class ForumMainFilter { RECENT, REPORTED }

/** Opción de filtro por categoría real (categorias_oposired), derivada de las
 * categorías vistas en las páginas ya cargadas — mismo criterio que el filtro
 * secundario de Informes (ReportsModels.ReportSecondaryFilters): no existe un
 * catálogo completo de categorías expuesto todavía, así que no se inventa uno
 * aparte con una llamada adicional. */
data class ForumCategoryOption(val id: String, val name: String)

data class ForumThreadUi(
    val id: String,
    val categoryLabel: String?,
    val title: String,
    val snippet: String,
    val pinned: Boolean,
    val authorName: String,
    val timeLabel: String,
    val replyCountLabel: String,
    val hasPendingReport: Boolean,
    val semanticsLabel: String,
)

fun ForumThread.toUi(now: Instant): ForumThreadUi {
    val safeTitle = title.ifBlank { "Hilo sin título" }
    val relative = formatForumRelativeTime(createdAt, now)
    val replyLabel = if (replyCount == 1) "1 respuesta" else "$replyCount respuestas"
    return ForumThreadUi(
        id = id,
        categoryLabel = categoryName.takeIf { it.isNotBlank() },
        title = safeTitle,
        snippet = snippet,
        pinned = pinned,
        authorName = authorName,
        timeLabel = relative,
        replyCountLabel = replyLabel,
        hasPendingReport = hasPendingReport,
        semanticsLabel = buildString {
            append("Hilo. ")
            append(safeTitle)
            if (categoryName.isNotBlank()) {
                append(". ")
                append(categoryName)
            }
            append(". ")
            append(replyLabel)
            if (relative.isNotBlank()) {
                append(". Publicado ")
                append(relative)
            }
            if (hasPendingReport) append(". Tiene un reporte pendiente.")
            append(". Abrir hilo.")
        },
    )
}

/** Mismo criterio que el resto de pantallas (p.ej. ReportsModels.formatRelativeTime):
 * nunca fabrica un valor si [iso] no se puede interpretar como instante real. */
fun formatForumRelativeTime(iso: String, now: Instant): String {
    if (iso.isBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val minutes = (now - instant).inWholeMinutes
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        minutes < 60 * 24 * 7 -> "hace ${minutes / (60 * 24)} d"
        iso.length >= 10 -> "el ${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}"
        else -> ""
    }
}
