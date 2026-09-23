package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.DashboardMetrics
import com.oposicontrol.admin.domain.repository.DashboardRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

private fun metrics(totalExams: Int) = DashboardMetrics(
    totalSpecialties = 3,
    totalExams = totalExams,
    publishedExams = 2,
    totalQuestions = 40,
    lastUpdatedAt = Instant.parse("2026-09-03T10:00:00Z"),
)

/**
 * Repositorio con compuerta.
 *
 * Sin la compuerta no se puede observar el estado INTERMEDIO —el que hay
 * mientras la petición está en vuelo— que es justamente lo que estas pruebas
 * tienen que comprobar: con `StandardTestDispatcher`, entre `dispatch` y
 * `advanceUntilIdle` no ha corrido nada todavía, y entre `advanceUntilIdle` y el
 * final ya ha corrido todo. `gate` abre esa ventana.
 */
private class FakeDashboardRepository : DashboardRepository {
    var response: AppResult<DashboardMetrics> = AppResult.Success(metrics(10))
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getMetrics(): AppResult<DashboardMetrics> {
        calls++
        gate?.await()
        return response
    }
}

/**
 * El dashboard se recarga solo cada cinco minutos. Estas pruebas fijan que esa
 * recarga no puede vaciar la pantalla que alguien está mirando.
 */
class DashboardRefreshTest {

    private fun newViewModel(scheduler: TestCoroutineScheduler, repository: DashboardRepository) =
        DashboardViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
            dashboardRepository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )

    @Test
    fun `la primera carga si muestra el esqueleto`() = runTest {
        val repository = FakeDashboardRepository()
        val viewModel = newViewModel(testScheduler, repository)

        // Antes de que responda el repositorio no hay nada que enseñar.
        assertIs<UiAsync.Loading>(viewModel.state.value.metrics)

        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<DashboardMetrics>>(viewModel.state.value.metrics)
    }

    @Test
    fun `un refresco en vuelo no borra los datos que ya se ven`() = runTest {
        val repository = FakeDashboardRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        // Refresco que se queda a medias, como el automático de cada 5 minutos.
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        repository.response = AppResult.Success(metrics(11))
        viewModel.dispatch(DashboardIntent.Refresh)
        testScheduler.advanceUntilIdle()

        // Éste es el momento que antes vaciaba la pantalla.
        val during = viewModel.state.value
        assertIs<UiAsync.Success<DashboardMetrics>>(during.metrics)
        assertEquals(10, (during.metrics as UiAsync.Success<DashboardMetrics>).value.totalExams)
        assertTrue(during.isRefreshing, "Un refresco sobre datos visibles debe marcarse con isRefreshing")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val after = viewModel.state.value
        assertEquals(11, (after.metrics as UiAsync.Success<DashboardMetrics>).value.totalExams)
        assertFalse(after.isRefreshing, "isRefreshing debe volver a false al terminar")
    }

    @Test
    fun `la primera carga en vuelo si es Loading`() = runTest {
        val repository = FakeDashboardRepository()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        // Sin datos previos sí corresponde el esqueleto: no hay nada que preservar.
        assertIs<UiAsync.Loading>(viewModel.state.value.metrics)
        assertFalse(viewModel.state.value.isRefreshing)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `si falla el refresco se conservan los datos anteriores`() = runTest {
        val repository = FakeDashboardRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        repository.response = AppResult.Failure(AppError.Network("sin red"))
        viewModel.dispatch(DashboardIntent.Refresh)
        testScheduler.advanceUntilIdle()

        // Unas métricas de hace cinco minutos valen más que una pantalla de error.
        val state = viewModel.state.value
        assertIs<UiAsync.Success<DashboardMetrics>>(state.metrics)
        assertEquals(10, (state.metrics as UiAsync.Success<DashboardMetrics>).value.totalExams)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `si falla la primera carga si se muestra el error`() = runTest {
        val repository = FakeDashboardRepository()
        repository.response = AppResult.Failure(AppError.Network("sin red"))
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.metrics)
    }
}
