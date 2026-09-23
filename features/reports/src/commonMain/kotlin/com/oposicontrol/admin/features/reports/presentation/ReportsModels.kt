package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportContentPreview
import com.oposicontrol.admin.domain.model.ForumReportDetail
import kotlinx.datetime.Instant

/** Filtro principal de la pantalla. Refleja los dos únicos modos que el
 * backend soporta hoy: pendientes (comportamiento histórico) o todos. */
enum class ReportsMainFilter { PENDING, ALL }

enum class ReportStatusKind { PENDING, RESOLVED, DISCARDED, UNKNOWN }

/** Único filtro secundario respaldado por datos reales: el tipo de entidad
 * reportada (`tipo_entidad`). No existen especialidad/examen/pregunta en el
 * modelo de reportes, así que no se ofrecen como filtro (BACKEND_DATA_GAP). */
data class ReportSecondaryFilters(
    val selectedEntityTypes: Set<String> = emptySet(),
)

data class ReportItemUi(
    val id: String,
    val statusKind: ReportStatusKind,
    val statusLabel: String,
    val entityType: String,
    val entityTypeLabel: String,
    val reason: String,
    val description: String,
    val reporterName: String,
    val timeLabel: String,
    val semanticsLabel: String,
)

fun ForumReport.toUi(now: Instant): ReportItemUi {
    val (statusKind, statusLabel) = mapStatus(status)
    val entityTypeLabel = mapEntityTypeLabel(entityType)
    val timeLabel = if (statusKind != ReportStatusKind.PENDING && resolvedAt.isNotBlank()) {
        val relative = formatRelativeTime(resolvedAt, now)
        if (relative.isBlank()) "" else "Resuelto $relative"
    } else {
        formatRelativeTime(createdAt, now).replaceFirstChar { it.uppercase() }
    }
    val descriptionOrReason = description.ifBlank { reason }
    val title = reason.ifBlank { entityTypeLabel }
    return ReportItemUi(
        id = id,
        statusKind = statusKind,
        statusLabel = statusLabel,
        entityType = entityType,
        entityTypeLabel = entityTypeLabel,
        reason = title,
        description = descriptionOrReason,
        reporterName = reporterName,
        timeLabel = timeLabel,
        semanticsLabel = buildString {
            append("Informe $statusLabel. ")
            append(title)
            if (descriptionOrReason.isNotBlank() && descriptionOrReason != title) {
                append(". ")
                append(descriptionOrReason)
            }
            append(". Abrir informe.")
        },
    )
}

data class ReportDetailUi(
    val statusKind: ReportStatusKind,
    val statusLabel: String,
    val reason: String,
    val description: String,
    val entityTypeLabel: String,
    val reporterName: String,
    val createdLabel: String,
    val resolvedLabel: String?,
    /** Null cuando el backend no pudo resolver la entidad real (BACKEND_DATA_GAP
     * para ese informe concreto) — nunca se rellena con datos inventados. */
    val contentPreview: ContentPreviewUi?,
)

data class ContentPreviewUi(
    val typeLabel: String,
    val author: String,
    val text: String,
    val timeLabel: String,
    val threadTitle: String?,
    val currentStateLabel: String,
)

fun ForumReportDetail.toDetailUi(now: Instant): ReportDetailUi {
    val (statusKind, statusLabel) = mapStatus(report.status)
    return ReportDetailUi(
        statusKind = statusKind,
        statusLabel = statusLabel,
        reason = report.reason,
        description = report.description,
        entityTypeLabel = mapEntityTypeLabel(report.entityType),
        reporterName = report.reporterName,
        createdLabel = formatAbsoluteDate(report.createdAt),
        resolvedLabel = formatAbsoluteDate(report.resolvedAt).takeIf { statusKind != ReportStatusKind.PENDING && it.isNotBlank() },
        contentPreview = contentPreview?.toUi(now),
    )
}

private fun ForumReportContentPreview.toUi(now: Instant): ContentPreviewUi = ContentPreviewUi(
    typeLabel = mapEntityTypeLabel(type),
    author = author,
    text = text,
    timeLabel = formatRelativeTime(createdAt, now).replaceFirstChar { it.uppercase() },
    threadTitle = threadTitle?.takeIf { it.isNotBlank() },
    currentStateLabel = currentState.ifBlank { "" }.replaceFirstChar { it.uppercase() },
)

/** Fecha/hora completa a partir de un timestamp ISO real, sin inventar zona
 * horaria ni formato: recorta directamente los componentes del ISO recibido. */
private fun formatAbsoluteDate(iso: String): String {
    if (iso.length < 16) return ""
    val date = "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}"
    val time = iso.substring(11, 16)
    return "$date · $time"
}

private fun mapStatus(estado: String): Pair<ReportStatusKind, String> = when (estado.lowercase()) {
    "pendiente" -> ReportStatusKind.PENDING to "Pendiente"
    "resuelto" -> ReportStatusKind.RESOLVED to "Resuelto"
    "descartado" -> ReportStatusKind.DISCARDED to "Descartado"
    else -> ReportStatusKind.UNKNOWN to estado.ifBlank { "Sin estado" }.replaceFirstChar { it.uppercase() }
}

private fun mapEntityTypeLabel(tipo: String): String = when (tipo.lowercase()) {
    "hilo" -> "Hilo del foro"
    "comentario" -> "Comentario"
    "" -> "Contenido reportado"
    else -> tipo.replaceFirstChar { it.uppercase() }
}

/** Formatea un timestamp ISO real como texto relativo. Nunca inventa un
 * valor: si [iso] no se puede interpretar, devuelve cadena vacía. */
fun formatRelativeTime(iso: String, now: Instant): String =
    com.oposicontrol.admin.core.common.presentation.formatRelativeTime(iso, now)
