package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.TesterReport
import com.oposicontrol.admin.domain.model.TesterReportPage
import com.oposicontrol.admin.domain.repository.TesterRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun report(id: String, type: String = "pregunta_incorrecta") = TesterReport(
    id = id,
    authorName = "Autor",
    type = type,
    description = "Fragmento $id",
    status = "pendiente",
    createdAt = "2026-08-23T10:00:00Z",
)

private class FakeTesterRepository(
    private val responder: (status: String?, cursor: String?, type: String?) -> AppResult<TesterReportPage>,
) : TesterRepository {
    val calls = mutableListOf<Triple<String?, String?, String?>>()

    override suspend fun listReports(status: String?, cursor: String?, type: String?, pageSize: Int): AppResult<TesterReportPage> {
        calls += Triple(status, cursor, type)
        return responder(status, cursor, type)
    }

    // No usados por TesterListViewModel — solo Screen 07 (TesterDetailViewModel)
    // los ejercita, ver TesterDetailViewModelTest.
    override suspend fun getReport(id: String) = error("not used")
    override suspend fun resolveReport(id: String, estado: String, nota: String?) = error("not used")
}

private fun newViewModel(testScheduler: TestCoroutineScheduler, repository: TesterRepository) = TesterListViewModel(
    dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
    testerRepository = repository,
    analyticsTracker = NoOpAnalyticsTracker,
)

class TesterListViewModelTest {

