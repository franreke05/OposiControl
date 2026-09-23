package com.oposicontrol.admin.features.dashboard.presentation

sealed interface DashboardIntent {
    data object Refresh : DashboardIntent
    data object ToggleQuickActions : DashboardIntent
    data object ToggleSummary : DashboardIntent
}
