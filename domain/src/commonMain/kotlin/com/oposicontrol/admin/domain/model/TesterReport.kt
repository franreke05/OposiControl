package com.oposicontrol.admin.domain.model

/**
 * Una incidencia real de OposiTester (feedback_pregunta): un usuario reporta
 * un problema con una pregunta concreta durante un intento de examen. [status]
 * real confirmado: "pendiente" (inicial) → "resuelto" | "descartado" |
 * "revisado" (tras resolver). [type] es el valor real de tipo_feedback, sin
 * un catálogo cerrado conocido — se muestra humanizado, nunca interpretado.
 */
data class TesterReport(
    val id: String,
    val authorName: String,
    val type: String,
    val description: String,
    val status: String,
    val createdAt: String,
)

/**
 * Página de [TesterReport] paginada por cursor keyset, más reciente primero.
 * [nextCursor] null significa que no hay más páginas — nunca se pagina con
 * OFFSET.
 */
data class TesterReportPage(
    val items: List<TesterReport>,
    val nextCursor: String?,
)
