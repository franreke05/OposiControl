package com.oposicontrol.admin.features.forum.presentation

sealed interface ForumIntent {
    data object Load : ForumIntent
    data object PullToRefresh : ForumIntent
    data object LoadMore : ForumIntent
    data class SetMainFilter(val filter: ForumMainFilter) : ForumIntent
    data class SetCategoryFilter(val category: ForumCategoryOption?) : ForumIntent
}
