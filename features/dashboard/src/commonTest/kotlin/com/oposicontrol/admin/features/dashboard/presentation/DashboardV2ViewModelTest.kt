package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardOverview
import com.oposicontrol.admin.domain.repository.DashboardOverviewRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val T0 = Instant.fromEpochSeconds(1_756_000_000L)

private fun report(block: DashboardBlock, value: Int = 1) = DashboardBlockReport(
    block = block,
    metrics = listOf(
        AnalyticsMetric("k_${block.wire}", AnalyticsValue.Count(value), AnalyticsMetricStatus.AVAILABLE),
    ),
    generatedAt = T0,
)

private fun overview(
    blocks: Set<DashboardBlock>,
    failed: Set<DashboardBlock> = emptySet(),
    value: Int = 1,
) = DashboardOverview(
    blocks = (blocks - failed).associateWith { report(it, value) },
    failedBlocks = failed,
    generatedAt = T0,
)

/**
 * Doble de repositorio.
 *
 * Es un doble y no el implementador real a propósito: `KtorDashboardOverviewRepositoryImpl`
 * lo está escribiendo Hashira A en paralelo, y el ViewModel no debe depender de
 * que exista para poder probarse.
 */
private class FakeOverviewRepository(
    private val responder: (Set<DashboardBlock>) -> AppResult<DashboardOverview>,
) : DashboardOverviewRepository {
    val requests = mutableListOf<Set<DashboardBlock>>()

    override suspend fun getOverview(sections: Set<DashboardBlock>): AppResult<DashboardOverview> {
        requests += sections
        return responder(sections)
    }
}

/**
 * Repositorio que NO responde hasta que el caso lo decide: cada petición queda
 * colgada de su propio `CompletableDeferred`. Es lo que permite que la respuesta
 * de una petición vieja llegue DESPUÉS que la de una nueva, que es el orden que
 * PC-10 tiene que resistir.
 */
private class ControlledOverviewRepository : DashboardOverviewRepository {
    val requests = mutableListOf<Set<DashboardBlock>>()
    val pending = mutableListOf<CompletableDeferred<AppResult<DashboardOverview>>>()

    override suspend fun getOverview(sections: Set<DashboardBlock>): AppResult<DashboardOverview> {
        requests += sections
        val answer = CompletableDeferred<AppResult<DashboardOverview>>()
        pending += answer
        return answer.await()
    }
}

private val ALL = DashboardBlock.entries.toSet()

class DashboardV2ViewModelTest {

    private fun newViewModel(
        scheduler: TestCoroutineScheduler,
        repository: DashboardOverviewRepository,
    ) = DashboardV2ViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun laCargaInicialPideTodasLasFranjasEnUnaSolaLlamada() = runTest {
        // Una petición, no seis: las seis franjas se ven a la vez, así que
        // pedirlas por separado sólo multiplicaría los viajes de red.
        val repository = FakeOverviewRepository { AppResult.Success(overview(ALL)) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(emptySet()),
            repository.requests,
            "El contrato dice que un `sections` vacío significa todas.",
        )
        ALL.forEach { block ->
            assertIs<UiAsync.Success<DashboardBlockReport>>(viewModel.state.value.block(block))
        }
    }

    @Test
    fun refrescarUnaFranjaPideSoloEsaFranja() = runTest {
        val repository = FakeOverviewRepository { sections ->
            AppResult.Success(overview(sections.ifEmpty { ALL }))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(emptySet(), setOf(DashboardBlock.SYSTEM)),
            repository.requests,
            "El autorefresco de estado no puede repetir los conteos de contenido y economía.",
        )
    }

