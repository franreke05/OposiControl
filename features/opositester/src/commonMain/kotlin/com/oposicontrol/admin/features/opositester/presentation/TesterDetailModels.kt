package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.domain.model.TesterReportDetail
import kotlinx.datetime.Instant

data class TesterQuestionContextUi(
    val code: String,
    val title: String,
    val specialtyName: String?,
)

data class TesterReportDetailUi(
    val id: String,
    val statusKind: TesterReportStatusKind,
    val statusLabel: String,
    val typeLabel: String,
    val authorName: String,
    val description: String,
    val timeLabel: String,
    val question: TesterQuestionContextUi?,
)

/** Mismo mapeo que [toUi] (lista): estado/tipo/tiempo reales, sin inventar
 * significado para valores no confirmados. [description] nunca se trunca
 * aquí — Screen 07 muestra el texto completo, a diferencia del preview de
 * Screen 06. */
fun TesterReportDetail.toDetailUi(now: Instant): TesterReportDetailUi {
    val (statusKind, statusLabel) = mapTesterStatus(status)
    return TesterReportDetailUi(
        id = id,
        statusKind = statusKind,
        statusLabel = statusLabel,
        typeLabel = humanizeTesterType(type),
        authorName = authorName.ifBlank { "Usuario" },
        description = description.ifBlank { "Sin descripción." },
        timeLabel = formatTesterRelativeTime(createdAt, now),
        question = question?.let {
            TesterQuestionContextUi(code = it.code, title = it.title, specialtyName = it.specialtyName)
        },
    )
}
