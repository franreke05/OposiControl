package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumReplyPage
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import com.oposicontrol.admin.domain.model.ForumThreadPage
import com.oposicontrol.admin.domain.repository.ForumRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun threadDetail(associatedReportsCount: Int = 0) = ForumThreadDetail(
    id = "t1",
    categoryId = "c1",
    categoryName = "Policía Nacional",
    title = "Duda sobre el Tema 12",
    content = "Texto completo del hilo.",
    status = "publicada",
    pinned = false,
    authorName = "Arturo",
    createdAt = "2026-08-23T10:00:00Z",
    updatedAt = "2026-08-23T10:00:00Z",
    replyCount = 2,
    associatedReportsCount = associatedReportsCount,
)

private fun reply(id: String, createdAt: String = "2026-08-23T10:05:00Z") = ForumReply(
    id = id,
    authorName = "Marta",
    content = "Respuesta $id",
    status = "publicado",
    createdAt = createdAt,
)

private class FakeThreadForumRepository(
    private val threadResponder: () -> AppResult<ForumThreadDetail> = { AppResult.Success(threadDetail()) },
    private val repliesResponder: (cursor: String?) -> AppResult<ForumReplyPage> = { AppResult.Success(ForumReplyPage(listOf(reply("r1")), null)) },
) : ForumRepository {
    val repliesCalls = mutableListOf<String?>()
    var threadCallCount = 0

    override suspend fun listThreads(cursor: String?, reportedOnly: Boolean, categoryId: String?, pageSize: Int): AppResult<ForumThreadPage> =
        AppResult.Success(ForumThreadPage(emptyList(), null))

    override suspend fun getThread(threadId: String): AppResult<ForumThreadDetail> {
        threadCallCount++
        return threadResponder()
    }

    override suspend fun listReplies(threadId: String, cursor: String?, pageSize: Int): AppResult<ForumReplyPage> {
        repliesCalls += cursor
        return repliesResponder(cursor)
    }
}

private fun newViewModel(testScheduler: TestCoroutineScheduler, repository: ForumRepository) = ThreadDetailViewModel(
    threadId = "t1",
    dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
    forumRepository = repository,
    analyticsTracker = NoOpAnalyticsTracker,
)

class ThreadDetailViewModelTest {

    @Test
    fun load_onInit_populatesThreadAndFirstReplyPage() = runTest {
        val repository = FakeThreadForumRepository()
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val thread = assertIs<UiAsync.Success<ForumThreadDetail>>(viewModel.state.value.thread)
        assertEquals("t1", thread.value.id)
        val replies = assertIs<UiAsync.Success<List<ForumReply>>>(viewModel.state.value.replies)
        assertEquals(listOf("r1"), replies.value.map { it.id })
    }

