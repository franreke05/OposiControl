package com.oposicontrol.admin.domain.model

/** Reporte + contenido real reportado (si se pudo resolver). Solo se usa en la
 * pantalla de detalle; la lista sigue usando [ForumReport] a secas. */
data class ForumReportDetail(
    val report: ForumReport,
    val contentPreview: ForumReportContentPreview?,
)