    @Test
    fun recargarNoBorraLoQueYaSeEstabaViendo() {
        // Se prueba sobre la transición pura: es exactamente la garantía, y así
        // no depende de atrapar al ViewModel en mitad de una llamada en vuelo.
        val loaded = DashboardV2State(
            blocks = mapOf(DashboardBlock.SYSTEM to UiAsync.Success(report(DashboardBlock.SYSTEM))),
        )

        val refreshing = loaded.startLoading(setOf(DashboardBlock.SYSTEM, DashboardBlock.PULSE))

        assertIs<UiAsync.Success<DashboardBlockReport>>(
            refreshing.block(DashboardBlock.SYSTEM),
            "Una franja con datos no puede volver a Loading: desaparecería mientras se lee.",
        )
        assertTrue(refreshing.isRefreshing(DashboardBlock.SYSTEM))
        assertEquals(
            UiAsync.Loading,
            refreshing.block(DashboardBlock.PULSE),
            "Una franja sin datos sí va a Loading: ahí no hay nada que conservar.",
        )
        assertTrue(DashboardBlock.PULSE !in refreshing.refreshing)
    }

    @Test
    fun unFalloDeRefrescoNoBorraLosDatosPrevios() = runTest {
        var failing = false
        val repository = FakeOverviewRepository { sections ->
            if (failing) {
                AppResult.Failure(AppError.Network("upstream caido"))
            } else {
                AppResult.Success(overview(sections.ifEmpty { ALL }, value = 7))
            }
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        failing = true
        viewModel.dispatch(DashboardV2Intent.RefreshAll)
        testScheduler.advanceUntilIdle()

        val system = viewModel.state.value.block(DashboardBlock.SYSTEM)
        assertIs<UiAsync.Success<DashboardBlockReport>>(
            system,
            "Unos datos de hace un minuto son más útiles que una pantalla de error.",
        )
        assertEquals(AnalyticsValue.Count(7), system.value.metrics.single().value)
        assertTrue(viewModel.state.value.refreshing.isEmpty())
    }

    @Test
    fun unFalloEnLaPrimeraCargaSiDejaLasFranjasEnError() = runTest {
        val repository = FakeOverviewRepository { AppResult.Failure(AppError.Network("sin red")) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        ALL.forEach { block ->
            assertIs<UiAsync.Error>(viewModel.state.value.block(block))
        }
    }

    @Test
    fun unaFranjaEnFailedBlocksPasaAErrorSinTumbarALasDemas() = runTest {
        // Ktor no pudo componer ECONOMY. Eso no es un fallo de la llamada: las
        // otras cinco franjas llegaron y se pintan.
        val repository = FakeOverviewRepository {
            AppResult.Success(overview(ALL, failed = setOf(DashboardBlock.ECONOMY)))
        }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.block(DashboardBlock.ECONOMY))
        (ALL - DashboardBlock.ECONOMY).forEach { block ->
            assertIs<UiAsync.Success<DashboardBlockReport>>(viewModel.state.value.block(block))
        }
    }

    @Test
    fun unaFranjaPedidaQueNoLlegaNiComoDatoNiComoFalloEsError() = runTest {
        // Violación del contrato. Fallar en silencio dejaría esa franja girando
        // para siempre.
        val repository = FakeOverviewRepository {
            AppResult.Success(
                DashboardOverview(blocks = emptyMap(), failedBlocks = emptySet(), generatedAt = T0),
            )
        }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        ALL.forEach { block ->
            assertIs<UiAsync.Error>(viewModel.state.value.block(block))
        }
        assertTrue(viewModel.state.value.refreshing.isEmpty())
    }

    // -----------------------------------------------------------------
    // PC-10 · Respuestas viejas, refrescos duplicados y datos desactualizados
    // -----------------------------------------------------------------

    /**
     * Dos refrescos de SYSTEM en vuelo; el SEGUNDO responde antes. La respuesta del
     * primero, que llega después, es más vieja y no puede pisar a la nueva: el panel
     * retrocedería en el tiempo sin ninguna señal.
     */
    @Test
    fun unaRespuestaViejaQueLlegaTardeNoPisaAUnaNueva() = runTest {
        val repository = ControlledOverviewRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.pending[0].complete(AppResult.Success(overview(ALL, value = 1)))
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()
        assertEquals(3, repository.requests.size, "precondición: dos refrescos de SYSTEM en vuelo a la vez")

        repository.pending[2].complete(AppResult.Success(overview(setOf(DashboardBlock.SYSTEM), value = 3)))
        testScheduler.advanceUntilIdle()
        repository.pending[1].complete(AppResult.Success(overview(setOf(DashboardBlock.SYSTEM), value = 2)))
        testScheduler.advanceUntilIdle()

        val system = assertIs<UiAsync.Success<DashboardBlockReport>>(viewModel.state.value.block(DashboardBlock.SYSTEM))
        assertEquals(
            AnalyticsValue.Count(3),
            system.value.metrics.single().value,
            "la respuesta vieja (2) pisó a la nueva (3)",
        )
        assertTrue(viewModel.state.value.refreshing.isEmpty())
    }

    /** Un «Actualizar» con otra carga completa en vuelo no lanza las 19 lecturas otra vez. */
    @Test
    fun unRefreshAllConOtraCargaCompletaEnVueloNoLanzaUnaSegundaPeticion() = runTest {
        val repository = ControlledOverviewRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(DashboardV2Intent.RefreshAll)
        viewModel.dispatch(DashboardV2Intent.RefreshAll)
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.requests.size, "la carga inicial sigue en vuelo: los dos «Actualizar» sobran")

        repository.pending[0].complete(AppResult.Success(overview(ALL)))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(DashboardV2Intent.RefreshAll)
        testScheduler.advanceUntilIdle()
        assertEquals(2, repository.requests.size, "terminada la carga, «Actualizar» vuelve a pedir")
    }

