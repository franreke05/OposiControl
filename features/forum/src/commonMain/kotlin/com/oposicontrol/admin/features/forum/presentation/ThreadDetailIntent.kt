package com.oposicontrol.admin.features.forum.presentation

sealed interface ThreadDetailIntent {
    data object Load : ThreadDetailIntent
    data object PullToRefresh : ThreadDetailIntent
    data object LoadMoreReplies : ThreadDetailIntent
}
