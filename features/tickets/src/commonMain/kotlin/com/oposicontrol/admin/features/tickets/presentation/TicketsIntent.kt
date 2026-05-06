package com.oposicontrol.admin.features.tickets.presentation

sealed interface TicketsIntent {
    data object Refresh : TicketsIntent
    data object PrepareAdjustment : TicketsIntent
    data object ApplyAdjustment : TicketsIntent
    data class SelectWallet(val userId: String) : TicketsIntent
    data class UpdateSearch(val value: String) : TicketsIntent
    data class UpdateAdjustmentAmount(val value: String) : TicketsIntent
    data class UpdateAdjustmentReason(val value: String) : TicketsIntent
}
