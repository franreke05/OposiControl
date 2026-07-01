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
    initialState = DashboardState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(DashboardIntent.Refresh)
    }

    override suspend fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.Refresh -> refresh()
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("dashboard")
        updateState { it.copy(metrics = UiAsync.Loading) }
        when (val result = dashboardRepository.getMetrics()) {
            is AppResult.Failure -> updateState { it.copy(metrics = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(metrics = UiAsync.Success(result.value)) }
        }
    }
}
