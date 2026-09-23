package com.oposicontrol.admin.features.analytics.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport
import com.oposicontrol.admin.domain.repository.ProductAnalyticsRepository

sealed interface ProductAnalyticsIntent {
    data class SelectSection(val section: AnalyticsSection) : ProductAnalyticsIntent
    data object Refresh : ProductAnalyticsIntent
}

data class ProductAnalyticsState(
    val section: AnalyticsSection = AnalyticsSection.PRODUCTO,
    val reports: Map<AnalyticsSection, UiAsync<AnalyticsSectionReport>> = emptyMap(),
) {
    val current: UiAsync<AnalyticsSectionReport> get() = reports[section] ?: UiAsync.Idle
}

/**
 * Analítica de producto: una pantalla, seis secciones.
 *
 * Carga por sección y no de golpe: cada sección son entre diez y veinte conteos
 * en el backend, así que abrir "Producto" no debe pagar también los de economía.
 * Cada sección guarda su propio estado, de modo que un fallo en comunidad no
 * deja la pantalla entera en blanco.
 */
class ProductAnalyticsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ProductAnalyticsRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ProductAnalyticsState, ProductAnalyticsIntent>(
    initialState = ProductAnalyticsState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(ProductAnalyticsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ProductAnalyticsIntent) {
        when (intent) {
            is ProductAnalyticsIntent.SelectSection -> {
                updateState { it.copy(section = intent.section) }
                load(intent.section)
            }
            ProductAnalyticsIntent.Refresh -> load(currentState().section, force = true)
        }
    }

    /**
     * Carga una sección. Sin refresco explícito no se repite: cambiar de pestaña
     * y volver no debería costar otra ronda de conteos exactos contra Supabase.
     */
    private suspend fun load(section: AnalyticsSection, force: Boolean = false) {
        if (!force && currentState().reports[section].isLoaded()) return
        analyticsTracker.trackScreen("analytics_${section.name.lowercase()}")
        updateState { it.withReport(section, UiAsync.Loading) }
        val result = repository.getSection(section).toUiAsync()
        updateState { it.withReport(section, result) }
    }
}

private fun ProductAnalyticsState.withReport(
    section: AnalyticsSection,
    value: UiAsync<AnalyticsSectionReport>,
): ProductAnalyticsState = copy(reports = reports + (section to value))

private fun UiAsync<AnalyticsSectionReport>?.isLoaded(): Boolean = this is UiAsync.Success

/**
 * Un informe nunca es `Empty`, ni siquiera cuando ninguna métrica trae número.
 *
 * "Sin resultados" escondería justo lo que hay que ver: qué no se pudo leer y
 * qué agregados faltan por construir. Un informe con cero métricas disponibles
 * sigue siendo información, y de la útil.
 */
private fun AppResult<AnalyticsSectionReport>.toUiAsync(): UiAsync<AnalyticsSectionReport> = when (this) {
    is AppResult.Failure -> UiAsync.Error(error)
    is AppResult.Success -> UiAsync.Success(value)
}
