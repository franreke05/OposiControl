package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus

/**
 * De dónde salen los exámenes del escaparate del Market.
 *
 * Es una interfaz mínima y no el `ContentRepository` entero: aquel tiene una
 * treintena de operaciones de escritura y un doble de test suyo obligaría a
 * implementarlas todas para probar una lista de solo lectura.
 */
fun interface PublishedExamsSource {
    /** Todos los exámenes PUBLICADOS, de cualquier tipo. */
    suspend fun load(): AppResult<List<Exam>>
}

/** Filtro por tipo de examen. `ALL` no filtra. */
enum class ExamTypeFilter(val label: String, val type: ContentExamType?) {
    ALL("Todos", null),
    RAFAGA("Ráfagas", ContentExamType.RAFAGA),
    RAZONADO("Razonados", ContentExamType.RAZONADO),
    INMERSIVO("Inmersivos", ContentExamType.INMERSIVO),
}

/**
 * Lo que el escaparate enseña: publicados y no borrados.
 *
 * El estado se decide AQUÍ y no en el backend porque `refreshAllExams` devuelve
 * todo el catálogo —borradores y archivados incluidos— y el escaparate solo
 * puede enseñar lo que el usuario final ve de verdad. Un borrador con portada
 * lista no está a la venta.
 *
 * Orden: especialidad, orden manual y título. Es el mismo criterio con el que
 * se agrupa el catálogo en Exámenes, así que quien compara las dos pantallas
 * encuentra cada examen en el mismo sitio.
 */
fun publishedExams(exams: List<Exam>): List<Exam> =
    exams
        .filter { it.status == ExamStatus.PUBLISHED && it.deletedAt == null }
        .sortedWith(
            compareBy<Exam> { it.specialtyName.lowercase() }
                .thenBy { it.sortOrder }
                .thenBy { it.title.lowercase() },
        )

/** El tipo canónico de un examen. Un valor desconocido cae en ráfaga, como en la importación. */
fun Exam.contentType(): ContentExamType = ContentExamType.fromApiValue(typeExam)

/** Aplica tipo y búsqueda (título, especialidad o código, sin distinguir mayúsculas). */
fun filterPublishedExams(
    exams: List<Exam>,
    filter: ExamTypeFilter,
    query: String,
): List<Exam> {
    val needle = query.trim().lowercase()
    return exams.filter { exam ->
        (filter.type == null || exam.contentType() == filter.type) &&
            (
                needle.isEmpty() ||
                    exam.title.lowercase().contains(needle) ||
                    exam.specialtyName.lowercase().contains(needle) ||
                    exam.code.lowercase().contains(needle)
                )
    }
}

/**
 * Recuento por filtro, sobre el total publicado y NO sobre lo que la búsqueda
 * deja: el número de la pestaña dice cuántos hay de ese tipo, no cuántos
 * coinciden con lo que se acaba de teclear.
 */
fun publishedExamCounts(exams: List<Exam>): Map<ExamTypeFilter, Int> =
    ExamTypeFilter.entries.associateWith { filter ->
        if (filter.type == null) exams.size else exams.count { it.contentType() == filter.type }
    }

/**
 * Cómo se accede al examen. `priceTickets` solo significa algo en un examen de
 * pago: en uno gratuito un valor residual no debe leerse como un precio.
 */
fun Exam.accessLabel(): String = when {
    typeAccess.equals("gratuito", ignoreCase = true) -> "Gratuito"
    priceTickets > 0 -> "Premium · $priceTickets OT"
    else -> "Premium"
}

/** «120 preguntas · 90 min · 2026». Solo lo que se sabe: un dato ausente no se inventa. */
fun Exam.factsLine(): String = buildList {
    add(if (questionCount == 1) "1 pregunta" else "$questionCount preguntas")
    durationMinutes?.takeIf { it > 0 }?.let { add("$it min") }
    year?.let { add(it.toString()) }
    if (isOfficial) add("Oficial")
}.joinToString(" · ")
