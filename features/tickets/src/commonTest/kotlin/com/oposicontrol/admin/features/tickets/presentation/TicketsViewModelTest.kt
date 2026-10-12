package com.oposicontrol.admin.features.tickets.presentation

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class TicketsViewModelTest {
    private val ana = "11111111-1111-4111-8111-111111111111"
    private val luis = "22222222-2222-4222-8222-222222222222"

    private fun TestScope.viewModel(repository: FakeRepository): TicketsViewModel {
        var seed = 0
        return TicketsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
            keySeed = { "seed${++seed}" },
        )
    }

    @Test
    fun `opening the screen loads the wallets, opens the first one and reads the overview`() = runTest {
        val vm = viewModel(FakeRepository())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(ana, luis), (state.wallets as UiAsync.Success).value.map { it.userId })
        assertEquals(ana, state.selectedUserId)
        assertEquals(1, (state.transactions as UiAsync.Success).value.size)
        assertEquals(TicketsOverview(wallets = 2, movements = 5, credits = 4, debits = 1), (state.overview as UiAsync.Success).value)
    }

    @Test
    fun `search and balance filter are applied over the downloaded list and say empty when nothing matches`() = runTest {
        val vm = viewModel(FakeRepository())
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.UpdateFilter(WalletFilter.EMPTY))
        advanceUntilIdle()
        assertEquals(listOf(luis), (vm.state.value.wallets as UiAsync.Success).value.map { it.userId })

        vm.dispatch(TicketsIntent.UpdateSearch("nadie"))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, vm.state.value.wallets)
        assertEquals(2, vm.state.value.allWallets.size, "el filtro no descarta lo descargado")
        assertNotNull(vm.state.value.selectedWallet, "la ficha abierta se conserva aunque el filtro la oculte")
    }

    @Test
    fun `an adjustment is validated before any confirmation is offered`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("12,5"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("regalo"))
        vm.dispatch(TicketsIntent.RequestAdjustment)
        advanceUntilIdle()

        assertNull(vm.state.value.pendingAdjustment)
        assertTrue((vm.state.value.actionState as UiAsync.Error).error is AppError.Validation)
        assertTrue(repository.adjustments.isEmpty())
    }

    @Test
    fun `confirming sends the operation key once and reloads the wallet`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("+50"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("  Compensación por incidencia  "))
        vm.dispatch(TicketsIntent.RequestAdjustment)
        advanceUntilIdle()
        val pending = assertNotNull(vm.state.value.pendingAdjustment)
        assertTrue(pending.awaitingConfirmation)
        assertTrue(repository.adjustments.isEmpty(), "pedir confirmación no escribe nada")

        // Doble clic en confirmar: una sola operación.
        vm.dispatch(TicketsIntent.ConfirmAdjustment)
        vm.dispatch(TicketsIntent.ConfirmAdjustment)
        advanceUntilIdle()

        assertEquals(listOf(Adjustment(ana, 50, "Compensación por incidencia", pending.idempotencyKey)), repository.adjustments)
        val state = vm.state.value
        assertEquals(UiAsync.Success(Unit), state.actionState)
        assertEquals("", state.adjustmentAmount)
        assertNull(state.pendingAdjustment)
        assertEquals(TicketsTab.MOVEMENTS, state.tab)
        assertEquals(170, state.selectedWallet?.balance, "el saldo es el que devuelve el servidor al releer")
    }

    @Test
    fun `a failed adjustment keeps its key so retrying cannot apply it twice and a changed amount gets a new key`() = runTest {
        val repository = FakeRepository().apply { failNextAdjustment = true }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("-10"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("Corrección"))
        vm.dispatch(TicketsIntent.RequestAdjustment)
        advanceUntilIdle()
        val firstKey = assertNotNull(vm.state.value.pendingAdjustment).idempotencyKey
        vm.dispatch(TicketsIntent.ConfirmAdjustment)
        advanceUntilIdle()
        assertTrue(vm.state.value.actionState is UiAsync.Error)
        assertEquals("-10", vm.state.value.adjustmentAmount, "el formulario no se vacía si no se sabe qué pasó")

        // Reintento sin cambiar nada: misma clave.
        vm.dispatch(TicketsIntent.RequestAdjustment)
        advanceUntilIdle()
        assertEquals(firstKey, vm.state.value.pendingAdjustment?.idempotencyKey)
        vm.dispatch(TicketsIntent.ConfirmAdjustment)
        advanceUntilIdle()
        assertEquals(listOf(firstKey, firstKey), repository.attemptedKeys)

        // Otra cantidad es otra operación.
        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("-20"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("Corrección"))
        vm.dispatch(TicketsIntent.RequestAdjustment)
        advanceUntilIdle()
        assertNotEquals(firstKey, vm.state.value.pendingAdjustment?.idempotencyKey)
    }

    @Test
    fun `changing wallet drops the draft that was written for the previous one`() = runTest {
        val vm = viewModel(FakeRepository())
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("100"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("para Ana"))
        vm.dispatch(TicketsIntent.SelectWallet(luis))
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(luis, state.selectedUserId)
        assertEquals("", state.adjustmentAmount)
        assertEquals("", state.adjustmentReason)
        assertEquals(UiAsync.Empty, state.transactions)
    }

    @Test
    fun `arriving from a user profile opens that wallet and clears the filters that would hide it`() = runTest {
        val vm = viewModel(FakeRepository())
        advanceUntilIdle()
        vm.dispatch(TicketsIntent.UpdateFilter(WalletFilter.WITH_BALANCE))
        vm.dispatch(TicketsIntent.UpdateSearch("ana"))
        advanceUntilIdle()

        vm.dispatch(TicketsIntent.OpenWalletOf(luis))
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(luis, state.selectedUserId)
        assertEquals("", state.searchQuery)
        assertEquals(WalletFilter.ALL, state.filter)
        assertEquals(2, (state.wallets as UiAsync.Success).value.size)
    }

    @Test
    fun `a failed wallet read is an error with nothing invented and the overview failing does not hide the wallets`() = runTest {
        val down = viewModel(FakeRepository().apply { failWallets = true })
        advanceUntilIdle()
        assertTrue(down.state.value.wallets is UiAsync.Error)
        assertTrue(down.state.value.allWallets.isEmpty())

        val partial = viewModel(FakeRepository().apply { failOverview = true })
        advanceUntilIdle()
        assertTrue(partial.state.value.overview is UiAsync.Error)
        assertTrue(partial.state.value.wallets is UiAsync.Success)
    }

    private data class Adjustment(val userId: String, val amount: Int, val reason: String, val key: String)

    @Test
    fun `after an adjustment the catalogue and the movements stay on screen while they are reread`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        val seen = mutableListOf<TicketsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }

        vm.dispatch(TicketsIntent.UpdateAdjustmentAmount("+50"))
        vm.dispatch(TicketsIntent.UpdateAdjustmentReason("Compensación"))
        vm.dispatch(TicketsIntent.ApplyAdjustment)
        advanceUntilIdle()

        // Si pasaran por «cargando», la lista volveria arriba y ni la cartera ni el asiento nuevo podrian resaltarse.
        assertTrue(seen.none { it.wallets is UiAsync.Loading }, "el catalogo no se vacia al releer")
        assertTrue(seen.none { it.transactions is UiAsync.Loading }, "los movimientos no se vacian al releer")
        assertEquals(170, vm.state.value.selectedWallet?.balance)
        assertIs<UiAsync.Success<List<TicketMovement>>>(vm.state.value.transactions)
    }

    private inner class FakeRepository : AdminOperationsRepository {
        var failWallets = false
        var failOverview = false
        var failNextAdjustment = false
        val adjustments = mutableListOf<Adjustment>()
        val attemptedKeys = mutableListOf<String>()
        private var anaBalance = 120.0

        override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> =
            if (failWallets) {
                AppResult.Failure(AppError.Network("sin red"))
            } else {
                AppResult.Success(
                    listOf(
                        TicketWallet(ana, "Ana García", "TCAE", anaBalance, "active"),
                        TicketWallet(luis, "Luis Pérez", "", 0.0, "active"),
                    ),
                )
            }

        override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = AppResult.Success(
            if (userId == ana) {
                listOf(TicketTransaction("tx-1", ana, "ajuste", 20.0, "Bienvenida", "2026-10-09T14:32:10Z", 100, 120, "admin", null))
            } else {
                emptyList()
            },
        )

        override suspend fun appendTicketAdjustment(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<TicketTransaction> {
            attemptedKeys += idempotencyKey
            if (failNextAdjustment) {
                failNextAdjustment = false
                return AppResult.Failure(AppError.Network("timeout"))
            }
            adjustments += Adjustment(userId, amount, reason, idempotencyKey)
            if (userId == ana) anaBalance += amount
            return AppResult.Success(TicketTransaction("tx-new", userId, "ajuste", amount.toDouble(), reason, "2026-10-10T10:00:00Z"))
        }

        override suspend fun getTicketsOverview(): AppResult<TicketsOverview> =
            if (failOverview) AppResult.Failure(AppError.Network("sin red")) else AppResult.Success(TicketsOverview(2, 5, 4, 1))

        override suspend fun getNewsTypes(): AppResult<List<NewsType>> = AppResult.Success(emptyList())
        override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = AppResult.Success(newsType)
        override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = AppResult.Success(emptyList())
        override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> = AppResult.Success(access)
        override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = AppResult.Success(emptyList())
        override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> = AppResult.Success(emptyList())
        override suspend fun sendSupportMessage(conversationId: String, assignee: String, text: String, internal: Boolean): AppResult<SupportMessage> =
            AppResult.Failure(AppError.Validation("no usado"))
        override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = AppResult.Success(emptyList())
        override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = AppResult.Success(action)
    }
}
