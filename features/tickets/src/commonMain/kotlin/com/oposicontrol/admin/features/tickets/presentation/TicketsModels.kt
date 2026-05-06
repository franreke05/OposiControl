package com.oposicontrol.admin.features.tickets.presentation

data class WalletRow(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Double,
    val status: String,
)

data class TicketMovement(
    val id: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val occurredAt: String,
)
