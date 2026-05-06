package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class TicketsState(
    val wallets: UiAsync<List<WalletRow>> = UiAsync.Loading,
    val transactions: UiAsync<List<TicketMovement>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedUserId: String? = null,
    val adjustmentAmount: String = "",
    val adjustmentReason: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
