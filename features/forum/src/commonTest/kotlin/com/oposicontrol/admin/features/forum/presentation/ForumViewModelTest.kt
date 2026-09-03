package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ForumReplyPage
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.model.ForumThread
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import com.oposicontrol.admin.domain.model.ForumThreadPage
import com.oposicontrol.admin.domain.repository.ForumRepository
import com.oposicontrol.admin.domain.repository.ModerationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun thread(id: String, createdAt: String = "2026-08-22T10:00:00Z", categoryId: String? = "c1", categoryName: String = "Policía Nacional") = ForumThread(
    id = id,
    categoryId = categoryId,
    categoryName = categoryName,
    title = "Hilo $id",
    snippet = "Fragmento $id",
    status = "publicada",
    pinned = false,
    authorName = "Autor",
    createdAt = createdAt,
    updatedAt = createdAt,
    replyCount = 0,
    hasPendingReport = false,
)

private class FakeForumRepository(
    private val responder: (cursor: String?, reportedOnly: Boolean, categoryId: String?) -> AppResult<ForumThreadPage>,
) : ForumRepository {
    val calls = mutableListOf<Triple<String?, Boolean, String?>>()

    override suspend fun listThreads(cursor: String?, reportedOnly: Boolean, categoryId: String?, pageSize: Int): AppResult<ForumThreadPage> {
        calls += Triple(cursor, reportedOnly, categoryId)
        return responder(cursor, reportedOnly, categoryId)
    }

    override suspend fun getThread(threadId: String): AppResult<ForumThreadDetail> = error("not used by ForumViewModelTest")
    override suspend fun listReplies(threadId: String, cursor: String?, pageSize: Int): AppResult<ForumReplyPage> = error("not used by ForumViewModelTest")
}

/**
 * Repositorio que se queda suspendido hasta que el test lo libera, para poder
 * observar el estado *a mitad* de una carga (lo que un fake sincrono no deja
 * ver: alli la carga entera se completa dentro del mismo turno del scheduler).
 */
private class GatedForumRepository(
    private val firstPage: List<ForumThread>,
    private val secondPage: List<ForumThread>,
) : ForumRepository {
    private var gate = CompletableDeferred<Unit>()
    private var callCount = 0

    fun release() {
        gate.complete(Unit)
        gate = CompletableDeferred()
    }

    override suspend fun listThreads(cursor: String?, reportedOnly: Boolean, categoryId: String?, pageSize: Int): AppResult<ForumThreadPage> {
        val current = gate
        callCount++
        val page = if (callCount <= 1) firstPage else secondPage
        current.await()
        return AppResult.Success(ForumThreadPage(page, null))
    }

    override suspend fun getThread(threadId: String): AppResult<ForumThreadDetail> = error("not used by ForumViewModelTest")
    override suspend fun listReplies(threadId: String, cursor: String?, pageSize: Int): AppResult<ForumReplyPage> = error("not used by ForumViewModelTest")
}

private class FakeModerationRepository(
    private val pendingReports: AppResult<List<ForumReport>> = AppResult.Success(emptyList()),
) : ModerationRepository {
    override suspend fun listPendingReports(): AppResult<List<ForumReport>> = pendingReports
    override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = AppResult.Success(emptyList())
    override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("not used by ForumViewModel")
    override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("not used by ForumViewModel")
    override suspend fun grantTickets(userId: String, amount: Int, reason: String): AppResult<Int> = error("not used by ForumViewModel")
    override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("not used by ForumViewModel")
}

private fun newViewModel(
    testScheduler: TestCoroutineScheduler,
    forumRepository: ForumRepository,
    moderationRepository: ModerationRepository = FakeModerationRepository(),
) = ForumViewModel(
    dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
    forumRepository = forumRepository,
    moderationRepository = moderationRepository,
    analyticsTracker = NoOpAnalyticsTracker,
)

class ForumViewModelTest {

