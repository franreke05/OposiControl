package com.oposicontrol.admin.features.forum.presentation

sealed interface ForumIntent {
    data object Load : ForumIntent
    data object PullToRefresh : ForumIntent
    data object LoadMore : ForumIntent
    /** Reintento explícito tras un fallo de «cargar más» (el desplazamiento no reintenta solo en bucle). */
    data object RetryLoadMore : ForumIntent
    /** Vuelve a contar los reportes pendientes tras un fallo. */
    data object RetryPendingReportsCount : ForumIntent
    data class SetMainFilter(val filter: ForumMainFilter) : ForumIntent
    data class SetCategoryFilter(val category: ForumCategoryOption?) : ForumIntent
}
