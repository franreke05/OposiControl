package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppResult
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ExamAccessViewModelTest {

    @Test
    fun `the default access type is admin`() = runTest {
        val viewModel = viewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals("admin", ExamAccessState().editorAccessType)
        assertEquals("admin", viewModel.state.value.editorAccessType)
    }

    @Test
    fun `creating a new access resets the type to admin`() = runTest {
        val viewModel = viewModel(FakeRepository())
        viewModel.dispatch(ExamAccessIntent.UpdateAccessType("premium"))
        viewModel.dispatch(ExamAccessIntent.CreateNew)
        advanceUntilIdle()
        assertEquals("admin", viewModel.state.value.editorAccessType)
    }

    @Test
    fun `saving with the default sends admin and never manual`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.dispatch(ExamAccessIntent.CreateNew)
        viewModel.dispatch(ExamAccessIntent.UpdateUserName("ana"))
        viewModel.dispatch(ExamAccessIntent.UpdateExamTitle("Examen A"))
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()
        assertEquals("admin", repository.upserted.single().accessType)
    }

    @Test
    fun `saving sends the selected code`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.dispatch(ExamAccessIntent.CreateNew)
        viewModel.dispatch(ExamAccessIntent.UpdateUserName("ana"))
        viewModel.dispatch(ExamAccessIntent.UpdateExamTitle("Examen A"))
        viewModel.dispatch(ExamAccessIntent.UpdateAccessType("regalo"))
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()
        assertEquals("regalo", repository.upserted.single().accessType)
    }

    @Test
    fun `a legacy manual value from the server is edited and saved as admin`() = runTest {
        val repository = FakeRepository(
            listOf(ExamAccess(id = "a1", userName = "ana", examTitle = "Examen A", accessType = "manual", isActive = true)),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals("admin", viewModel.state.value.editorAccessType)
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()
        assertEquals("admin", repository.upserted.single().accessType)
    }

    @Test
    fun `editing a loaded row sends its user and exam ids, a renamed user goes without id`() = runTest {
        val repository = FakeRepository(
            listOf(
                ExamAccess(
                    id = "a1", userName = "Ana", examTitle = "Examen A", accessType = "admin", isActive = true,
                    userId = "00000000-0000-4000-8000-000000000001", examId = "00000000-0000-4000-8000-000000000002",
                ),
            ),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()
        assertEquals("00000000-0000-4000-8000-000000000001", repository.upserted.last().userId)
        assertEquals("00000000-0000-4000-8000-000000000002", repository.upserted.last().examId)

        viewModel.dispatch(ExamAccessIntent.UpdateUserName("Otra Persona"))
        viewModel.dispatch(ExamAccessIntent.Save)
        advanceUntilIdle()
        assertEquals(null, repository.upserted.last().userId, "un nombre nuevo no arrastra el id anterior")
        assertEquals("00000000-0000-4000-8000-000000000002", repository.upserted.last().examId)
    }

    @Test
    fun `the selector offers exactly the five canonical values`() {
        assertEquals(
            listOf("admin", "premium", "compra", "regalo", "promocion"),
            ExamAccessTypes.options.map { it.first },
        )
        assertEquals(
            listOf("Administración", "Premium", "Compra", "Regalo", "Promoción"),
            ExamAccessTypes.options.map { it.second },
        )
    }

    @Test
    fun `labels map canonical codes and show unknown values as they are`() {
        assertEquals("Administración", ExamAccessTypes.label("admin"))
        assertEquals("Premium", ExamAccessTypes.label("premium"))
        assertEquals("Compra", ExamAccessTypes.label("compra"))
        assertEquals("Regalo", ExamAccessTypes.label("regalo"))
        assertEquals("Promoción", ExamAccessTypes.label("promocion"))
        assertEquals("Administración", ExamAccessTypes.label("manual"))
        assertEquals("Administración", ExamAccessTypes.label(""))
        assertEquals("prueba-x", ExamAccessTypes.label("prueba-x"))
    }

    private fun TestScope.viewModel(repository: FakeRepository) = ExamAccessViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    private class FakeRepository(private val accesses: List<ExamAccess> = emptyList()) : AdminOperationsRepository {
        val upserted = mutableListOf<ExamAccess>()

        override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = AppResult.Success(accesses)
        override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> {
            upserted += access
            return AppResult.Success(access.copy(id = access.id.ifBlank { "new-id" }))
        }

        override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = AppResult.Success(emptyList())
        override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> = AppResult.Success(emptyList())
        override suspend fun sendSupportMessage(
            conversationId: String,
            assignee: String,
            text: String,
            internal: Boolean,
        ): AppResult<SupportMessage> = AppResult.Success(SupportMessage("m", conversationId, assignee, text, "", internal))

        override suspend fun getNewsTypes(): AppResult<List<NewsType>> = AppResult.Success(emptyList())
        override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = AppResult.Success(newsType)
        override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = AppResult.Success(emptyList())
        override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = AppResult.Success(emptyList())
        override suspend fun appendTicketAdjustment(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<TicketTransaction> =
            AppResult.Success(TicketTransaction("tx-1", userId, "adjustment", amount.toDouble(), reason, ""))

        override suspend fun getTicketsOverview(): AppResult<com.oposicontrol.admin.domain.model.TicketsOverview> =
            AppResult.Success(com.oposicontrol.admin.domain.model.TicketsOverview())

        override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = AppResult.Success(emptyList())
        override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = AppResult.Success(action)
    }
}
