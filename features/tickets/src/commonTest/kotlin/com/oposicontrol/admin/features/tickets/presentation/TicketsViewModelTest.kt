package com.oposicontrol.admin.features.tickets.presentation

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
class TicketsViewModelTest {
    @Test
    fun `double adjustment performs one write and keeps the resulting movement`() = runTest {
        val repository = TicketRepository()
        val logger = InMemoryLogger()
        val viewModel = TicketsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
            logger = logger,
        )
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(TicketsIntent.UpdateAdjustmentAmount("5"))
        viewModel.dispatch(TicketsIntent.UpdateAdjustmentReason("Compensacion"))
        viewModel.dispatch(TicketsIntent.ApplyAdjustment)
        viewModel.dispatch(TicketsIntent.ApplyAdjustment)
        advanceUntilIdle()

        assertEquals(1, repository.adjustmentCalls)
        assertEquals(
            TicketsEffect.AdjustmentApplied(
                userId = WalletUserId("user-1"),
                movementId = TicketMovementId("movement-new"),
            ),
            effect.await(),
        )
        val movements = assertIs<UiAsync.Success<List<TicketMovement>>>(viewModel.state.value.transactions)
        assertEquals(listOf(TicketMovementId("movement-new")), movements.value.map(TicketMovement::id))

        val start = logger.entries.single { "event=tickets.adjust.start " in it.message }
        val success = logger.entries.single { "event=tickets.adjust.success " in it.message }
        assertEquals(ticketCorrelationId(start.message), ticketCorrelationId(success.message))
        assertEquals("OC-VM", start.tag)
        val rendered = logger.entries.joinToString("\n") { it.message }
        assertFalse("Compensacion" in rendered)
        assertFalse("user-1" in rendered)
        assertFalse("movement-new" in rendered)
    }

    @Test
    fun `decimal adjustment is rejected before repository write`() = runTest {
        val repository = TicketRepository()
        val viewModel = TicketsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(TicketsIntent.UpdateAdjustmentAmount("1.5"))
        viewModel.dispatch(TicketsIntent.UpdateAdjustmentReason("No debe truncarse"))
        viewModel.dispatch(TicketsIntent.ApplyAdjustment)
        advanceUntilIdle()

        assertEquals(0, repository.adjustmentCalls)
        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
    }
}

private class TicketRepository : AdminOperationsRepository {
    private var wallets = listOf(
        TicketWallet(
            userId = "user-1",
            userName = "Ana",
            specialty = "TCAE",
            balance = 10L,
            status = "active",
        ),
    )
    private var movements = emptyList<TicketTransaction>()
    var adjustmentCalls = 0
        private set

    override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = AppResult.Success(wallets)

    override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> =
        AppResult.Success(movements.filter { it.userId == userId })

    override suspend fun appendTicketAdjustment(
        userId: String,
        amount: Long,
        reason: String,
    ): AppResult<TicketTransaction> {
        adjustmentCalls += 1
        val movement = TicketTransaction(
            id = "movement-new",
            userId = userId,
            type = "adjustment",
            amount = amount,
            reason = reason,
            occurredAt = "2026-07-29T10:00:00Z",
        )
        movements = listOf(movement)
        wallets = wallets.map { wallet ->
            if (wallet.userId == userId) wallet.copy(balance = wallet.balance + amount) else wallet
        }
        return AppResult.Success(movement)
    }

    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = error("unused")
    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = error("unused")
    override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = error("unused")
    override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> = error("unused")
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

private fun ticketCorrelationId(message: String): String =
    Regex("correlationId=([^ ]+)").find(message)?.groupValues?.get(1).orEmpty()
