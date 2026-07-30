package com.oposicontrol.admin.features.support.presentation

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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SupportViewModelTest {
    @Test
    fun `sending reply appends message to selected conversation`() = runTest {
        val logger = InMemoryLogger()
        val viewModel = SupportViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = FakeAdminOperationsRepository(),
            analyticsTracker = NoOpAnalyticsTracker,
            logger = logger,
        )

        viewModel.dispatch(SupportIntent.SelectConversation("conv-1"))
        viewModel.dispatch(SupportIntent.UpdateReplyText("Mensaje de prueba"))
        viewModel.dispatch(SupportIntent.SendReply)
        advanceUntilIdle()

        val state = viewModel.state.value
        val messages = (state.messages as UiAsync.Success).value
        assertEquals("conv-1", state.selectedConversationId)
        assertTrue(messages.any { it.text == "Mensaje de prueba" })
        assertEquals("", state.replyText)

        val start = logger.entries.single { "event=support.reply.send.start " in it.message }
        val success = logger.entries.single { "event=support.reply.send.success " in it.message }
        assertEquals("OC-VM", start.tag)
        assertEquals(correlationId(start.message), correlationId(success.message))
        val rendered = logger.entries.joinToString("\n") { it.message }
        assertFalse("Mensaje de prueba" in rendered)
        assertFalse("conv-1" in rendered)
    }

    private class FakeAdminOperationsRepository : AdminOperationsRepository {
        private val messages = mutableListOf(
            SupportMessage(
                id = "msg-1",
                conversationId = "conv-1",
                authorName = "Usuario",
                text = "Necesito ayuda",
                timestamp = "2026-05-05T09:00:00Z",
                internal = false,
            ),
        )

        override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = AppResult.Success(
            listOf(
                SupportConversationSummary(
                    id = "conv-1",
                    userName = "Usuario real",
                    subject = "Incidencia",
                    status = "open",
                    assignedTo = "Soporte",
                    lastMessage = messages.last().text,
                ),
            ),
        )

        override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> {
            return AppResult.Success(messages.filter { it.conversationId == conversationId })
        }

        override suspend fun sendSupportMessage(
            conversationId: String,
            assignee: String,
            text: String,
            internal: Boolean,
        ): AppResult<SupportMessage> {
            val message = SupportMessage(
                id = "msg-${messages.size + 1}",
                conversationId = conversationId,
                authorName = assignee,
                text = text,
                timestamp = "2026-05-05T09:05:00Z",
                internal = internal,
            )
            messages += message
            return AppResult.Success(message)
        }

        override suspend fun getNewsTypes(): AppResult<List<NewsType>> = AppResult.Success(emptyList())
        override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = AppResult.Success(newsType)
        override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = AppResult.Success(emptyList())
        override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> = AppResult.Success(access)
        override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = AppResult.Success(emptyList())
        override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = AppResult.Success(emptyList())
        override suspend fun appendTicketAdjustment(userId: String, amount: Long, reason: String): AppResult<TicketTransaction> =
            AppResult.Success(TicketTransaction("tx-1", userId, "adjustment", amount, reason, "2026-05-05T09:00:00Z"))

        override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = AppResult.Success(emptyList())
        override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = AppResult.Success(action)
    }
}

private fun correlationId(message: String): String =
    Regex("correlationId=([^ ]+)").find(message)?.groupValues?.get(1).orEmpty()
