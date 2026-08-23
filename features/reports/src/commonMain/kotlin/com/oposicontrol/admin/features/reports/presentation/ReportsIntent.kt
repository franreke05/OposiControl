package com.oposicontrol.admin.features.reports.presentation

sealed interface ReportsIntent {
    data object Refresh : ReportsIntent
    data object PullToRefresh : ReportsIntent
    data class SetMainFilter(val filter: ReportsMainFilter) : ReportsIntent
    data class ApplySecondaryFilters(val filters: ReportSecondaryFilters) : ReportsIntent
    data object ClearSecondaryFilters : ReportsIntent
}
