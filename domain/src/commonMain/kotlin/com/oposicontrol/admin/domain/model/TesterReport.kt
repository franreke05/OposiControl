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

/**
 * Contexto real de la pregunta asociada a una incidencia, resuelto por el
 * backend sin N+1. [specialtyName] es null cuando la especialidad no se
 * pudo resolver — nunca se muestra el uuid en su lugar. No incluye examen:
 * `preguntas` no guarda una relación 1:1 con examen (la relación real es
 * M:N vía `preguntas_examen`), así que ese dato queda fuera hasta que haya
 * una forma de resolverlo sin ambigüedad.
 */
data class TesterQuestionContext(
    val code: String,
    val title: String,
    val specialtyName: String?,
)

/**
 * Detalle completo de una incidencia de OposiTester (Screen 07). [question]
 * es null cuando la pregunta asociada ya no existe o no se pudo resolver —
 * nunca se fabrica un contexto falso.
 */
data class TesterReportDetail(
    val id: String,
    val authorName: String,
    val type: String,
    val description: String,
    val status: String,
    val createdAt: String,
    val question: TesterQuestionContext?,
)
