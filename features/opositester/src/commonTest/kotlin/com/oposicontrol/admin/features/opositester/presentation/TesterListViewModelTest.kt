package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.TesterReport
import com.oposicontrol.admin.domain.model.TesterReportPage
import com.oposicontrol.admin.domain.repository.TesterRepository
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
}
