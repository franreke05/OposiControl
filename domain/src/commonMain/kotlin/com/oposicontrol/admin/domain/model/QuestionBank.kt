package com.oposicontrol.admin.domain.model

import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
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
    /** Solo presentes cuando la consulta es la de un examen ([QuestionListQuery.examId]). */
    val examId: String? = null,
    val position: Int? = null,
)

/** El orden del banco, con el valor exacto que entiende `GET /preguntas?orden=` (KQ0-4). */
enum class QuestionListSort(val wireValue: String) {
    MOST_RECENT("recientes"),
    BY_CODE("codigo"),
}

/**
 * Qué página del banco se pide (KQ0-4, QN-07).
 *
 * Todo se resuelve EN EL SERVIDOR: búsqueda, tipo, estado, examen,
 * orden y paginación. Antes el cliente descargaba el banco entero —sin cota— y
 * filtraba lo cargado, y el filtro «Inactivas» filtraba un conjunto del que las
 * inactivas ya se habían ido.
 *
 * Con [examId] la lista es la de ese examen, en su orden (posición); [sort] no aplica.
 */
data class QuestionListQuery(
    val search: String = "",
    val type: QuestionType? = null,
    val active: QuestionActiveFilter = QuestionActiveFilter.ACTIVE,
    val examId: String? = null,
    val sort: QuestionListSort = QuestionListSort.MOST_RECENT,
    val offset: Int = 0,
    val limit: Int = 25,
)

/** Una página del banco con el TOTAL exacto de la consulta, no sólo lo que trae. */
data class QuestionPage(
    val items: List<QuestionListItem>,
    val total: Int,
    val offset: Int,
    val limit: Int,
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
     * el diseño aprobado); se reenvía tal cual en cada guardado. `""` es «sin
     * definir» (columna NULL) y nunca un `"media"` inventado (KQ0-2): el servidor
     * lee un vacío como «no la toques».
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

/**
 * Un examen al que está vinculada una pregunta, tal y como lo responde
 * `GET /preguntas/{id}/examenes` (KQ3-2): la lista REAL, no la inferida de la sesión.
 *
 * [status] es `borrador`, `publicado` o `archivado`: el diálogo de archivar y el de
 * quitar avisan cuando el examen está publicado, porque ahí el cambio se ve al
 * instante. [examType] es el vocabulario de examen (`rafaga`, `razonado`,
 * `inmersivo`) y decide si el tipo de la pregunta se puede cambiar (KQ3-3).
 */
data class QuestionExamLink(
    val examId: String,
    val examCode: String,
    val examTitle: String,
    val position: Int,
    val examType: String = "",
    val status: String = "",
    /** La pregunta va anulada en ESE examen (M10): dato de la relación. */
    val annulled: Boolean = false,
    val annulmentReason: String? = null,
) {
    val isPublished: Boolean get() = status.equals("publicado", ignoreCase = true)
}
