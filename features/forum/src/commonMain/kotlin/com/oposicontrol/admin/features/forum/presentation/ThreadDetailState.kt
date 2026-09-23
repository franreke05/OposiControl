package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumThreadDetail

data class ThreadDetailState(
    val thread: UiAsync<ForumThreadDetail> = UiAsync.Loading,
    val replies: UiAsync<List<ForumReply>> = UiAsync.Loading,
    val nextRepliesCursor: String? = null,
    val loadingMoreReplies: Boolean = false,
    val refreshing: Boolean = false,
)
