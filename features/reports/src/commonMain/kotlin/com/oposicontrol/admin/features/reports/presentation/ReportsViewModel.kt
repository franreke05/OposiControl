package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.ModerationRepository

class ReportsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ReportsState, ReportsIntent>(
    initialState = ReportsState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("reports_list")
        dispatch(ReportsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ReportsIntent) {
        when (intent) {
            ReportsIntent.Refresh -> load(asPullToRefresh = false)
            ReportsIntent.PullToRefresh -> load(asPullToRefresh = true)
            is ReportsIntent.SetMainFilter -> {
                if (intent.filter == currentState().mainFilter) return
                updateState { it.copy(mainFilter = intent.filter, secondaryFilters = ReportSecondaryFilters()) }
                load(asPullToRefresh = false)
            }
            is ReportsIntent.ApplySecondaryFilters -> updateState { it.copy(secondaryFilters = intent.filters) }
            ReportsIntent.ClearSecondaryFilters -> updateState { it.copy(secondaryFilters = ReportSecondaryFilters()) }
        }
    }

    private suspend fun load(asPullToRefresh: Boolean) {
        val includeResolved = currentState().mainFilter == ReportsMainFilter.ALL
        if (asPullToRefresh) {
            updateState { it.copy(refreshing = true) }
        } else {
            updateState { it.copy(reports = UiAsync.Loading) }
        }
        when (val result = repository.listReports(includeResolved)) {
            is AppResult.Failure -> updateState { it.copy(reports = UiAsync.Error(result.error), refreshing = false) }
            is AppResult.Success -> {
                val sorted = result.value.sortedByDescending { it.createdAt }
                updateState {
                    it.copy(
                        reports = if (sorted.isEmpty()) UiAsync.Empty else UiAsync.Success(sorted),
                        refreshing = false,
                    )
                }
            }
        }
    }
}
