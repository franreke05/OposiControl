package com.oposicontrol.admin.features.opositester.presentation

sealed interface TesterDetailIntent {
    data object Load : TesterDetailIntent

    /** "resuelto": único estado real que el backend acepta para dar la
     * incidencia por resuelta (ResolverFeedbackRequest). */
    data object Resolve : TesterDetailIntent

    /** "descartado": estado real para incidencias que no requieren acción
     * (duplicada, no reproducible, etc.) — mismo criterio que ReportDetail. */
    data object Discard : TesterDetailIntent
}
