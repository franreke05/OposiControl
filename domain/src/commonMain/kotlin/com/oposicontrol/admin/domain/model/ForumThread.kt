package com.oposicontrol.admin.domain.model

/**
 * Un hilo real de OposiRed (publicaciones_oposired) para la pantalla de
 * supervisión "Foro". [replyCount] y [hasPendingReport] se resuelven en el
 * backend sin N+1 (ver AdminV1Gateway.listForoHilos): nunca se calculan aquí
 * lanzando una consulta por hilo.
 */
data class ForumThread(
    val id: String,
    val categoryId: String?,
    val categoryName: String,
    val title: String,
    val snippet: String,
    val status: String,
    val pinned: Boolean,
    val authorName: String,
    val createdAt: String,
    val updatedAt: String,
    val replyCount: Int,
    val hasPendingReport: Boolean,
)
