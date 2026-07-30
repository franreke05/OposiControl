package com.oposicontrol.admin.features.tickets.presentation

/** One-shot ticket operation outcomes consumed by the route. */
sealed interface TicketsEffect {
    data class AdjustmentApplied(
        val userId: WalletUserId,
        val movementId: TicketMovementId,
    ) : TicketsEffect
}
