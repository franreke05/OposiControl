package com.oposicontrol.admin.features.reports.presentation

sealed interface ReportsIntent {
    data object Refresh : ReportsIntent
    data object PullToRefresh : ReportsIntent
    /** Pide la página siguiente al servidor (también es el reintento tras un fallo de «cargar más»). */
    data object LoadMore : ReportsIntent
    data class SetMainFilter(val filter: ReportsMainFilter) : ReportsIntent
    data class ApplySecondaryFilters(val filters: ReportSecondaryFilters) : ReportsIntent
    data object ClearSecondaryFilters : ReportsIntent
}