    @Test
    fun load_onInit_startsLoadingThenPopulatesThreadsOnSuccess() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(items = listOf(thread("t1"), thread("t2")), nextCursor = null)) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("t1", "t2"), success.value.map { it.id })
        assertNull(viewModel.state.value.nextCursor)
    }

    @Test
    fun load_withNoThreads_reachesTheEmptyState_notAnError() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(items = emptyList(), nextCursor = null)) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.threads)
    }

    @Test
    fun load_onFailure_surfacesTheAppError() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Failure(AppError.Network("sin conexion")) }
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.threads)
    }

    @Test
    fun retry_afterAFailure_canSucceed() = runTest {
        var shouldFail = true
        val repository = FakeForumRepository { _, _, _ ->
            if (shouldFail) AppResult.Failure(AppError.Network("sin conexion")) else AppResult.Success(ForumThreadPage(listOf(thread("t1")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.threads)

        shouldFail = false
        viewModel.dispatch(ForumIntent.Load)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
    }

    @Test
    fun pullToRefresh_togglesRefreshingAndReloadsFirstPage() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(listOf(thread("t1")), null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(ForumIntent.PullToRefresh)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.refreshing)
        assertEquals(1, repository.calls.size)
        assertEquals(Triple(null, false, null), repository.calls.single())
    }

    @Test
    fun setMainFilter_toReported_resetsPaginationAndAsksForReportedOnly() = runTest {
        val repository = FakeForumRepository { _, reportedOnly, _ ->
            AppResult.Success(ForumThreadPage(items = if (reportedOnly) listOf(thread("reported-1")) else listOf(thread("t1")), nextCursor = null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ForumIntent.SetMainFilter(ForumMainFilter.REPORTED))
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("reported-1"), success.value.map { it.id })
        assertTrue(repository.calls.last().second) // reportedOnly = true on the last call
    }

    @Test
    fun setMainFilter_toTheSameFilterAlreadyActive_doesNotReload() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(listOf(thread("t1")), null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(ForumIntent.SetMainFilter(ForumMainFilter.RECENT)) // already RECENT
        testScheduler.advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun setCategoryFilter_resetsPaginationAndPassesTheCategoryId() = runTest {
        val repository = FakeForumRepository { _, _, categoryId ->
            AppResult.Success(ForumThreadPage(items = if (categoryId == "c9") listOf(thread("filtered")) else listOf(thread("t1")), nextCursor = null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ForumIntent.SetCategoryFilter(ForumCategoryOption("c9", "Guardia Civil")))
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("filtered"), success.value.map { it.id })
        assertEquals("c9", repository.calls.last().third)
    }

    @Test
    fun loadMore_appendsTheNextPage_andAdvancesTheCursor() = runTest {
        val repository = FakeForumRepository { cursor, _, _ ->
            if (cursor == null) {
                AppResult.Success(ForumThreadPage(items = listOf(thread("t1"), thread("t2")), nextCursor = "cursor-2"))
            } else {
                AppResult.Success(ForumThreadPage(items = listOf(thread("t3")), nextCursor = null))
            }
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ForumIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("t1", "t2", "t3"), success.value.map { it.id })
        assertNull(viewModel.state.value.nextCursor)
        assertEquals(false, viewModel.state.value.loadingMore)
    }

    @Test
    fun loadMore_neverProducesDuplicateItems_evenIfAPageOverlaps() = runTest {
        val repository = FakeForumRepository { cursor, _, _ ->
            if (cursor == null) {
                AppResult.Success(ForumThreadPage(items = listOf(thread("t1"), thread("t2")), nextCursor = "cursor-2"))
            } else {
                // Simula un solape defensivo: t2 vuelve a aparecer en la página siguiente.
                AppResult.Success(ForumThreadPage(items = listOf(thread("t2"), thread("t3")), nextCursor = null))
            }
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ForumIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("t1", "t2", "t3"), success.value.map { it.id })
    }

    @Test
    fun loadMore_withoutANextCursor_doesNotCallTheRepositoryAgain() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(items = listOf(thread("t1")), nextCursor = null)) }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        viewModel.dispatch(ForumIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun loadMore_dispatchedTwiceBeforeItSettles_onlyTriggersOnePageRequest() = runTest {
        val repository = FakeForumRepository { cursor, _, _ ->
            if (cursor == null) AppResult.Success(ForumThreadPage(listOf(thread("t1")), "cursor-2"))
            else AppResult.Success(ForumThreadPage(listOf(thread("t2")), null))
        }
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.calls.clear()

        // Dos disparos consecutivos, tal como una lista que dispara el trigger de
        // "cerca del final" varias veces mientras el usuario sigue haciendo scroll.
        viewModel.dispatch(ForumIntent.LoadMore)
        viewModel.dispatch(ForumIntent.LoadMore)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.calls.count { it.first == "cursor-2" })
    }

    @Test
    fun pendingReportsCount_usesTheSameSourceAsHomeAndInformes() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(emptyList(), null)) }
        val moderation = FakeModerationRepository(pendingReports = AppResult.Success(listOf(mockReport("r1"), mockReport("r2"), mockReport("r3"))))
        val viewModel = newViewModel(testScheduler, repository, moderation)

        testScheduler.advanceUntilIdle()

        assertEquals(3, viewModel.state.value.pendingReportsCount)
    }

    @Test
    fun pendingReportsCount_staysNull_whenTheCallFails_ratherThanShowingAWrongZero() = runTest {
        val repository = FakeForumRepository { _, _, _ -> AppResult.Success(ForumThreadPage(emptyList(), null)) }
        val moderation = FakeModerationRepository(pendingReports = AppResult.Failure(AppError.Network("sin conexion")))
        val viewModel = newViewModel(testScheduler, repository, moderation)

        testScheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.pendingReportsCount)
    }

    /**
     * Regresión: `refresh()` llamaba a `loadFirstPage()` sin más, que ponía
     * `threads = UiAsync.Loading`. Como `PullToRefreshBox` ya muestra su propio
     * indicador con `refreshing`, el usuario veía desaparecer la lista y salir
     * el esqueleto *debajo* del spinner (doble loader). Durante un
     * pull-to-refresh el contenido anterior debe seguir visible.
     */
    @Test
    fun pullToRefresh_keepsThePreviousListVisibleWhileReloading() = runTest {
        val repository = GatedForumRepository(firstPage = listOf(thread("h1")), secondPage = listOf(thread("h2")))
        val viewModel = newViewModel(testScheduler, repository)
        // Primero dejar que la carga inicial llegue a la puerta, y solo
        // entonces abrirla: `release()` antes de que el repositorio haya sido
        // llamado abriria una puerta que nadie esta esperando.
        testScheduler.advanceUntilIdle()
        repository.release()
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)

        viewModel.dispatch(ForumIntent.PullToRefresh)
        // La recarga queda parada dentro del repositorio: este es exactamente
        // el instante que veia el usuario con el doble loader.
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.refreshing, "el indicador de pull-to-refresh debe estar activo")
        val midRefresh = assertIs<UiAsync.Success<List<ForumThread>>>(
            viewModel.state.value.threads,
            "la lista no debe volver a Loading durante un pull-to-refresh",
        )
        assertEquals(listOf("h1"), midRefresh.value.map { it.id })

        repository.release()
        testScheduler.advanceUntilIdle()
        val after = assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)
        assertEquals(listOf("h2"), after.value.map { it.id })
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
        val repository = GatedForumRepository(firstPage = listOf(thread("h1")), secondPage = listOf(thread("h2")))
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.release()
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<List<ForumThread>>>(viewModel.state.value.threads)

        viewModel.dispatch(ForumIntent.SetMainFilter(ForumMainFilter.REPORTED))
        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Loading, viewModel.state.value.threads)

        repository.release()
        testScheduler.advanceUntilIdle()
    }

    private fun mockReport(id: String) = ForumReport(
        id = id,
        entityType = "hilo",
        entityId = "e-$id",
        reason = "motivo",
        description = "",
        status = "pendiente",
        createdAt = "2026-08-22T10:00:00Z",
        reporterId = "u1",
        reporterName = "Reportero",
    )
}
