package com.oposicontrol.admin.features.tickets.presentation

sealed interface TicketsIntent {
    data object Refresh : TicketsIntent

    /** Acción primaria de la cabecera: abre el formulario de ajuste en blanco sobre la cartera seleccionada. */
    data object PrepareAdjustment : TicketsIntent

    data class SelectWallet(val userId: String) : TicketsIntent

    /** Llegada desde la ficha de un usuario: abre SU cartera aunque el filtro actual la oculte. */
    data class OpenWalletOf(val userId: String) : TicketsIntent

    data class SelectTab(val tab: TicketsTab) : TicketsIntent
    data class UpdateSearch(val value: String) : TicketsIntent
    data class UpdateFilter(val filter: WalletFilter) : TicketsIntent
    data class UpdateAdjustmentAmount(val value: String) : TicketsIntent
    data class UpdateAdjustmentReason(val value: String) : TicketsIntent

    /** Valida el formulario y, si es correcto, abre la confirmación con su clave de operación ya fijada. */
    data object RequestAdjustment : TicketsIntent
    data object ConfirmAdjustment : TicketsIntent

    /**
     * Valida y aplica en un solo paso, con su clave de operacion. Para superficies SIN dialogo de confirmacion propio (el
     * panel movil): en escritorio el camino es [RequestAdjustment] + [ConfirmAdjustment].
     */
    data object ApplyAdjustment : TicketsIntent
    data object DismissAdjustment : TicketsIntent
    data object DiscardAdjustment : TicketsIntent
    data object RetryTransactions : TicketsIntent
}
