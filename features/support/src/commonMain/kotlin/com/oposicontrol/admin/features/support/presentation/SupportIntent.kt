package com.oposicontrol.admin.features.support.presentation

sealed interface SupportIntent {
    data object Refresh : SupportIntent
    data object SendReply : SupportIntent
    data class SelectConversation(val id: String) : SupportIntent
    data class UpdateSearch(val value: String) : SupportIntent
    data class UpdateStatusFilter(val value: String) : SupportIntent
    data class UpdateAssignee(val value: String) : SupportIntent
    data class UpdateReplyText(val value: String) : SupportIntent
}
