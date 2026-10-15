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

/**
 * Una página de `GET /api/v1/admin/reportes` (D5-25), en el orden del servidor (`creado_en` ascendente: la cola, lo más
 * antiguo primero). [nextCursor] = null cuando no hay más páginas. [pendingTotal] es el total de reportes `pendiente` del
 * servidor con independencia del filtro, o null si el conteo no se pudo leer (nunca un 0 inventado).
 */
data class ForumReportsPage(
    val items: List<ForumReport>,
    val nextCursor: String?,
    val pendingTotal: Int?,
)
