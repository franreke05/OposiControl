package com.oposicontrol.admin.features.opositester.presentation

sealed interface TesterIntent {
    data object Load : TesterIntent
    data object PullToRefresh : TesterIntent
    data object LoadMore : TesterIntent
    /** Reintento explícito tras un fallo de «cargar más» (el desplazamiento no reintenta solo en bucle). */
    data object RetryLoadMore : TesterIntent
    data class SetMainFilter(val filter: TesterMainFilter) : TesterIntent
    data class SetTypeFilter(val type: TesterTypeOption?) : TesterIntent
}
