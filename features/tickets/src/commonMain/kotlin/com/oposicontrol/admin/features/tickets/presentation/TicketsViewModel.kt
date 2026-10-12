package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlin.random.Random

/**
 * OposiTickets: carteras, movimientos y ajustes de saldo.
 *
 * El saldo lo mueve siempre el servidor (RPC atómico e idempotente). Esta clase no calcula saldos: tras un ajuste vuelve a
 * leer la cartera y su ledger.
 */
class TicketsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    /** Semilla de las claves de operación. Inyectable para que las pruebas sean deterministas. */
    private val keySeed: () -> String = { Random.nextLong().toULong().toString(16) },
) : MviViewModel<TicketsState, TicketsIntent>(
    initialState = TicketsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var keySequence = 0
    private var adjustmentInFlight = false

    init {
        analyticsTracker.trackScreen("tickets")
        dispatch(TicketsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: TicketsIntent) {
        when (intent) {
            TicketsIntent.Refresh -> refresh()
            TicketsIntent.PrepareAdjustment -> updateState {
                it.copy(
                    tab = TicketsTab.ADJUST,
                    adjustmentAmount = "",
                    adjustmentReason = "",
                    pendingAdjustment = null,
                    actionState = UiAsync.Idle,
                )
            }
            is TicketsIntent.SelectWallet -> selectWallet(intent.userId)
            is TicketsIntent.OpenWalletOf -> openWalletOf(intent.userId)
            is TicketsIntent.SelectTab -> updateState { it.copy(tab = intent.tab) }
            is TicketsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishWallets()
            }
            is TicketsIntent.UpdateFilter -> {
                updateState { it.copy(filter = intent.filter) }
                publishWallets()
            }
            // Cambiar el formulario invalida una operación pendiente: es otra operación y necesita otra clave.
            is TicketsIntent.UpdateAdjustmentAmount -> updateState {
                it.copy(adjustmentAmount = intent.value, pendingAdjustment = null, actionState = UiAsync.Idle)
            }
            is TicketsIntent.UpdateAdjustmentReason -> updateState {
                it.copy(adjustmentReason = intent.value, pendingAdjustment = null, actionState = UiAsync.Idle)
            }
            TicketsIntent.RequestAdjustment -> requestAdjustment()
            TicketsIntent.ConfirmAdjustment -> confirmAdjustment()
            TicketsIntent.ApplyAdjustment -> {
                requestAdjustment()
                if (currentState().pendingAdjustment?.awaitingConfirmation == true) confirmAdjustment()
            }
            TicketsIntent.DismissAdjustment -> updateState { state ->
                state.copy(pendingAdjustment = state.pendingAdjustment?.copy(awaitingConfirmation = false))
            }
            TicketsIntent.DiscardAdjustment -> updateState {
                it.copy(adjustmentAmount = "", adjustmentReason = "", pendingAdjustment = null, actionState = UiAsync.Idle)
            }
            TicketsIntent.RetryTransactions -> currentState().selectedUserId?.let { loadTransactions(it) }
        }
    }

    private fun publishWallets() {
        val state = currentState()
        // Antes de la primera respuesta no hay nada que filtrar: se respeta el estado de carga o de error.
        if (state.wallets is UiAsync.Loading || (state.wallets is UiAsync.Error && state.allWallets.isEmpty())) return
        val filtered = state.allWallets.filter { walletMatches(it, state.searchQuery, state.filter) }
        updateState { it.copy(wallets = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        // Releer con datos ya en pantalla no los quita: el catalogo y los movimientos siguen ahi hasta que llega la
        // respuesta. Solo la primera lectura ensena el esqueleto. (Si se vaciaran, la lista volveria arriba y la
        // cartera y el asiento que acaban de cambiar no podrian resaltarse: naceria todo de nuevo.)
        val hadWallets = currentState().allWallets.isNotEmpty()
        updateState {
            it.copy(
                wallets = if (hadWallets) it.wallets else UiAsync.Loading,
                overview = if (hadWallets && it.overview is UiAsync.Success) it.overview else UiAsync.Loading,
            )
        }
        when (val result = repository.getTicketWallets()) {
            is AppResult.Failure -> updateState { it.copy(wallets = UiAsync.Error(result.error), allWallets = emptyList()) }
            is AppResult.Success -> {
                val rows = result.value.map { it.toRow() }
                val filtered = rows.filter { walletMatches(it, currentState().searchQuery, currentState().filter) }
                updateState {
                    it.copy(allWallets = rows, wallets = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered))
                }
                val selected = currentState().selectedUserId
                when {
                    selected != null -> loadTransactions(selected, keepShown = hadWallets)
                    rows.isNotEmpty() -> selectWallet(rows.first().userId)
                }
            }
        }
        // El resumen es independiente: si falla, el catálogo y la ficha siguen funcionando.
        val overview = when (val result = repository.getTicketsOverview()) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> UiAsync.Success(result.value)
        }
        updateState { it.copy(overview = overview) }
    }

    private suspend fun selectWallet(userId: String) {
        if (userId == currentState().selectedUserId && currentState().transactions is UiAsync.Success) return
        updateState {
            it.copy(
                selectedUserId = userId,
                // El borrador de ajuste era para la cartera anterior: aplicarlo a otra sería mover el saldo equivocado.
                adjustmentAmount = "",
                adjustmentReason = "",
                pendingAdjustment = null,
                actionState = UiAsync.Idle,
            )
        }
        loadTransactions(userId)
    }

    private suspend fun openWalletOf(userId: String) {
        // Se limpian búsqueda y filtro: quien llega desde una ficha quiere ver ESA cartera en el catálogo.
        updateState { it.copy(searchQuery = "", filter = WalletFilter.ALL, tab = TicketsTab.MOVEMENTS) }
        publishWallets()
        selectWallet(userId)
    }

    /** [keepShown]: se relee la MISMA cartera que ya se ve; sus movimientos siguen en pantalla hasta que llegan los nuevos. */
    private suspend fun loadTransactions(userId: String, keepShown: Boolean = false) {
        updateState { if (keepShown && it.selectedUserId == userId && it.transactions is UiAsync.Success) it else it.copy(transactions = UiAsync.Loading) }
        val loaded = when (val result = repository.getTicketTransactions(userId)) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.map { it.toMovement() })
        }
        // Si entre tanto se abrió otra cartera, esta respuesta ya no es la que se está mirando.
        updateState { if (it.selectedUserId == userId) it.copy(transactions = loaded) else it }
    }

    private fun requestAdjustment() {
        val state = currentState()
        val problem = adjustmentProblem(state.adjustmentAmount, state.adjustmentReason, hasWallet = state.selectedUserId != null)
        if (problem != null) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(problem))) }
            return
        }
        val userId = state.selectedUserId ?: return
        val amount = parseAdjustmentAmount(state.adjustmentAmount) ?: return
        val reason = state.adjustmentReason.trim()
        // Reintento de la MISMA operación (mismo usuario, cantidad y motivo): se conserva su clave.
        val previous = state.pendingAdjustment?.takeIf { it.userId == userId && it.amount == amount && it.reason == reason }
        val pending = previous?.copy(awaitingConfirmation = true) ?: PendingAdjustment(
            userId = userId,
            userName = state.selectedWallet?.userName ?: "la cartera seleccionada",
            amount = amount,
            reason = reason,
            idempotencyKey = "adjust:$userId:${keySeed()}:${++keySequence}",
        )
        updateState { it.copy(pendingAdjustment = pending, actionState = UiAsync.Idle) }
    }

    private suspend fun confirmAdjustment() {
        if (adjustmentInFlight) return
        val pending = currentState().pendingAdjustment ?: return
        adjustmentInFlight = true
        try {
            updateState { it.copy(pendingAdjustment = pending.copy(awaitingConfirmation = false), actionState = UiAsync.Loading) }
            when (val result = repository.appendTicketAdjustment(pending.userId, pending.amount, pending.reason, pending.idempotencyKey)) {
                // El resultado es DESCONOCIDO (pudo aplicarse): se conserva la operación y su clave para reintentar sin duplicar.
                is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
                is AppResult.Success -> {
                    analyticsTracker.trackAction("tickets_adjust", mapOf("user_id" to pending.userId, "amount" to pending.amount.toString()))
                    updateState {
                        it.copy(
                            adjustmentAmount = "",
                            adjustmentReason = "",
                            pendingAdjustment = null,
                            actionState = UiAsync.Success(Unit),
                            tab = TicketsTab.MOVEMENTS,
                        )
                    }
                    refresh()
                }
            }
        } finally {
            adjustmentInFlight = false
        }
    }

    private fun TicketWallet.toRow() = WalletRow(
        userId = userId,
        userName = userName,
        specialty = specialty,
        balance = balance.toInt(),
    )

    private fun TicketTransaction.toMovement() = TicketMovement(
        id = id,
        type = type,
        amount = amount.toInt(),
        reason = reason,
        occurredAt = occurredAt,
        balanceBefore = balanceBefore,
        balanceAfter = balanceAfter,
        originType = originType,
        originId = originId,
    )
}
