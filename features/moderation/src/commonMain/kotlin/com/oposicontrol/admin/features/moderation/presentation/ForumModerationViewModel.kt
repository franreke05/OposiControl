package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.ModerationRepository

class ForumModerationViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ForumModerationState, ForumModerationIntent>(
    initialState = ForumModerationState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("forum_moderation")
        dispatch(ForumModerationIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ForumModerationIntent) {
        when (intent) {
            ForumModerationIntent.Refresh -> refresh()
            is ForumModerationIntent.Resolve -> resolve(intent)
        }
    }

    private suspend fun refresh() {
        updateState { it.copy(reports = UiAsync.Loading) }
        when (val result = repository.listPendingReports()) {
            is AppResult.Failure -> updateState { it.copy(reports = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(reports = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value))
            }
        }
    }

    private suspend fun resolve(intent: ForumModerationIntent.Resolve) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = repository.resolveReport(intent.reportId, intent.action, intent.notes, intent.ticketsReporter)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("forum_report_resolve", mapOf("action" to intent.action))
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                refresh()
            }
        }
    }
}
