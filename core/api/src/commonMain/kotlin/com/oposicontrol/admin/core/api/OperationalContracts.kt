package com.oposicontrol.admin.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ExamAccessResponse(
    val id: Long,
    val userId: Long,
    val examId: Long,
    val accessType: String,
    val active: Boolean,
    val version: Long,
    val updatedAt: String,
    val userDisplayName: String = "",
    val examTitle: String = "",
)

@Serializable
data class SetExamAccessRequest(
    val userId: Long,
    val examId: Long,
    val accessType: String,
    val active: Boolean,
)

@Serializable
data class TicketWalletResponse(
    val userId: Long,
    val balance: Long,
    val version: Long,
    val updatedAt: String,
    val userDisplayName: String = "",
    val specialtyName: String = "",
    val status: String = "active",
)

@Serializable
data class TicketAdjustmentRequest(
    val delta: Long,
    val reason: String,
    val referenceType: String? = null,
    val referenceId: Long? = null,
)

@Serializable
data class TicketTransactionResponse(
    val id: Long,
    val userId: Long,
    val type: TicketTransactionType,
    val amount: Long,
    val reason: String,
    val referenceType: String? = null,
    val referenceId: Long? = null,
    val occurredAt: String,
)

@Serializable
enum class TicketTransactionType {
    @SerialName("credito")
    CREDIT,

    @SerialName("debito")
    DEBIT,
}

@Serializable
data class TicketAdjustmentResponse(
    val wallet: TicketWalletResponse,
    val transaction: TicketTransactionResponse,
    val auditId: Long,
)

@Serializable
enum class SupportConversationStatus {
    @SerialName("open")
    OPEN,

    @SerialName("pending")
    PENDING,

    @SerialName("resolved")
    RESOLVED,

    @SerialName("closed")
    CLOSED,
}

@Serializable
data class SupportConversationResponse(
    val id: Long,
    val studentUserId: Long,
    val assignedUserId: Long? = null,
    val status: SupportConversationStatus,
    val title: String,
    val updatedAt: String,
    val studentDisplayName: String = "",
    val assignedDisplayName: String = "",
    val lastMessage: String = "",
)

@Serializable
data class SupportMessageResponse(
    val id: Long,
    val conversationId: Long,
    val senderUserId: Long,
    val body: String,
    val attachmentPath: String? = null,
    val internalNote: Boolean,
    val createdAt: String,
)

@Serializable
data class SendSupportMessageRequest(
    val body: String,
    val attachmentPath: String? = null,
    val internalNote: Boolean = false,
)

@Serializable
enum class ModerationActionType {
    @SerialName("warning")
    WARNING,

    @SerialName("mute")
    MUTE,

    @SerialName("close_conversation")
    CLOSE_CONVERSATION,

    @SerialName("ban")
    BAN,

    @SerialName("internal_note")
    INTERNAL_NOTE,
}

@Serializable
data class CreateModerationActionRequest(
    val targetUserId: Long,
    val conversationId: Long? = null,
    val type: ModerationActionType,
    val reason: String,
    val metadata: JsonObject,
)

@Serializable
data class ModerationActionResponse(
    val id: Long,
    val targetUserId: Long,
    val actorUserId: Long,
    val conversationId: Long? = null,
    val type: ModerationActionType,
    val reason: String,
    val metadata: JsonObject,
    val createdAt: String,
    val targetDisplayName: String = "",
    val actorDisplayName: String = "",
)
