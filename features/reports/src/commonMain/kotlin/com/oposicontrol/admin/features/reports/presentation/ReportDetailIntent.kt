package com.oposicontrol.admin.features.reports.presentation

sealed interface ReportDetailIntent {
    data object Load : ReportDetailIntent
    /** "ocultar": única acción real disponible para el botón genérico "Resolver informe". */
    data object Resolve : ReportDetailIntent
    data object Discard : ReportDetailIntent
    data object DismissActionResult : ReportDetailIntent
}
