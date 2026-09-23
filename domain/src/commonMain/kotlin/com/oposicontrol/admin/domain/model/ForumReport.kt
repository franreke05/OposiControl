package com.oposicontrol.admin.domain.model

/** Reporte de contenido del foro (OposiRed) pendiente de moderación. */
data class ForumReport(
    val id: String,
    val entityType: String,
    val entityId: String,
    val reason: String,
    val description: String,
    val status: String,
    val createdAt: String,
    val reporterId: String,
    val reporterName: String,
    /** ISO timestamp de cuando se resolvió/descartó, vacío si sigue pendiente. */
    val resolvedAt: String = "",
)
