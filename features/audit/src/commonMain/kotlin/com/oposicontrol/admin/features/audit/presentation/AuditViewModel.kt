package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.usecase.audit.ObserveAuditEventsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AuditViewModel(
    dispatcherProvider: DispatcherProvider,
    private val observeAuditEventsUseCase: ObserveAuditEventsUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<AuditState, AuditIntent>(
    initialState = AuditState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var observationJob: Job? = null

    init {
        dispatch(AuditIntent.Refresh)
    }

    override suspend fun handleIntent(intent: AuditIntent) {
        when (intent) {
            AuditIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        analyticsTracker.trackScreen("audit")
        observationJob?.cancel()
        updateState { it.copy(events = UiAsync.Loading) }
        observationJob = scope.launch {
            observeAuditEventsUseCase()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    updateState {
                        it.copy(
                            events = UiAsync.Error(
                                AppError.Unknown(
                                    message = "No se pudo cargar la auditoria.",
                                    cause = cause,
                                ),
                            ),
                        )
                    }
                }
                .collect { events ->
                    updateState {
                        it.copy(events = if (events.isEmpty()) UiAsync.Empty else UiAsync.Success(events))
                    }
                }
        }
    }
}
