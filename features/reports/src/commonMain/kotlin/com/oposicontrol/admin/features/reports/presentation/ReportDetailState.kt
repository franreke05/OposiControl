package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ForumReportDetail

/** Tope de `notas` en `POST /reportes/{id}/resolver` (AUDIT_TEXT_MAX del backend: 422 REPORT_NOTES_TOO_LONG). */
const val REPORT_NOTES_MAX = 1_000

/** Tope de `ticketsReporter` del backend (MAX_RECOMPENSA_POR_REPORTE: 422 WALLET_AMOUNT_INVALID por encima). */
const val REPORT_REWARD_MAX = 10_000

data class ReportDetailState(
    val report: UiAsync<ForumReportDetail> = UiAsync.Loading,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val notes: String = "",
    /** Texto tecleado de la recompensa; vacío = 0. */
    val rewardInput: String = "",
) {
    /** Recompensa válida (0 si está vacía) o null si no es un entero dentro de 0..[REPORT_REWARD_MAX]. */
    val reward: Int? get() = if (rewardInput.isBlank()) 0 else rewardInput.trim().toIntOrNull()?.takeIf { it in 0..REPORT_REWARD_MAX }

    /** Sin reportante (anónimo o borrado) el servidor no concede nada: la recompensa no se ofrece. */
    val rewardAvailable: Boolean
        get() = (report as? UiAsync.Success)?.value?.report?.reporterId?.isNotBlank() == true

    /** Lo que impide resolver, en el orden en que se dice; vacío = se puede. */
    val resolveBlocker: String?
        get() = when {
            notes.length > REPORT_NOTES_MAX -> "Las notas no pueden superar $REPORT_NOTES_MAX caracteres."
            rewardAvailable && reward == null -> "La recompensa tiene que ser un número entre 0 y $REPORT_REWARD_MAX."
            else -> null
        }

    /** Descartar no lleva recompensa: solo cuentan las notas. */
    val discardBlocker: String?
        get() = if (notes.length > REPORT_NOTES_MAX) "Las notas no pueden superar $REPORT_NOTES_MAX caracteres." else null
}
