package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.usecase.dashboard.GetDashboardMetricsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DashboardViewModel(
    dispatcherProvider: DispatcherProvider,
    private val getDashboardMetricsUseCase: GetDashboardMetricsUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<DashboardState, DashboardIntent>(
    initialState = DashboardState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    init {
        analyticsTracker.trackScreen("dashboard")
        dispatch(DashboardIntent.Refresh)
    }

    override suspend fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        updateState {
            it.copy(
                metrics = if (it.metrics is UiAsync.Success) it.metrics else UiAsync.Loading,
                isRefreshing = true,
                refreshError = null,
            )
        }
        refreshJob = scope.launch {
            val result = getDashboardMetricsUseCase()
            // WHY: a cancelled transport may still complete, so only the newest refresh may publish.
            if (generation != refreshGeneration) return@launch
            when (result) {
                is AppResult.Failure -> updateState {
                    it.copy(
                        metrics = if (it.metrics is UiAsync.Success) it.metrics else UiAsync.Error(result.error),
                        isRefreshing = false,
                        refreshError = result.error,
                    )
                }

                is AppResult.Success -> updateState {
                    it.copy(
                        metrics = UiAsync.Success(result.value),
                        isRefreshing = false,
                        refreshError = null,
                    )
                }
            }
        }
    }
}
