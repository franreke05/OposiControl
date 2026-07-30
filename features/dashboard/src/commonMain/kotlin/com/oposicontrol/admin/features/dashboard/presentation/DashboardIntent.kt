package com.oposicontrol.admin.features.dashboard.presentation

/** User actions accepted by the dashboard state holder. */
sealed interface DashboardIntent {
    data object Refresh : DashboardIntent
}
