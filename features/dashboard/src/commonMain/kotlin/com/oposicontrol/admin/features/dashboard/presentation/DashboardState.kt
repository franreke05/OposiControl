package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.DashboardMetrics

data class DashboardState(
    val metrics: UiAsync<DashboardMetrics> = UiAsync.Loading,
    /**
     * Hay una recarga en curso sobre datos que YA se están mostrando.
     *
     * Es distinto de `metrics == Loading`, que significa que todavía no hay nada
     * que enseñar. La separación existe porque el dashboard se refresca solo cada
     * cinco minutos: si esa recarga volviera a poner `metrics` en `Loading`, la
     * pantalla entera desaparecería y volvería sola cada cinco minutos mientras
     * alguien está leyéndola.
     */
    val isRefreshing: Boolean = false,
    val quickActionsExpanded: Boolean = true,
    val summaryExpanded: Boolean = true,
)
