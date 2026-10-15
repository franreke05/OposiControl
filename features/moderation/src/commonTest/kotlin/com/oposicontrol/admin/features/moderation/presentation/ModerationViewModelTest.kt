package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.model.TicketsOverview
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationViewModelTest {
    private val uid = "00000000-0000-4000-8000-000000000009"
    private val reportRow = ModerationAction("a-1", "advertir", "Ana", "Mod", "spam", "2026-10-01T10:00:00Z")

    private fun TestScope.viewModel(repo: FakeRepo) = ModerationViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repo,
        analyticsTracker = NoOpAnalyticsTracker,
        authRepository = NoSessionAuth,
    )

    @Test
    fun aWarningIsSentAsTheCanonicalCommandAndTheListIsReloadedFromTheServer() = runTest {
        val repo = FakeRepo(server = mutableListOf(reportRow))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ModerationIntent.CreateNew)
        vm.dispatch(ModerationIntent.UpdateTargetUser(" ${uid.uppercase()} "))
        vm.dispatch(ModerationIntent.UpdateReason("  lenguaje ofensivo "))
        vm.dispatch(ModerationIntent.Save)
        advanceUntilIdle()

        val sent = repo.sent.single()
        assertEquals("advertencia", sent.actionType)
        assertEquals(uid, sent.targetUser)
        assertEquals("lenguaje ofensivo", sent.reason)
        assertEquals("", sent.executor, "el ejecutor lo pone el servidor")
        assertEquals(2, repo.listCalls, "tras el exito se relee el servidor")
        val state = vm.state.value
        assertIs<UiAsync.Success<Unit>>(state.actionState)
        // Ninguna fila inventada: el historial es exactamente lo que devolvio el servidor.
        assertEquals(listOf("a-1"), (state.actions as UiAsync.Success).value.map { it.id })
        assertNull(state.selectedId)
        assertEquals("", state.editorTargetUser)
    }

    @Test
    fun aTargetThatIsNotAUuidIsRejectedWithoutCallingTheBackend() = runTest {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ModerationIntent.UpdateTargetUser("Ana Garcia"))
        vm.dispatch(ModerationIntent.UpdateReason("motivo"))
        vm.dispatch(ModerationIntent.Save)
        advanceUntilIdle()

        assertTrue(repo.sent.isEmpty())
        val error = assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertTrue("UUID" in error.error.message, error.error.message)
    }

    @Test
    fun aBackendRejectionIsShownAndNothingIsAddedToTheList() = runTest {
        val repo = FakeRepo(fail = AppError.Validation("backend 422 /api/v1/admin/moderacion/actions"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ModerationIntent.UpdateTargetUser(uid))
        vm.dispatch(ModerationIntent.UpdateReason("motivo"))
        vm.dispatch(ModerationIntent.Save)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals(UiAsync.Empty, vm.state.value.actions)
        assertEquals(1, repo.listCalls)
    }

    @Test
    fun selectingAHistoryRowNeverResendsIt() = runTest {
        val repo = FakeRepo(server = mutableListOf(reportRow))
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals("a-1", vm.state.value.selectedId, "la primera fila se abre al cargar")
        vm.dispatch(ModerationIntent.Save)
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
    }

    private object NoSessionAuth : AuthRepository {
        override fun observeSession(): Flow<Session?> = flowOf(null)
        override suspend fun login(credentials: AuthCredentials): AppResult<Session> = error("no usado")
        override suspend fun refreshSession(): AppResult<Session> = error("no usado")
        override suspend fun logout(): AppResult<Unit> = error("no usado")
    }

    private class FakeRepo(
        val server: MutableList<ModerationAction> = mutableListOf(),
        val fail: AppError? = null,
    ) : AdminOperationsRepository {
        val sent = mutableListOf<ModerationAction>()
        var listCalls = 0

        override suspend fun getModerationActions(): AppResult<List<ModerationAction>> {
            listCalls++
            return AppResult.Success(server.toList())
        }

        override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> {
            if (fail != null) return AppResult.Failure(fail)
            sent += action
            return AppResult.Success(action)
        }

        override suspend fun getNewsTypes(): AppResult<List<NewsType>> = error("no usado")
        override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = error("no usado")
        override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = error("no usado")
        override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> = error("no usado")
        override suspend fun revokeExamAccess(accessId: String): AppResult<ExamAccess> = error("no usado")
        override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = error("no usado")
        override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = error("no usado")
        override suspend fun appendTicketAdjustment(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<TicketTransaction> =
            error("no usado")
        override suspend fun getTicketsOverview(): AppResult<TicketsOverview> = error("no usado")
        override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = error("no usado")
        override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> = error("no usado")
        override suspend fun sendSupportMessage(conversationId: String, assignee: String, text: String, internal: Boolean): AppResult<SupportMessage> =
            error("no usado")
    }
}
