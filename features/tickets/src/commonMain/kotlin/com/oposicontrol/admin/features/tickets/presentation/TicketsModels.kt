package com.oposicontrol.admin.features.tickets.presentation

/** Una cartera tal como la lista el backend. El saldo es entero: los OposiTickets no tienen decimales. */
data class WalletRow(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Int,
)

/** Un asiento del ledger. Los campos auditables son `null` cuando el backend no los informa. */
data class TicketMovement(
    val id: String,
    val type: String,
    val amount: Int,
    val reason: String,
    val occurredAt: String,
    val balanceBefore: Int? = null,
    val balanceAfter: Int? = null,
    val originType: String? = null,
    val originId: String? = null,
)

/**
 * Filtro del catálogo de carteras. Se aplica en el cliente, sobre la lista ya descargada: `GET /api/v1/admin/tickets` no
 * acepta búsqueda ni filtros, y la interfaz no finge lo contrario.
 */
enum class WalletFilter(val label: String) {
    ALL("Todas"),
    WITH_BALANCE("Con saldo"),
    EMPTY("Sin saldo"),
}

/** Pestañas de la ficha de una cartera. */
enum class TicketsTab(val id: String, val label: String) {
    MOVEMENTS("movements", "Movimientos"),
    ADJUST("adjust", "Ajuste de saldo"),
    ;

    companion object {
        fun fromId(id: String): TicketsTab = entries.firstOrNull { it.id == id } ?: MOVEMENTS
    }
}

/**
 * Un ajuste ya validado, a la espera de confirmación o de un reintento.
 *
 * [idempotencyKey] se fija al pedir la confirmación e identifica la OPERACIÓN: confirmar dos veces, o reintentar tras un
 * fallo de red, envía la misma clave y el backend devuelve el asiento original en vez de mover el saldo dos veces.
 */
data class PendingAdjustment(
    val userId: String,
    val userName: String,
    val amount: Int,
    val reason: String,
    val idempotencyKey: String,
    /** `true` mientras el diálogo de confirmación está abierto. */
    val awaitingConfirmation: Boolean = true,
)
