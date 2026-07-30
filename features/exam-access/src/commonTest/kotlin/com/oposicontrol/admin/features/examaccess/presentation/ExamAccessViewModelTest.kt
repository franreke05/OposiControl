package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.InMemoryLogger
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ExamAccessViewModelTest {
    @Test
    fun `double save grants one access and emits success`() = runTest {
        val repository = ExamAccessRepository()
        val logger = InMemoryLogger()
        val viewModel = ExamAccessViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
            logger = logger,
        )
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(ExamAccessIntent.CreateNew)
        viewModel.dispatch(ExamAccessIntent.SelectUser(UserId("user-2"), "Ana"))
        viewModel.dispatch(ExamAccessIntent.SelectExam(ExamId("exam-2"), "TCAE"))
        viewModel.dispatch(ExamAccessIntent.Save)
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()

        assertEquals(1, repository.upsertCalls)
        assertEquals(
            ExamAccessEffect.Saved(ExamAccessId("access-new")),
            effect.await(),
        )
        val start = logger.entries.single { "event=exam_access.save.start " in it.message }
        val success = logger.entries.single { "event=exam_access.save.success " in it.message }
        assertEquals(startCorrelationId(start.message), startCorrelationId(success.message))
        assertEquals("OC-VM", start.tag)
        val rendered = logger.entries.joinToString("\n") { it.message }
        listOf("Ana", "TCAE", "user-2", "exam-2", "access-new").forEach {
            assertFalse(it in rendered)
        }
    }

    @Test
    fun `display names never resolve access relationships`() = runTest {
        val repository = ExamAccessRepository()
        val viewModel = ExamAccessViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(ExamAccessIntent.CreateNew)
        viewModel.dispatch(ExamAccessIntent.UpdateUserName("Ana"))
        viewModel.dispatch(ExamAccessIntent.UpdateExamTitle("TCAE"))
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()

        assertEquals(0, repository.upsertCalls)
        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
    }

    @Test
    fun `latest search query owns filtered accesses`() = runTest {
        val repository = ExamAccessRepository()
        val viewModel = ExamAccessViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(ExamAccessIntent.UpdateSearch("Ana"))
        viewModel.dispatch(ExamAccessIntent.UpdateSearch("TCAE"))
        advanceUntilIdle()

        val accesses = assertIs<UiAsync.Success<List<ExamAccessItem>>>(viewModel.state.value.accesses)
        assertEquals("TCAE", viewModel.state.value.searchQuery)
        assertEquals(listOf(ExamAccessId("access-1")), accesses.value.map(ExamAccessItem::id))
    }
}

private class ExamAccessRepository : AdminOperationsRepository {
    private var accesses = listOf(
        ExamAccess(
            id = "access-1",
            userName = "Ana",
            examTitle = "TCAE",
            accessType = "manual",
            isActive = true,
            userId = "user-1",
            examId = "exam-1",
        ),
    )
    var upsertCalls = 0
        private set

    override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = AppResult.Success(accesses)

    override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> {
        upsertCalls += 1
        val saved = access.copy(id = access.id.ifBlank { "access-new" })
        accesses = accesses.filterNot { it.id == saved.id } + saved
        return AppResult.Success(saved)
    }

    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = error("unused")
    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = error("unused")
    override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = error("unused")
    override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = error("unused")
    override suspend fun appendTicketAdjustment(
        userId: String,
        amount: Long,
        reason: String,
    ): AppResult<TicketTransaction> = error("unused")

    override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = error("unused")
    override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> = error("unused")
    override suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String,
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage> = error("unused")

    override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = error("unused")
    override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = error("unused")
}

private fun startCorrelationId(message: String): String =
    Regex("correlationId=([^ ]+)").find(message)?.groupValues?.get(1).orEmpty()
