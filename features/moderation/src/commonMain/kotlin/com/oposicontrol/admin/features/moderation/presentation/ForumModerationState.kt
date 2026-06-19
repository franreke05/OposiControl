package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ForumReport

data class ForumModerationState(
    val reports: UiAsync<List<ForumReport>> = UiAsync.Loading,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)

sealed interface ForumModerationIntent {
    data object Refresh : ForumModerationIntent
    data class Resolve(
        val reportId: String,
        val action: String,
        val notes: String?,
        val ticketsReporter: Int,
    ) : ForumModerationIntent
}
