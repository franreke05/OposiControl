package com.oposicontrol.admin.features.dashboard.presentation

sealed interface DashboardIntent {
    data object Refresh : DashboardIntent
}
