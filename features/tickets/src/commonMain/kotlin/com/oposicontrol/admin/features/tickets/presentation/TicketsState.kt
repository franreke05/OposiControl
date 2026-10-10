package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.TicketsOverview

data class TicketsState(
    /** Lo que se pinta en el catálogo: las carteras que pasan búsqueda y filtro. */
    val wallets: UiAsync<List<WalletRow>> = UiAsync.Loading,
    /** Todas las carteras descargadas, sin filtrar: la ficha abierta se busca aquí aunque el filtro la oculte. */
    val allWallets: List<WalletRow> = emptyList(),
    val transactions: UiAsync<List<TicketMovement>> = UiAsync.Idle,
    val overview: UiAsync<TicketsOverview> = UiAsync.Idle,
    val searchQuery: String = "",
    val filter: WalletFilter = WalletFilter.ALL,
    val selectedUserId: String? = null,
    val tab: TicketsTab = TicketsTab.MOVEMENTS,
    val adjustmentAmount: String = "",
    val adjustmentReason: String = "",
    val pendingAdjustment: PendingAdjustment? = null,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
) {
    val selectedWallet: WalletRow? get() = allWallets.firstOrNull { it.userId == selectedUserId }

    /** Hay algo escrito en el formulario de ajuste que se perdería al cambiar de cartera. */
    val hasAdjustmentDraft: Boolean get() = adjustmentAmount.isNotBlank() || adjustmentReason.isNotBlank()
}
