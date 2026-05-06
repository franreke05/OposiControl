package com.oposicontrol.admin.features.support.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class SupportState(
    val conversations: UiAsync<List<SupportConversation>> = UiAsync.Loading,
    val messages: UiAsync<List<SupportMessageItem>> = UiAsync.Loading,
    val searchQuery: String = "",
    val statusFilter: String = "all",
    val selectedConversationId: String? = null,
    val assignee: String = "",
    val replyText: String = "",
    val sendAsInternalNote: Boolean = false,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