    /** El refresco automático de SYSTEM no espera a una carga completa: es otra petición, más pequeña. */
    @Test
    fun elRefrescoDeSistemaNoQuedaBloqueadoPorUnaCargaCompletaEnVuelo() = runTest {
        val repository = ControlledOverviewRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(emptySet(), setOf(DashboardBlock.SYSTEM)), repository.requests)
    }

    @Test
    fun trasUnFalloDeLaPeticionLasFranjasConDatosSeMarcanDesactualizadasHastaElSiguienteExito() = runTest {
        var failing = false
        val repository = FakeOverviewRepository { sections ->
            if (failing) AppResult.Failure(AppError.Network("upstream caido")) else AppResult.Success(overview(sections.ifEmpty { ALL }))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.stale.isEmpty())

        failing = true
        viewModel.dispatch(DashboardV2Intent.RefreshAll)
        testScheduler.advanceUntilIdle()
        assertEquals(ALL, viewModel.state.value.stale, "los datos conservados tienen que decir que son de antes")
        ALL.forEach { assertIs<UiAsync.Success<DashboardBlockReport>>(viewModel.state.value.block(it)) }

        failing = false
        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()
        assertTrue(DashboardBlock.SYSTEM !in viewModel.state.value.stale, "SYSTEM ya tiene datos nuevos")
        assertTrue(DashboardBlock.PULSE in viewModel.state.value.stale, "PULSE sigue siendo de antes: el aviso es por franja")
    }

    @Test
    fun unaFranjaSinDatosQueFallaEsErrorYNoDesactualizada() {
        val failed = DashboardV2State().applyCallFailure(setOf(DashboardBlock.ECONOMY), AppError.Network("sin red"))

        assertIs<UiAsync.Error>(failed.block(DashboardBlock.ECONOMY))
        assertTrue(failed.stale.isEmpty(), "no hay datos viejos que avisar: se pinta el error")
    }

    @Test
    fun unaRespuestaConMasFranjasDeLasPedidasSeAprovechaEntera() = runTest {
        val repository = FakeOverviewRepository { AppResult.Success(overview(ALL)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        testScheduler.advanceUntilIdle()

        ALL.forEach { block ->
            assertIs<UiAsync.Success<DashboardBlockReport>>(viewModel.state.value.block(block))
        }
    }
}
