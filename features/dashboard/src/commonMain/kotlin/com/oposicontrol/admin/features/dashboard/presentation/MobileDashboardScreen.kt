package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.runtime.Composable

/** Compatibility entry point for the existing dashboard route. */
@Composable
fun MobileDashboardScreen(
    viewModel: DashboardViewModel,
    environmentLabel: String,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    MobileHomeScreen(
        viewModel = viewModel,
        onQuickAction = onQuickAction,
    )
}
