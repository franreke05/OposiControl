package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

/**
 * Modelos propios del banco global de preguntas (pantalla Preguntas,
 * JVM/Desktop). Deliberadamente separados de [ExamQuestion]: esa clase la
 * sigue usando Exámenes (`features/exams`) sin ningún campo adicional por
 * culpa de esta pantalla. Reutiliza [QuestionOption]/[QuestionResource]
 * /[QuestionResourceType]/[QuestionType], que ya eran tipos independientes
 * antes de esta pantalla — reutilizarlos no hace crecer nada.
 */

/** Fila ligera del banco global — sin opciones ni recursos. */
data class QuestionListItem(
    val id: String,
    /** Código humano real generado por backend (`codigo_pregunta`), p.ej. "PREG-000123". */
    val code: String,
    val type: QuestionType?,
    val statement: String,
    val active: Boolean,
    /** Solo presentes cuando la fila viene de [QuestionsRepository.listQuestionsForExam]. */
    val examId: String? = null,
    val position: Int? = null,
)

/** Detalle completo — opciones, solución y recursos, para el editor. */
data class QuestionDetail(
    val id: String,
    val code: String,
    val type: QuestionType?,
    val statement: String,
    val options: List<QuestionOption>,
    /** Justificación (`razonamiento`). Vacío si no hay. */
    val explanation: String,
    /** Referencia normativa/bibliográfica (`fuente_texto`). Vacío si no hay. */
    val source: String,
    val active: Boolean,
    /**
     * `dificultad` del contrato — no editable desde esta pantalla (no está en
     * el diseño aprobado), pero hay que reenviarlo tal cual en cada guardado:
     * el backend no conserva el valor existente si no llega en el PATCH.
     */
    val difficulty: String,
    val resources: List<QuestionResource>,
    /**
     * El contrato V1 de preguntas no expone fecha de creación/actualización
     * (a diferencia de Exámenes). `null` es el valor honesto — nunca se
     * rellena con la hora actual para aparentar un dato que no existe.
     */
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/** Campos editables — lo que viaja en un alta o una actualización. */
data class QuestionDraft(
    val statement: String,
    val type: QuestionType,
    val options: List<QuestionOption>,
    val explanation: String,
    val source: String,
    val active: Boolean,
    val difficulty: String,
)

/** Relación pregunta↔examen conocida (código/título humanos, para pintar la tabla sin pedir UUID). */
data class QuestionExamLink(
    val examId: String,
    val examCode: String,
    val examTitle: String,
    val position: Int,
)

/** Petición de asociación al guardar: examen destino + posición dentro de él. */
data class QuestionExamLinkRequest(
    val examId: String,
    val position: Int,
)