    @Test
    fun load_withNoReplies_reachesTheEmptyState_notAnError() = runTest {
        val repository = FakeThreadForumRepository(repliesResponder = { AppResult.Success(ForumReplyPage(emptyList(), null)) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.replies)
        assertIs<UiAsync.Success<ForumThreadDetail>>(viewModel.state.value.thread)
    }

    @Test
    fun load_threadNotFound_surfacesANotFoundError() = runTest {
        val repository = FakeThreadForumRepository(threadResponder = { AppResult.Failure(AppError.NotFound("no existe")) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val thread = assertIs<UiAsync.Error>(viewModel.state.value.thread)
        assertIs<AppError.NotFound>(thread.error)
    }

    @Test
    fun load_threadFailure_doesNotBlockRepliesFromLoadingIndependently() = runTest {
        val repository = FakeThreadForumRepository(threadResponder = { AppResult.Failure(AppError.Network("sin conexion")) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.thread)
        assertIs<UiAsync.Success<List<ForumReply>>>(viewModel.state.value.replies)
    }

    @Test
    fun retry_afterAFailure_canSucceed() = runTest {
        var shouldFail = true
        val repository = FakeThreadForumRepository(threadResponder = {
            if (shouldFail) AppResult.Failure(AppError.Network("sin conexion")) else AppResult.Success(threadDetail())
        })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.thread)

        shouldFail = false
        viewModel.dispatch(ThreadDetailIntent.Load)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Success<ForumThreadDetail>>(viewModel.state.value.thread)
    }

    @Test
    fun pullToRefresh_togglesRefreshing_andReloadsThreadAndFirstPage() = runTest {
        val repository = FakeThreadForumRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.repliesCalls.clear()
        val threadCallsBefore = repository.threadCallCount

        viewModel.dispatch(ThreadDetailIntent.PullToRefresh)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.refreshing)
        assertEquals(threadCallsBefore + 1, repository.threadCallCount)
        assertEquals(listOf<String?>(null), repository.repliesCalls)
    }

    @Test
    fun loadMoreReplies_appendsTheNextPage_andAdvancesTheCursor() = runTest {
        val repository = FakeThreadForumRepository(repliesResponder = { cursor ->
            if (cursor == null) AppResult.Success(ForumReplyPage(listOf(reply("r1")), "cursor-2"))
            else AppResult.Success(ForumReplyPage(listOf(reply("r2")), null))
        })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies)
        testScheduler.advanceUntilIdle()

        val replies = assertIs<UiAsync.Success<List<ForumReply>>>(viewModel.state.value.replies)
        assertEquals(listOf("r1", "r2"), replies.value.map { it.id })
        assertNull(viewModel.state.value.nextRepliesCursor)
        assertEquals(false, viewModel.state.value.loadingMoreReplies)
    }

    @Test
    fun loadMoreReplies_onFailure_keepsExistingRepliesInstead_ofClobberingThem() = runTest {
        val repository = FakeThreadForumRepository(repliesResponder = { cursor ->
            if (cursor == null) AppResult.Success(ForumReplyPage(listOf(reply("r1")), "cursor-2"))
            else AppResult.Failure(AppError.Network("sin conexion"))
        })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies)
        testScheduler.advanceUntilIdle()

        val replies = assertIs<UiAsync.Success<List<ForumReply>>>(viewModel.state.value.replies)
        assertEquals(listOf("r1"), replies.value.map { it.id })
        assertEquals(false, viewModel.state.value.loadingMoreReplies)
    }

    @Test
    fun loadMoreReplies_withoutANextCursor_doesNotCallTheRepositoryAgain() = runTest {
        val repository = FakeThreadForumRepository(repliesResponder = { AppResult.Success(ForumReplyPage(listOf(reply("r1")), null)) })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.repliesCalls.clear()

        viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies)
        testScheduler.advanceUntilIdle()

        assertTrue(repository.repliesCalls.isEmpty())
    }

    @Test
    fun loadMoreReplies_dispatchedTwiceBeforeItSettles_onlyTriggersOnePageRequest() = runTest {
        val repository = FakeThreadForumRepository(repliesResponder = { cursor ->
            if (cursor == null) AppResult.Success(ForumReplyPage(listOf(reply("r1")), "cursor-2"))
            else AppResult.Success(ForumReplyPage(listOf(reply("r2")), null))
        })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        repository.repliesCalls.clear()

        viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies)
        viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.repliesCalls.count { it == "cursor-2" })
    }

    @Test
    fun associatedReportsCount_isCarriedThroughUnchanged_whenPositive() = runTest {
        val repository = FakeThreadForumRepository(threadResponder = { AppResult.Success(threadDetail(associatedReportsCount = 2)) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val thread = assertIs<UiAsync.Success<ForumThreadDetail>>(viewModel.state.value.thread)
        assertEquals(2, thread.value.associatedReportsCount)
    }

    @Test
    fun associatedReportsCount_isZero_whenThereAreNoRealReports() = runTest {
        val repository = FakeThreadForumRepository(threadResponder = { AppResult.Success(threadDetail(associatedReportsCount = 0)) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val thread = assertIs<UiAsync.Success<ForumThreadDetail>>(viewModel.state.value.thread)
        assertEquals(0, thread.value.associatedReportsCount)
    }
}
