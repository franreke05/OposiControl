package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.ui.components.WorkspaceFact

/** Cuántos exámenes se enseñan como candidatos a la vez: una lista para elegir, no un catálogo. */
internal const val EXAM_MATCH_LIMIT: Int = 8

internal fun accessMatches(item: ExamAccessItem, query: String, filter: AccessFilter): Boolean {
    val matchesFilter = when (filter) {
        AccessFilter.ALL -> true
        AccessFilter.ACTIVE -> item.isActive
        AccessFilter.REVOKED -> !item.isActive
    }
    val q = query.trim().lowercase()
    return matchesFilter && (q.isEmpty() || item.userName.lowercase().contains(q) || item.examTitle.lowercase().contains(q))
}

internal fun accessCatalogHasFilters(query: String, filter: AccessFilter): Boolean = query.isNotBlank() || filter != AccessFilter.ALL

/** Exámenes candidatos para lo tecleado, por título o especialidad. Sin texto, los primeros del catálogo. */
internal fun examMatches(exams: List<AccessExamOption>, query: String): List<AccessExamOption> {
    val q = query.trim().lowercase()
    return exams.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.detail.lowercase().contains(q) }.take(EXAM_MATCH_LIMIT)
}

internal fun accessStatusLabel(item: ExamAccessItem): String = if (item.isActive) "Vigente" else "Retirado"

/** «2026-10-13T09:15:00+00:00» → «13/10/2026». `null` si no hay fecha o no tiene esa forma: no se inventa una. */
internal fun formatAccessDate(iso: String?): String? {
    val day = iso?.trim()?.take(10) ?: return null
    val parts = day.split("-")
    if (parts.size != 3 || parts[0].length != 4 || parts.any { part -> part.isEmpty() || !part.all { it.isDigit() } }) return null
    return "${parts[2]}/${parts[1]}/${parts[0]}"
}

internal fun accessOverviewFacts(all: List<ExamAccessItem>): List<WorkspaceFact> = listOf(
    WorkspaceFact("Registrados", all.size.toString()),
    WorkspaceFact("Vigentes", all.count { it.isActive }.toString()),
    WorkspaceFact("Retirados", all.count { !it.isActive }.toString()),
    WorkspaceFact("Por compra", all.count { it.isPurchase }.toString()),
)

internal val accessInformationLines: List<String> = listOf(
    "Un acceso abre un examen de pago a una persona concreta, sin que lo compre ni tenga Premium.",
    "Conceder y retirar quedan registrados en auditoría, con quién lo hizo.",
    "Retirar no borra la fila: le pone fin de vigencia. La persona deja de poder abrir el examen y el historial se conserva.",
    "Un acceso por compra se ve aquí, pero ni se concede ni se retira desde esta pantalla.",
)
