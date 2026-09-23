package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.ModerationRepository

// Etiquetas de auditoría (acciones_moderacion.tipo_accion, texto libre sin
// constraint conocida). "resuelto_sin_accion" es deliberadamente distinta de
// "ocultar"/"avisar"/"banear" (las acciones reales del panel de escritorio que
// SÍ implican algo sobre el contenido/usuario): resolver desde esta pantalla
// nunca toca contenido, así que la etiqueta no debe insinuar que lo hizo.
private const val RESOLVE_ACTION = "resuelto_sin_accion"
private const val DISCARD_ACTION = "descartar"

class ReportDetailViewModel(
    private val reportId: String,
    dispatcherProvider: DispatcherProvider,
    private val repository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ReportDetailState, ReportDetailIntent>(
    initialState = ReportDetailState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("report_detail")
        dispatch(ReportDetailIntent.Load)
    }

    override suspend fun handleIntent(intent: ReportDetailIntent) {
        when (intent) {
            ReportDetailIntent.Load -> load()
            ReportDetailIntent.Resolve -> performAction(RESOLVE_ACTION)
            ReportDetailIntent.Discard -> performAction(DISCARD_ACTION)
            ReportDetailIntent.DismissActionResult -> updateState { it.copy(actionState = UiAsync.Idle) }
        }
    }

    private suspend fun load() {
        updateState { it.copy(report = UiAsync.Loading) }
        when (val result = repository.getReport(reportId)) {
            is AppResult.Failure -> updateState { it.copy(report = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(report = UiAsync.Success(result.value)) }
        }
    }

    private suspend fun performAction(action: String) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = repository.resolveReport(reportId, action, null, 0)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("report_detail_action", mapOf("action" to action))
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                load()
            }
        }
    }
}
