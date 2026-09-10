package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.repository.DashboardOverviewRepository

/**
 * Panel de escritorio (Dashboard V2).
 *
 * Copia el patrón que ya funciona en Analítica de producto —estado por sección,
 * error por sección— pero invierte una decisión: allí cada pestaña se pide por
 * separado porque sólo se ve una a la vez; aquí las seis franjas se ven juntas,
 * así que la carga inicial es **una sola petición** (`getOverview(emptySet())`)
 * y no seis. El refresco sí es por franja, que es lo que permite tener el estado
 * del sistema al minuto sin repetir cada minuto los catorce conteos del resto.
 *
 * No sustituye a [com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel]:
 * el Home móvil sigue con aquel y su contrato intactos.
 */
class DashboardV2ViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: DashboardOverviewRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<DashboardV2State, DashboardV2Intent>(
    initialState = DashboardV2State(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(DashboardV2Intent.Load)
    }

    override suspend fun handleIntent(intent: DashboardV2Intent) {
        when (intent) {
            DashboardV2Intent.Load -> load(ALL_BLOCKS, trackScreen = true)
            DashboardV2Intent.RefreshAll -> load(ALL_BLOCKS, trackScreen = false)
            is DashboardV2Intent.RefreshBlock -> load(setOf(intent.block), trackScreen = false)
        }
    }

    private suspend fun load(requested: Set<DashboardBlock>, trackScreen: Boolean) {
        if (trackScreen) analyticsTracker.trackScreen("dashboard_v2")
        updateState { it.startLoading(requested) }
        // El contrato dice que un `sections` vacío significa TODAS. Mandarlo así
        // en vez de nombrar las seis no es cosmético: si mañana se añade una
        // séptima franja, esta llamada la trae sin tocar el cliente.
        val sections = if (requested == ALL_BLOCKS) emptySet() else requested
        when (val result = repository.getOverview(sections)) {
            is AppResult.Failure -> updateState { it.applyCallFailure(requested, result.error) }
            is AppResult.Success -> updateState { it.applyOverview(requested, result.value) }
        }
    }

    private companion object {
        val ALL_BLOCKS: Set<DashboardBlock> = DashboardBlock.entries.toSet()
    }
}
