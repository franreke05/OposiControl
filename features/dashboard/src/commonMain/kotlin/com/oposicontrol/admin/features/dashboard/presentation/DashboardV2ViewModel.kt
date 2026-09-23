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
    /**
     * Número de la última petición que pidió cada franja (PC-10).
     *
     * `MviViewModel` lanza cada intent en su propia corrutina, así que dos cargas
     * de la misma franja pueden estar en vuelo a la vez —el refresco automático de
     * SYSTEM cada minuto durante un «Actualizar», por ejemplo— y la respuesta vieja
     * puede llegar DESPUÉS que la nueva. Sin este registro la vieja pisaba a la
     * nueva y el panel retrocedía en el tiempo sin avisar. Sólo se toca desde
     * `handleIntent`, que corre en el dispatcher principal: no necesita cerrojo.
     */
    private val latestRequestByBlock = mutableMapOf<DashboardBlock, Long>()
    private var requestCounter = 0L

    /** Hay una carga COMPLETA en vuelo: un segundo «Actualizar» no lanza otra (PC-10). */
    private var fullLoadInFlight = false

    init {
        dispatch(DashboardV2Intent.Load)
    }

    override suspend fun handleIntent(intent: DashboardV2Intent) {
        when (intent) {
            DashboardV2Intent.Load -> loadAll(trackScreen = true)
            DashboardV2Intent.RefreshAll -> loadAll(trackScreen = false)
            is DashboardV2Intent.RefreshBlock -> load(setOf(intent.block), trackScreen = false)
        }
    }

    /**
     * Carga de las seis franjas, con UNA en vuelo como máximo.
     *
     * Pulsar «Actualizar» tres veces seguidas eran tres veces las 19 lecturas en
     * Ktor para acabar enseñando lo mismo. Mientras hay una carga completa en
     * vuelo, las siguientes se ignoran: su resultado sería el de la que ya viene.
     */
    private suspend fun loadAll(trackScreen: Boolean) {
        if (fullLoadInFlight) return
        fullLoadInFlight = true
        try {
            load(ALL_BLOCKS, trackScreen)
        } finally {
            fullLoadInFlight = false
        }
    }

    private suspend fun load(requested: Set<DashboardBlock>, trackScreen: Boolean) {
        if (trackScreen) analyticsTracker.trackScreen("dashboard_v2")
        val token = ++requestCounter
        requested.forEach { latestRequestByBlock[it] = token }
        updateState { it.startLoading(requested) }
        // El contrato dice que un `sections` vacío significa TODAS. Mandarlo así
        // en vez de nombrar las seis no es cosmético: si mañana se añade una
        // séptima franja, esta llamada la trae sin tocar el cliente.
        val sections = if (requested == ALL_BLOCKS) emptySet() else requested
        val result = repository.getOverview(sections)
        // Sólo se aplica a las franjas para las que ESTA es todavía la petición más
        // reciente. Una franja que otra petición posterior ya ha vuelto a pedir se
        // deja a esa otra: su respuesta es más nueva que ésta, llegue cuando llegue.
        val stillCurrent: (DashboardBlock) -> Boolean = { block -> (latestRequestByBlock[block] ?: 0L) <= token }
        val applicable = requested.filterTo(mutableSetOf(), stillCurrent)
        when (result) {
            is AppResult.Failure -> updateState { it.applyCallFailure(applicable, result.error) }
            is AppResult.Success -> {
                val overview = result.value
                val fresh = overview.copy(
                    blocks = overview.blocks.filterKeys(stillCurrent),
                    failedBlocks = overview.failedBlocks.filterTo(mutableSetOf(), stillCurrent),
                )
                updateState { it.applyOverview(applicable, fresh) }
            }
        }
    }

    private companion object {
        val ALL_BLOCKS: Set<DashboardBlock> = DashboardBlock.entries.toSet()
    }
}
