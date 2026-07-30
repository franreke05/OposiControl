package com.oposicontrol.admin.features.tickets.presentation

/** Stable wallet owner identifier used by presentation contracts. */
@JvmInline
value class WalletUserId(val value: String)

/** Stable ticket movement identifier used by presentation contracts. */
@JvmInline
value class TicketMovementId(val value: String)

data class WalletRow(
    val userId: WalletUserId,
    val userName: String,
    val specialty: String,
    val balance: Long,
    val status: String,
)

data class TicketMovement(
    val id: TicketMovementId,
    val type: String,
    val amount: Long,
    val reason: String,
    val occurredAt: String,
)
