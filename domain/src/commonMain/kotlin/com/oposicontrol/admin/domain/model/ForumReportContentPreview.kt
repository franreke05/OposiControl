package com.oposicontrol.admin.domain.model

/**
 * Contenido real reportado (hilo o comentario de OposiRed), resuelto por el
 * backend a partir de `tipoEntidad`+`entidadId`. Deliberadamente independiente
 * de [ForumReport]: el estado del reporte (pendiente/resuelto/descartado) y el
 * estado del contenido (`currentState`, p.ej. "publicada"/"publicado") son
 * conceptos distintos que no deben mezclarse.
 */
data class ForumReportContentPreview(
    val type: String,
    val text: String,
    val author: String,
    val createdAt: String,
    val threadTitle: String?,
    val currentState: String,
)
