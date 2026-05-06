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

class TicketsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<TicketsState, TicketsIntent>(
    initialState = TicketsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var wallets: List<WalletRow> = emptyList()

    init {
        analyticsTracker.trackScreen("tickets")
        dispatch(TicketsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: TicketsIntent) {
        when (intent) {
            TicketsIntent.Refresh -> refresh()
            TicketsIntent.ApplyAdjustment -> applyAdjustment()
            TicketsIntent.PrepareAdjustment -> updateState {
                it.copy(adjustmentAmount = "", adjustmentReason = "", actionState = UiAsync.Idle)
            }

            is TicketsIntent.SelectWallet -> selectWallet(intent.userId)
            is TicketsIntent.UpdateAdjustmentAmount -> updateState { it.copy(adjustmentAmount = intent.value) }
            is TicketsIntent.UpdateAdjustmentReason -> updateState { it.copy(adjustmentReason = intent.value) }
            is TicketsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishWallets()
            }
        }
    }

    private fun publishWallets() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = wallets.filter { wallet ->
            query.isBlank() || wallet.userName.lowercase().contains(query) || wallet.specialty.lowercase().contains(query)
        }
        updateState { it.copy(wallets = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        updateState { it.copy(wallets = UiAsync.Loading) }
        when (val result = repository.getTicketWallets()) {
            is AppResult.Failure -> updateState { it.copy(wallets = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                wallets = result.value.map { it.toRow() }
                publishWallets()
                if (currentState().selectedUserId == null) wallets.firstOrNull()?.let { selectWallet(it.userId) }
            }
        }
    }

    private suspend fun selectWallet(userId: String) {
        updateState { it.copy(selectedUserId = userId, transactions = UiAsync.Loading, actionState = UiAsync.Idle) }
        when (val result = repository.getTicketTransactions(userId)) {
            is AppResult.Failure -> updateState { it.copy(transactions = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(transactions = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.map { tx -> tx.toMovement() }))
            }
        }
    }

    private suspend fun applyAdjustment() {
        val selectedUserId = currentState().selectedUserId
            ?: run {
                updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una cartera antes de ajustar saldo."))) }
                return
            }
        val amount = currentState().adjustmentAmount.toDoubleOrNull()
        if (amount == null || currentState().adjustmentReason.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Importe y razon son obligatorios."))) }
            return
        }

        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = repository.appendTicketAdjustment(selectedUserId, amount, currentState().adjustmentReason)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("tickets_adjust", mapOf("user_id" to selectedUserId, "amount" to amount.toString()))
                updateState { it.copy(adjustmentAmount = "", adjustmentReason = "", actionState = UiAsync.Success(Unit)) }
                refresh()
                selectWallet(selectedUserId)
            }
        }
    }

    private fun TicketWallet.toRow() = WalletRow(
        userId = userId,
        userName = userName,
        specialty = specialty,
        balance = balance,
        status = status,
    )

    private fun TicketTransaction.toMovement() = TicketMovement(
        id = id,
        type = type,
        amount = amount,
        reason = reason,
        occurredAt = occurredAt,
    )
}