    @Test
    fun load_onInit_defaultsToPendingStatus_andPopulatesReports() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Success(TesterReportPage(listOf(report("t1"), report("t2")), null)) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("t1", "t2"), success.value.map { it.id })
        assertEquals("pendiente", repository.calls.single().first)
    }

    @Test
    fun load_withNoReports_reachesTheEmptyState_notAnError() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Success(TesterReportPage(emptyList(), null)) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.reports)
    }

    @Test
    fun load_onFailure_surfacesTheAppError() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Failure(AppError.Network("sin conexion")) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.reports)
    }

    @Test
    fun retry_afterAFailure_canSucceed() = runTest {
        var shouldFail = true
        val repository = FakeTesterRepository { _, _, _ ->
            if (shouldFail) AppResult.Failure(AppError.Network("sin conexion")) else AppResult.Success(TesterReportPage(listOf(report("t1")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.reports)

        shouldFail = false
        viewModel.dispatch(TesterIntent.Load)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
    }

    @Test
    fun pullToRefresh_togglesRefreshingAndReloadsFirstPage() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Success(TesterReportPage(listOf(report("t1")), null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(TesterIntent.PullToRefresh)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.refreshing)
        assertEquals(1, repository.calls.size)
        assertEquals(Triple("pendiente", null, null), repository.calls.single())
    }

    @Test
    fun setMainFilter_toAll_resetsPaginationAndDropsTheStatusFilter() = runTest {
        val repository = FakeTesterRepository { status, _, _ ->
            AppResult.Success(TesterReportPage(items = if (status == null) listOf(report("all-1")) else listOf(report("t1")), nextCursor = null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterIntent.SetMainFilter(TesterMainFilter.ALL))
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("all-1"), success.value.map { it.id })
        assertEquals(null, repository.calls.last().first)
    }

    @Test
    fun setMainFilter_toTheSameFilterAlreadyActive_doesNotReload() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Success(TesterReportPage(listOf(report("t1")), null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(TesterIntent.SetMainFilter(TesterMainFilter.PENDING)) // ya es PENDING
        testScheduler.advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun setTypeFilter_resetsPaginationAndPassesTheTypeValue() = runTest {
        val repository = FakeTesterRepository { _, _, type ->
            AppResult.Success(TesterReportPage(items = if (type == "otro") listOf(report("filtered")) else listOf(report("t1")), nextCursor = null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterIntent.SetTypeFilter(TesterTypeOption("otro", "Otro")))
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("filtered"), success.value.map { it.id })
        assertEquals("otro", repository.calls.last().third)
    }

    @Test
    fun loadMore_appendsTheNextPage_andAdvancesTheCursor() = runTest {
        val repository = FakeTesterRepository { _, cursor, _ ->
            if (cursor == null) AppResult.Success(TesterReportPage(listOf(report("t1"), report("t2")), "cursor-2"))
            else AppResult.Success(TesterReportPage(listOf(report("t3")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("t1", "t2", "t3"), success.value.map { it.id })
        assertEquals(null, viewModel.state.value.nextCursor)
        assertEquals(false, viewModel.state.value.loadingMore)
    }

    @Test
    fun loadMore_neverProducesDuplicateItems_evenIfAPageOverlaps() = runTest {
        val repository = FakeTesterRepository { _, cursor, _ ->
            if (cursor == null) AppResult.Success(TesterReportPage(listOf(report("t1"), report("t2")), "cursor-2"))
            else AppResult.Success(TesterReportPage(listOf(report("t2"), report("t3")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("t1", "t2", "t3"), success.value.map { it.id })
    }

    @Test
    fun loadMore_withoutANextCursor_doesNotCallTheRepositoryAgain() = runTest {
        val repository = FakeTesterRepository { _, _, _ -> AppResult.Success(TesterReportPage(listOf(report("t1")), null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun loadMore_dispatchedTwiceBeforeItSettles_onlyTriggersOnePageRequest() = runTest {
        val repository = FakeTesterRepository { _, cursor, _ ->
            if (cursor == null) AppResult.Success(TesterReportPage(listOf(report("t1")), "cursor-2"))
            else AppResult.Success(TesterReportPage(listOf(report("t2")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(TesterIntent.LoadMore)
        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.calls.count { it.second == "cursor-2" })
    }

    /**
     * Regresión: `refresh()` llamaba a `loadFirstPage()` sin más, que ponía
     * `reports = UiAsync.Loading`. Como `PullToRefreshBox` ya muestra su propio
     * indicador con `refreshing`, el usuario veía desaparecer la lista y salir
     * el esqueleto *debajo* del spinner (doble loader). Durante un
     * pull-to-refresh el contenido anterior debe seguir visible.
     */
    @Test
    fun loadMore_failure_isSurfaced_doesNotAutoRetry_andRetryLoadsThePage() = runTest {
        var failNext = true
        val repository = FakeTesterRepository { _, cursor, _ ->
            when {
                cursor == null -> AppResult.Success(TesterReportPage(listOf(report("t1")), "cursor-2"))
                failNext -> AppResult.Failure(AppError.Network("sin conexion"))
                else -> AppResult.Success(TesterReportPage(listOf(report("t2")), null))
            }
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()
        assertIs<AppError.Network>(viewModel.state.value.loadMoreError)
        assertEquals(listOf("t1"), (viewModel.state.value.reports as UiAsync.Success).value.map { it.id })

        repository.calls.clear()
        viewModel.dispatch(TesterIntent.LoadMore)
        testScheduler.advanceUntilIdle()
        assertTrue(repository.calls.isEmpty(), "el desplazamiento no relanza la pagina fallida en bucle")

        failNext = false
        viewModel.dispatch(TesterIntent.RetryLoadMore)
        testScheduler.advanceUntilIdle()
        assertEquals(null, viewModel.state.value.loadMoreError)
        assertEquals(listOf("t1", "t2"), (viewModel.state.value.reports as UiAsync.Success).value.map { it.id })
    }

    @Test
    fun pullToRefresh_keepsThePreviousListVisibleWhileReloading() = runTest {
        val repository = GatedTesterRepository(firstPage = listOf(report("t1")), secondPage = listOf(report("t2")))
        val viewModel = newViewModel(testScheduler, repository)
        // Primero dejar que la carga inicial llegue a la puerta, y solo
        // entonces abrirla: `release()` antes de que el repositorio haya sido
        // llamado abriria una puerta que nadie esta esperando.
        testScheduler.advanceUntilIdle()
        repository.release()
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)

        viewModel.dispatch(TesterIntent.PullToRefresh)
        // La recarga queda parada dentro del repositorio: este es exactamente
        // el instante que veia el usuario con el doble loader.
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.refreshing, "el indicador de pull-to-refresh debe estar activo")
        val midRefresh = assertIs<UiAsync.Success<List<TesterReport>>>(
            viewModel.state.value.reports,
            "la lista no debe volver a Loading durante un pull-to-refresh",
        )
        assertEquals(listOf("t1"), midRefresh.value.map { it.id })

        repository.release()
        testScheduler.advanceUntilIdle()
        val after = assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)
        assertEquals(listOf("t2"), after.value.map { it.id })
        assertTrue(!viewModel.state.value.refreshing)
    }

    /**
     * Un cambio de filtro sí es una carga nueva: ahí el esqueleto es correcto,
     * porque la lista anterior ya no corresponde al filtro seleccionado. Sin
     * esta contraprueba, `loadFirstPage(preserveContent = true)` podria
     * aplicarse a todo y nadie se enteraria.
     */
    @Test
    fun changingTheFilter_stillShowsTheLoadingSkeleton() = runTest {
        val repository = GatedTesterRepository(firstPage = listOf(report("t1")), secondPage = listOf(report("t2")))
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.release()
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<List<TesterReport>>>(viewModel.state.value.reports)

        viewModel.dispatch(TesterIntent.SetMainFilter(TesterMainFilter.ALL))
        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Loading, viewModel.state.value.reports)

        repository.release()
        testScheduler.advanceUntilIdle()
    }
}

/**
 * Repositorio que se queda suspendido hasta que el test lo libera, para poder
 * observar el estado *a mitad* de una carga (lo que un fake sincrono no deja
 * ver: alli la carga entera se completa dentro del mismo turno del scheduler).
 */
private class GatedTesterRepository(
    private val firstPage: List<TesterReport>,
    private val secondPage: List<TesterReport>,
) : TesterRepository {
    private var gate = CompletableDeferred<Unit>()
    private var callCount = 0

    fun release() {
        gate.complete(Unit)
        gate = CompletableDeferred()
    }

    override suspend fun listReports(status: String?, cursor: String?, type: String?, pageSize: Int): AppResult<TesterReportPage> {
        val current = gate
        callCount++
        val page = if (callCount <= 1) firstPage else secondPage
        current.await()
        return AppResult.Success(TesterReportPage(page, null))
    }

    override suspend fun getReport(id: String) = error("not used")
    override suspend fun resolveReport(id: String, estado: String, nota: String?) = error("not used")
}
