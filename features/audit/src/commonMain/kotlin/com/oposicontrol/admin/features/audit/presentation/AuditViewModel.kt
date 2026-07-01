package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.AuditRepository

class AuditViewModel(
    dispatcherProvider: DispatcherProvider,
    private val auditRepository: AuditRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<AuditState, AuditIntent>(
    initialState = AuditState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(AuditIntent.Refresh)
    }

    override suspend fun handleIntent(intent: AuditIntent) {
        when (intent) {
            AuditIntent.Refresh -> refresh()
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("audit")
        updateState { it.copy(events = UiAsync.Loading) }
        when (val result = auditRepository.fetchEvents()) {
            is AppResult.Failure -> updateState { it.copy(events = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(events = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value))
            }
        }
    }
}
