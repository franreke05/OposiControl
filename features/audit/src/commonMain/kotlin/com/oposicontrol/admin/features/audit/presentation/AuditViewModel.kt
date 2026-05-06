package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.usecase.audit.ObserveAuditEventsUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuditViewModel(
    dispatcherProvider: DispatcherProvider,
    observeAuditEventsUseCase: ObserveAuditEventsUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<AuditState, AuditIntent>(
    initialState = AuditState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            observeAuditEventsUseCase().collectLatest { events ->
                updateState {
                    it.copy(events = if (events.isEmpty()) UiAsync.Empty else UiAsync.Success(events))
                }
            }
        }
        dispatch(AuditIntent.Refresh)
    }

    override suspend fun handleIntent(intent: AuditIntent) {
        analyticsTracker.trackScreen("audit")
    }
}
