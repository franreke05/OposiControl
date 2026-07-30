package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError

/** Immutable ticket-wallet state rendered by [TicketsContent]. */
data class TicketsState(
    val wallets: UiAsync<List<WalletRow>> = UiAsync.Loading,
    val transactions: UiAsync<List<TicketMovement>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedUserId: WalletUserId? = null,
    val adjustmentAmount: String = "",
    val adjustmentReason: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
)
