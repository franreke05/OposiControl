package com.oposicontrol.admin.domain.model

/**
 * Detalle completo de un hilo de OposiRed (pantalla de detalle móvil).
 * [content] va sin truncar — a diferencia de [ForumThread.snippet] — y
 * [associatedReportsCount] es un conteo real de reportes PENDIENTES sobre el
 * hilo o sobre alguno de sus comentarios (mismo criterio que
 * [ForumThread.hasPendingReport] y que Informes: nunca discrepan).
 */
data class ForumThreadDetail(
    val id: String,
    val categoryId: String?,
    val categoryName: String,
    val title: String,
    val content: String,
    val status: String,
    val pinned: Boolean,
    val authorName: String,
    val createdAt: String,
    val updatedAt: String,
    val replyCount: Int,
    val associatedReportsCount: Int,
)
