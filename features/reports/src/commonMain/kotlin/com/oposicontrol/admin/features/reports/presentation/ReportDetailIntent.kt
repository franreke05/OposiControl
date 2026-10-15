package com.oposicontrol.admin.features.reports.presentation

sealed interface ReportDetailIntent {
    data object Load : ReportDetailIntent
    /** "ocultar": única acción real disponible para el botón genérico "Resolver informe". */
    data object Resolve : ReportDetailIntent
    data object Discard : ReportDetailIntent
    data object DismissActionResult : ReportDetailIntent
    /** Notas de moderación (quedan en la acción de moderación; tope del servidor [REPORT_NOTES_MAX]). */
    data class UpdateNotes(val value: String) : ReportDetailIntent
    /** Recompensa al reportante al resolver, en OposiTickets (0..[REPORT_REWARD_MAX]; descartar nunca recompensa). */
    data class UpdateReward(val value: String) : ReportDetailIntent
}
