package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.DashboardRepository

class DashboardViewModel(
    dispatcherProvider: DispatcherProvider,
    private val dashboardRepository: DashboardRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<DashboardState, DashboardIntent>(
    initialState = DashboardState(
        quickActionsExpanded = HomeSectionPreferences.quickActionsExpanded,
        summaryExpanded = HomeSectionPreferences.summaryExpanded,
    ),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(DashboardIntent.Refresh)
    }

    override suspend fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.Refresh -> refresh()
            DashboardIntent.ToggleQuickActions -> updateState {
                val expanded = !it.quickActionsExpanded
                HomeSectionPreferences.quickActionsExpanded = expanded
                it.copy(quickActionsExpanded = expanded)
            }
            DashboardIntent.ToggleSummary -> updateState {
                val expanded = !it.summaryExpanded
                HomeSectionPreferences.summaryExpanded = expanded
                it.copy(summaryExpanded = expanded)
            }
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("dashboard")
        // Sólo se vuelve a `Loading` cuando no hay nada en pantalla. Si ya hay
        // métricas, la recarga se anuncia con `isRefreshing` y los datos
        // anteriores siguen visibles: el dashboard se refresca solo cada cinco
        // minutos y, poniéndolo en `Loading`, la pantalla entera se sustituía por
        // un esqueleto mientras alguien estaba leyéndola.
        val hadData = currentState().metrics is UiAsync.Success
        updateState {
            if (hadData) it.copy(isRefreshing = true) else it.copy(metrics = UiAsync.Loading)
        }
        when (val result = dashboardRepository.getMetrics()) {
            is AppResult.Failure -> updateState {
                // Un fallo de refresco tampoco borra lo que ya se veía: unos datos
                // de hace cinco minutos son más útiles que una pantalla de error.
                if (hadData) it.copy(isRefreshing = false) else it.copy(metrics = UiAsync.Error(result.error))
            }
            is AppResult.Success -> updateState {
                it.copy(metrics = UiAsync.Success(result.value), isRefreshing = false)
            }
        }
    }
}
