package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.domain.model.DashboardBlock

/**
 * Intenciones del panel de escritorio.
 *
 * No hay `OpenRoute`: navegar no cambia el estado de esta pantalla, así que
 * pasarlo por el ViewModel sólo obligaría al módulo de dashboard a conocer
 * `AppRoute`, que vive en `composeApp`. La navegación sale como callback
 * (`onOpen: (DashboardV2Destination) -> Unit`) y es `AppRoot` quien traduce cada
 * destino a su ruta — igual que ya hacen Moderación, Analítica y Market.
 */
sealed interface DashboardV2Intent {

    /** Carga inicial: las seis franjas en una única petición. */
    data object Load : DashboardV2Intent

    /** Recarga manual de todo el panel, sin borrar lo que ya se está viendo. */
    data object RefreshAll : DashboardV2Intent

    /**
     * Recarga una sola franja.
     *
     * Es lo que permite refrescar el estado del sistema cada minuto sin volver a
     * pagar los conteos de contenido y economía, y lo que usa el botón
     * «Reintentar» de una franja concreta que falló.
     */
    data class RefreshBlock(val block: DashboardBlock) : DashboardV2Intent
}
