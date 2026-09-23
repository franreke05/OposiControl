package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.TesterRepository

/** Estados reales que el backend acepta al resolver (ResolverFeedbackRequest)
 * — mismo criterio que TesterListViewModel.PENDING_STATUS. "revisado" existe
 * en el contrato real pero no se expone como acción propia en esta pantalla
 * (mismo criterio de alcance que ReportDetail: Resolver/Descartar). */
private const val RESOLVED_STATUS = "resuelto"
private const val DISCARDED_STATUS = "descartado"

class TesterDetailViewModel(
    private val testerId: String,
    dispatcherProvider: DispatcherProvider,
    private val testerRepository: TesterRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<TesterDetailState, TesterDetailIntent>(
    initialState = TesterDetailState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("opositester_detail")
        dispatch(TesterDetailIntent.Load)
    }

    override suspend fun handleIntent(intent: TesterDetailIntent) {
        when (intent) {
            TesterDetailIntent.Load -> load()
            TesterDetailIntent.Resolve -> performAction(RESOLVED_STATUS)
            TesterDetailIntent.Discard -> performAction(DISCARDED_STATUS)
        }
    }

    private suspend fun load() {
        updateState { it.copy(report = UiAsync.Loading) }
        when (val result = testerRepository.getReport(testerId)) {
            is AppResult.Failure -> updateState { it.copy(report = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(report = UiAsync.Success(result.value)) }
        }
    }

    private suspend fun performAction(estado: String) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = testerRepository.resolveReport(testerId, estado)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("opositester_detail_action", mapOf("estado" to estado))
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                load()
            }
        }
    }
}
