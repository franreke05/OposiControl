package com.oposicontrol.admin.features.support.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class SupportState(
    val conversations: UiAsync<List<SupportConversation>> = UiAsync.Loading,
    val messages: UiAsync<List<SupportMessageItem>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedConversationId: String? = null,
    val replyText: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
) {
    /** Enviar solo con texto y sin otro envío en curso: un doble clic no manda dos respuestas. */
    val canSendReply: Boolean get() = selectedConversationId != null && replyText.isNotBlank() && actionState !is UiAsync.Loading
}
