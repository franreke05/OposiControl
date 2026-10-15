package com.oposicontrol.admin.features.support.presentation

data class SupportConversation(
    val id: String,
    val userName: String,
    val subject: String,
    val status: String,
    val lastMessage: String,
)

data class SupportMessageItem(
    val id: String,
    val authorName: String,
    val text: String,
    val timestamp: String,
    val internal: Boolean,
    val attachmentLabel: String? = null,
)
