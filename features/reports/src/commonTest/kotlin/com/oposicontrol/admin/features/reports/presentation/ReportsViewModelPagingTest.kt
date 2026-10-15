package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.model.ForumReportsPage
import com.oposicontrol.admin.domain.repository.ModerationRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Informes paginados en el servidor: «cargar más» pide la página siguiente con el cursor y un fallo se dice. */
class ReportsViewModelPagingTest {
    private fun report(id: String) = ForumReport(id, "hilo", "e-$id", "Spam", "", "pendiente", "2026-08-22T10:00:00Z", "u1", "Ana")

    private class Repo(val responder: (includeResolved: Boolean, cursor: String?) -> AppResult<ForumReportsPage>) : ModerationRepository {
        val calls = mutableListOf<Pair<Boolean, String?>>()
        override suspend fun listReportsPage(includeResolved: Boolean, cursor: String?): AppResult<ForumReportsPage> {
            calls += includeResolved to cursor
            return responder(includeResolved, cursor)
        }
        override suspend fun listPendingReports(): AppResult<List<ForumReport>> = error("sin paginar: no se usa")
        override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = error("sin paginar: no se usa")
        override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("unused")
        override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("unused")
        override suspend fun grantTickets(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<Int> = error("unused")
        override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("unused")
    }

    private fun TestScope.vm(repo: Repo) = ReportsViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoOpAnalyticsTracker,
    ).also { testScheduler.advanceUntilIdle() }

    private fun ids(vm: ReportsViewModel) = (vm.state.value.reports as UiAsync.Success).value.map { it.id }

    @Test fun firstPageKeepsServerOrderCursorAndServerPendingTotal() = runTest {
        val viewModel = vm(Repo { _, _ -> AppResult.Success(ForumReportsPage(listOf(report("a"), report("b")), "c2", 57)) })
        assertEquals(listOf("a", "b"), ids(viewModel))
        assertEquals("c2", viewModel.state.value.nextCursor)
        assertEquals(57, viewModel.state.value.pendingTotal, "el contador es el del servidor, no las filas leidas")
    }

    @Test fun loadMoreSendsTheCursorAndAppendsWithoutDuplicates() = runTest {
        val repo = Repo { _, cursor ->
            if (cursor == null) AppResult.Success(ForumReportsPage(listOf(report("a"), report("b")), "c2", 3))
            else AppResult.Success(ForumReportsPage(listOf(report("b"), report("c")), null, 3))
        }
        val viewModel = vm(repo)
        viewModel.dispatch(ReportsIntent.LoadMore)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false to null, false to "c2"), repo.calls)
        assertEquals(listOf("a", "b", "c"), ids(viewModel))
        assertNull(viewModel.state.value.nextCursor)
    }

    @Test fun aFailedLoadMoreKeepsWhatWasReadSurfacesTheErrorAndCanBeRetried() = runTest {
        var fail = true
        val viewModel = vm(Repo { _, cursor ->
            when {
                cursor == null -> AppResult.Success(ForumReportsPage(listOf(report("a")), "c2", 2))
                fail -> AppResult.Failure(AppError.Network("caido"))
                else -> AppResult.Success(ForumReportsPage(listOf(report("b")), null, 2))
            }
        })
        viewModel.dispatch(ReportsIntent.LoadMore)
        testScheduler.advanceUntilIdle()
        assertIs<AppError.Network>(viewModel.state.value.loadMoreError)
        assertEquals(listOf("a"), ids(viewModel))
        assertEquals("c2", viewModel.state.value.nextCursor)

        fail = false
        viewModel.dispatch(ReportsIntent.LoadMore)
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.state.value.loadMoreError)
        assertEquals(listOf("a", "b"), ids(viewModel))
    }

    @Test fun switchingToAllRestartsPagingWithoutTheOldCursor() = runTest {
        val repo = Repo { _, _ -> AppResult.Success(ForumReportsPage(listOf(report("a")), "c2", 1)) }
        val viewModel = vm(repo)
        viewModel.dispatch(ReportsIntent.SetMainFilter(ReportsMainFilter.ALL))
        testScheduler.advanceUntilIdle()
        assertEquals(true to null, repo.calls.last())
    }
}
