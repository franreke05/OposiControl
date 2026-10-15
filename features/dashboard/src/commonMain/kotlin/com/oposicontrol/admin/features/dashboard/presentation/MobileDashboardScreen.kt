package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.runtime.Composable

/**
 * Atajos del Inicio móvil. Sólo los que pinta [MobileHomeScreen]: los del antiguo dashboard de escritorio
 * (`DashboardScreen`, que ninguna ruta mostraba desde que Inicio en escritorio es Dashboard V2) se retiraron con él.
 */
enum class DashboardQuickAction {
    REVIEW_FORUM_REPORTS,
    IMPORT_QUESTIONS,
    CREATE_EXAM,
    OPEN_USERS,
    OPEN_SUPPORT,
}

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
