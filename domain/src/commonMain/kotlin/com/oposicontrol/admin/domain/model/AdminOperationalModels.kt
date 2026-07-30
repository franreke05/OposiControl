package com.oposicontrol.admin.domain.model

data class NewsType(
    val id: String,
    val name: String,
    val slug: String,
    val isActive: Boolean,
)

data class ExamAccess(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val isActive: Boolean,
    val userId: String = "",
    val examId: String = "",
    val version: Long = 1,
    val updatedAt: String = "",
)

data class TicketWallet(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Long,
    val status: String,
)

data class TicketTransaction(
    val id: String,
    val userId: String,
    val type: String,
    val amount: Long,
    val reason: String,
    val occurredAt: String,
)

data class SupportConversationSummary(
    val id: String,
    val userName: String,
    val subject: String,
    val status: String,
    val assignedTo: String,
    val lastMessage: String,
)

data class SupportMessage(
    val id: String,
    val conversationId: String,
    val authorName: String,
    val text: String,
    val timestamp: String,
    val internal: Boolean,
    val attachmentLabel: String? = null,
)

data class ModerationAction(
    val id: String,
    val actionType: String,
    val targetUser: String,
    val executor: String,
    val reason: String,
    val createdAt: String,
    val targetUserId: String = "",
    val conversationId: String? = null,
)
