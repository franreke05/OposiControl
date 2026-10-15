package com.oposicontrol.admin.features.moderation.presentation

sealed interface ModerationIntent {
    data object CreateNew : ModerationIntent
    data object Refresh : ModerationIntent
    data object Save : ModerationIntent
    data class Select(val id: String) : ModerationIntent
    data class UpdateSearch(val value: String) : ModerationIntent
    data class UpdateTargetUser(val value: String) : ModerationIntent
    data class UpdateReason(val value: String) : ModerationIntent
}
