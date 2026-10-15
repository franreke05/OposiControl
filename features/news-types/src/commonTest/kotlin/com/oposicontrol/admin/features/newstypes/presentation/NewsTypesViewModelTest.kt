package com.oposicontrol.admin.features.newstypes.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.model.TicketsOverview
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class NewsTypesViewModelTest {
    private val existing = NewsType("u-1", "Convocatorias", "convocatorias", true, code = "CONV", description = "Plazos", position = 4)

    private fun TestScope.viewModel(repo: FakeRepo) = NewsTypesViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repo,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun editingAnExistingTypeKeepsItsCodeDescriptionAndPosition() = runTest {
        val repo = FakeRepo(listOf(existing))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.dispatch(NewsTypesIntent.UpdateName("Convocatorias 2027"))
        vm.dispatch(NewsTypesIntent.Save)
        advanceUntilIdle()
        val saved = repo.saved.single()
        assertEquals("CONV", saved.code)
        assertEquals("Plazos", saved.description, "el PUT sustituye la fila: no se borra la descripcion")
        assertEquals(4, saved.position)
        assertEquals("Convocatorias 2027", saved.name)
    }

    @Test
    fun aNewTypeGetsAnAsciiCodeFromItsSlugAndTheNextPosition() = runTest {
        val repo = FakeRepo(listOf(existing))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.dispatch(NewsTypesIntent.CreateNew)
        vm.dispatch(NewsTypesIntent.UpdateName("Novedades legislativas"))
        vm.dispatch(NewsTypesIntent.UpdateSlug("Información pública"))
        vm.dispatch(NewsTypesIntent.Save)
        advanceUntilIdle()
        val saved = repo.saved.single()
        assertEquals("informacion-publica", saved.code)
        assertEquals(5, saved.position)
        assertEquals("", saved.id)
    }

    @Test
    fun aListFailureIsAnErrorNotAnEmptyCatalog() = runTest {
        val vm = viewModel(FakeRepo(emptyList(), listFailure = AppError.Network("backend 500 /api/v1/admin/tipos-noticia")))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.items)
    }

    @Test
    fun codeDerivationDropsEverythingTheBackendRejects() {
        assertEquals("ano-2026-oposiciones", newsTypeCodeFrom("  Año 2026: ¡Oposiciones!  "))
        assertEquals("", newsTypeCodeFrom("¿?¡!"))
    }

    private class FakeRepo(
        private val types: List<NewsType>,
        private val listFailure: AppError? = null,
    ) : AdminOperationsRepository {
        val saved = mutableListOf<NewsType>()

        override suspend fun getNewsTypes(): AppResult<List<NewsType>> =
            listFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(types)

        override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> {
            saved += newsType
            return AppResult.Success(newsType.copy(id = newsType.id.ifBlank { "u-new" }))
        }

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
        override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = error("no usado")
        override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = error("no usado")
    }
}
