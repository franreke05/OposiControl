package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class ModerationState(
    val actions: UiAsync<List<ModerationActionRow>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorActionType: String = "warning",
    val editorTargetUser: String = "",
    val editorExecutor: String = "Admin principal",
    val editorReason: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
