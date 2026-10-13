package com.oposicontrol.admin.features.examaccess.presentation

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
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExamAccessViewModelTest {
    private val ana = AccessUserOption("u-ana", "Ana García", "@ana")
    private val simulacro = AccessExamOption("e-1", "Simulacro 3", "TCAE · premium")
    private val oficial = AccessExamOption("e-2", "Oficial 2023", "Enfermería · premium")

    private fun access(id: String, active: Boolean = true, type: String = "admin") = ExamAccess(
        id = id,
        userName = "Ana García",
        examTitle = "Simulacro 3",
        accessType = type,
        isActive = active,
        userId = "u-ana",
        examId = "e-1",
        grantedAt = "2026-10-01T09:00:00+00:00",
    )

    @Test
    fun `opening the screen lists the accesses and opens the first one`() = runTest {
        val vm = viewModel(FakeRepository(listOf(access("a1"), access("a2", active = false))))
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("a1", "a2"), assertIs<UiAsync.Success<List<ExamAccessItem>>>(state.accesses).value.map { it.id })
        assertEquals("a1", state.selectedId)
        assertFalse(state.creating)
    }

    @Test
    fun `an empty table says empty and a failed read says error`() = runTest {
        val empty = viewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, empty.state.value.accesses)
        assertNull(empty.state.value.selectedId)

        val broken = viewModel(FakeRepository(failList = true))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(broken.state.value.accesses)
    }

    @Test
    fun `search and status filter are applied over the downloaded list`() = runTest {
        val vm = viewModel(FakeRepository(listOf(access("a1"), access("a2", active = false))))
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.UpdateFilter(AccessFilter.REVOKED))
        advanceUntilIdle()
        assertEquals(listOf("a2"), assertIs<UiAsync.Success<List<ExamAccessItem>>>(vm.state.value.accesses).value.map { it.id })

        vm.dispatch(ExamAccessIntent.UpdateSearch("nadie"))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, vm.state.value.accesses)
        assertEquals(2, vm.state.value.allAccesses.size, "el filtro no descarta lo descargado")
    }

    @Test
    fun `the grant form starts idle, loads the exam catalogue once and searches people only when asked`() = runTest {
        val sources = Sources(people = listOf(ana), exams = listOf(simulacro, oficial))
        val vm = viewModel(FakeRepository(), sources)
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.CreateNew)
        advanceUntilIdle()
        val opened = vm.state.value
        assertTrue(opened.creating)
        // En reposo: no se ha buscado a nadie, así que no hay nada «cargando».
        assertEquals(UiAsync.Idle, opened.userResults)
        assertEquals(listOf(simulacro, oficial), assertIs<UiAsync.Success<List<AccessExamOption>>>(opened.exams).value)

        vm.dispatch(ExamAccessIntent.UpdateUserQuery("ana"))
        advanceUntilIdle()
        assertTrue(sources.searches.isEmpty(), "teclear no lanza peticiones")
        vm.dispatch(ExamAccessIntent.SearchUsers)
        advanceUntilIdle()
        assertEquals(listOf("ana"), sources.searches)

        vm.dispatch(ExamAccessIntent.DiscardDraft)
        vm.dispatch(ExamAccessIntent.CreateNew)
        advanceUntilIdle()
        assertEquals(1, sources.examLoads, "el catálogo ya descargado no se vuelve a pedir")
    }

    @Test
    fun `granting sends the ids that were picked, no row id, and never an inactive access`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository, Sources(people = listOf(ana), exams = listOf(simulacro)))
        advanceUntilIdle()

        vm.pick(ana, simulacro)
        vm.dispatch(ExamAccessIntent.UpdateReason("regalo"))
        // Doble clic: una sola concesión.
        vm.dispatch(ExamAccessIntent.Grant)
        vm.dispatch(ExamAccessIntent.Grant)
        advanceUntilIdle()

        val sent = repository.granted.single()
        assertEquals("", sent.id, "una concesión nunca reutiliza el id de otra fila")
        assertEquals("u-ana", sent.userId)
        assertEquals("e-1", sent.examId)
        assertEquals("regalo", sent.accessType)
        assertTrue(sent.isActive)

        val state = vm.state.value
        assertFalse(state.creating)
        assertEquals("new-1", state.selectedId)
        assertEquals(UiAsync.Success(Unit), state.actionState)
        assertEquals(listOf("new-1"), assertIs<UiAsync.Success<List<ExamAccessItem>>>(state.accesses).value.map { it.id })
    }

    @Test
    fun `granting without a person or an exam is refused before any request`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository, Sources(people = listOf(ana), exams = listOf(simulacro)))
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.CreateNew)
        vm.dispatch(ExamAccessIntent.Grant)
        advanceUntilIdle()

        assertTrue(repository.granted.isEmpty())
        assertTrue(assertIs<UiAsync.Error>(vm.state.value.actionState).error.message.contains("persona"))
    }

    @Test
    fun `a purchase or a subscription cannot be chosen as the reason of a manual grant`() = runTest {
        val vm = viewModel(FakeRepository(), Sources(people = listOf(ana), exams = listOf(simulacro)))
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.CreateNew)
        vm.dispatch(ExamAccessIntent.UpdateReason("compra"))
        vm.dispatch(ExamAccessIntent.UpdateReason("premium"))
        advanceUntilIdle()

        assertEquals("admin", vm.state.value.reason)
        assertEquals(listOf("admin", "regalo", "promocion"), ExamAccessTypes.grantable.map { it.first })
    }

    @Test
    fun `a failed grant keeps the form so it can be retried`() = runTest {
        val repository = FakeRepository(failGrant = true)
        val vm = viewModel(repository, Sources(people = listOf(ana), exams = listOf(simulacro)))
        advanceUntilIdle()

        vm.pick(ana, simulacro)
        vm.dispatch(ExamAccessIntent.Grant)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.creating)
        assertEquals(ana, state.pickedUser)
        assertIs<UiAsync.Error>(state.actionState)
    }

    @Test
    fun `revoking asks first, writes once and rereads the list`() = runTest {
        val repository = FakeRepository(listOf(access("a1")))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.RequestRevoke)
        advanceUntilIdle()
        assertTrue(vm.state.value.confirmingRevoke)
        assertTrue(repository.revoked.isEmpty(), "pedir confirmación no escribe nada")

        vm.dispatch(ExamAccessIntent.ConfirmRevoke)
        vm.dispatch(ExamAccessIntent.ConfirmRevoke)
        advanceUntilIdle()

        assertEquals(listOf("a1"), repository.revoked)
        val state = vm.state.value
        assertFalse(state.confirmingRevoke)
        assertEquals("a1", state.selectedId)
        assertFalse(state.selected!!.isActive, "la lista releída ya lo trae retirado")
        assertEquals(UiAsync.Success(Unit), state.actionState)
    }

    @Test
    fun `a purchase and an already revoked access cannot be revoked from here`() = runTest {
        val repository = FakeRepository(listOf(access("a1", type = "compra"), access("a2", active = false)))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(ExamAccessIntent.RequestRevoke)
        vm.dispatch(ExamAccessIntent.Select("a2"))
        vm.dispatch(ExamAccessIntent.RequestRevoke)
        vm.dispatch(ExamAccessIntent.ConfirmRevoke)
        advanceUntilIdle()

        assertFalse(vm.state.value.confirmingRevoke)
        assertTrue(repository.revoked.isEmpty())
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

    @Test
    fun `dates are shown as day month year and never invented`() {
        assertEquals("13/10/2026", formatAccessDate("2026-10-13T09:15:00+00:00"))
        assertNull(formatAccessDate(null))
        assertNull(formatAccessDate("ayer"))
    }

    @Test
    fun `exam candidates are matched by title or specialty and capped`() {
        val many = (1..20).map { AccessExamOption("e$it", "Simulacro $it", "TCAE · premium") } + oficial
        assertEquals(EXAM_MATCH_LIMIT, examMatches(many, "").size)
        assertEquals(listOf(oficial), examMatches(many, "enfermer"))
        assertTrue(examMatches(many, "no existe").isEmpty())
    }

    private suspend fun ExamAccessViewModel.pick(user: AccessUserOption, exam: AccessExamOption) {
        dispatch(ExamAccessIntent.CreateNew)
        dispatch(ExamAccessIntent.UpdateUserQuery(user.label))
        dispatch(ExamAccessIntent.SearchUsers)
        dispatch(ExamAccessIntent.PickUser(user.id))
        dispatch(ExamAccessIntent.PickExam(exam.id))
    }

    private class Sources(val people: List<AccessUserOption> = emptyList(), val exams: List<AccessExamOption> = emptyList()) {
        val searches = mutableListOf<String>()
        var examLoads = 0
    }

    private fun TestScope.viewModel(repository: FakeRepository, sources: Sources = Sources()) = ExamAccessViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
        searchPeople = { query ->
            sources.searches += query
            AppResult.Success(sources.people)
        },
        loadExamCatalog = {
            sources.examLoads++
            AppResult.Success(sources.exams)
        },
    )

    private class FakeRepository(
        accesses: List<ExamAccess> = emptyList(),
        private val failList: Boolean = false,
        private val failGrant: Boolean = false,
    ) : AdminOperationsRepository {
        private val rows = accesses.toMutableList()
        val granted = mutableListOf<ExamAccess>()
        val revoked = mutableListOf<String>()

        override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> =
            if (failList) AppResult.Failure(AppError.Network("caída")) else AppResult.Success(rows.toList())

        override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> {
            if (failGrant) return AppResult.Failure(AppError.Network("caída"))
            granted += access
            val saved = access.copy(id = "new-${granted.size}")
            rows.add(0, saved)
            return AppResult.Success(saved)
        }

        override suspend fun revokeExamAccess(accessId: String): AppResult<ExamAccess> {
            revoked += accessId
            val index = rows.indexOfFirst { it.id == accessId }
            rows[index] = rows[index].copy(isActive = false, validUntil = "2026-10-13T10:00:00Z")
            return AppResult.Success(rows[index])
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
