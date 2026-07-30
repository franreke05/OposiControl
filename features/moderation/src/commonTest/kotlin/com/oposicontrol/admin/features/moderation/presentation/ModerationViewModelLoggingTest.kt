package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppError
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationViewModelLoggingTest {
    @Test
    fun `failed save logs one safe terminal event without stacktrace`() = runTest {
        val logger = InMemoryLogger()
        val viewModel = ModerationViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = FailingModerationRepository(),
            analyticsTracker = NoOpAnalyticsTracker,
            logger = logger,
        )
        advanceUntilIdle()

        viewModel.dispatch(ModerationIntent.CreateNew)
        viewModel.dispatch(ModerationIntent.UpdateTargetUser("persona@privado.test"))
        viewModel.dispatch(ModerationIntent.UpdateReason("Razon confidencial"))
        viewModel.dispatch(ModerationIntent.Save)
        advanceUntilIdle()

        val start = logger.entries.single { "event=moderation.action.save.start " in it.message }
        val failure = logger.entries.single { "event=moderation.action.save.failure " in it.message }
        assertEquals("OC-VM", start.tag)
        assertEquals(moderationCorrelationId(start.message), moderationCorrelationId(failure.message))
        assertEquals(0, failure.stackTraceCount)
        assertTrue("error_type=unknown" in failure.message)
        val rendered = logger.entries.joinToString("\n") { it.message }
        assertFalse("persona@privado.test" in rendered)
        assertFalse("Razon confidencial" in rendered)
        assertFalse("Detalle interno privado" in rendered)
    }
}

private class FailingModerationRepository : AdminOperationsRepository {
    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = AppResult.Success(emptyList())

    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> =
        AppResult.Success(newsType)

    override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = AppResult.Success(emptyList())

    override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> =
        AppResult.Success(access)

    override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = AppResult.Success(emptyList())

    override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> =
        AppResult.Success(emptyList())

    override suspend fun appendTicketAdjustment(
        userId: String,
        amount: Long,
        reason: String,
    ): AppResult<TicketTransaction> = error("Not used")

    override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> =
        AppResult.Success(emptyList())

    override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> =
        AppResult.Success(emptyList())

    override suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String,
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage> = error("Not used")

    override suspend fun getModerationActions(): AppResult<List<ModerationAction>> =
        AppResult.Success(emptyList())

    override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> =
        AppResult.Failure(
            AppError.Unknown(
                message = "Detalle interno privado",
                cause = IllegalStateException("Causa privada"),
            ),
        )
}

private fun moderationCorrelationId(message: String): String =
    Regex("correlationId=([^ ]+)").find(message)?.groupValues?.get(1).orEmpty()
