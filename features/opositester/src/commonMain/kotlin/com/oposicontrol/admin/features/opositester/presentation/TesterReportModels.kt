package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.domain.model.TesterReport
import kotlinx.datetime.Instant

/** Estados reales confirmados de feedback_pregunta: "pendiente" es el default
 * de la columna; "resuelto"/"descartado"/"revisado" son los únicos valores
 * que el propio backend acepta al resolver (`ResolverFeedbackRequest`). UNKNOWN
 * es el único caso no inventado: cualquier otro texto se muestra tal cual,
 * nunca se le asigna un significado que no está confirmado. */
enum class TesterReportStatusKind { PENDING, RESOLVED, DISCARDED, REVIEWED, UNKNOWN }

data class TesterReportUi(
    val id: String,
    val statusKind: TesterReportStatusKind,
    val statusLabel: String,
    val typeLabel: String,
    val authorName: String,
    val descriptionPreview: String,
    val timeLabel: String,
    val semanticsLabel: String,
)

fun TesterReport.toUi(now: Instant): TesterReportUi {
    val (statusKind, statusLabel) = mapTesterStatus(status)
    val typeLabel = humanizeTesterType(type)
    val author = authorName.ifBlank { "Usuario" }
    val relative = formatTesterRelativeTime(createdAt, now)
    return TesterReportUi(
        id = id,
        statusKind = statusKind,
        statusLabel = statusLabel,
        typeLabel = typeLabel,
        authorName = author,
        descriptionPreview = description,
        timeLabel = relative,
        semanticsLabel = buildString {
            append("Incidencia ")
            append(statusLabel.lowercase())
            append(". ")
            append(typeLabel)
            append(". ")
            append(author)
            if (relative.isNotBlank()) {
                append(". Actualizada ")
                append(relative)
            }
            append(". Abrir.")
        },
    )
}

/** internal (no private): TesterDetailModels.kt en el mismo módulo reutiliza
 * exactamente el mismo mapeo de estado para Screen 07 — nunca se duplica. */
internal fun mapTesterStatus(estado: String): Pair<TesterReportStatusKind, String> = when (estado.lowercase()) {
    "pendiente" -> TesterReportStatusKind.PENDING to "Pendiente"
    "resuelto" -> TesterReportStatusKind.RESOLVED to "Resuelto"
    "descartado" -> TesterReportStatusKind.DISCARDED to "Descartado"
    "revisado" -> TesterReportStatusKind.REVIEWED to "Revisado"
    else -> TesterReportStatusKind.UNKNOWN to estado.ifBlank { "Sin estado" }.replaceFirstChar { it.uppercase() }
}

/** tipo_feedback no tiene un catálogo cerrado confirmado: se humaniza el valor
 * real (guiones bajos → espacios, mayúscula inicial) sin inventar categorías
 * ni interpretar su significado. */
fun humanizeTesterType(tipo: String): String {
    if (tipo.isBlank()) return "Incidencia"
    return tipo.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Mismo criterio que el resto de pantallas: nunca fabrica un valor si [iso]
 * no se puede interpretar como instante real. */
fun formatTesterRelativeTime(iso: String, now: Instant): String {
    if (iso.isBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val minutes = (now - instant).inWholeMinutes
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        minutes < 60 * 24 * 7 -> "hace ${minutes / (60 * 24)} d"
        iso.length >= 10 -> "el ${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}"
        else -> ""
    }
}
