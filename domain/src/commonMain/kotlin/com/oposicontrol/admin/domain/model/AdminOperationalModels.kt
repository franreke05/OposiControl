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
    /** RC-06: ids canonicos (uuid) que devuelve el backend; null = fila sin ids (se cae al nombre, que el servidor rechaza si es ambiguo). */
    val userId: String? = null,
    val examId: String? = null,
)

data class TicketWallet(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Double,
    val status: String,
)

data class TicketTransaction(
    val id: String,
    val userId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val occurredAt: String,
    /** Parte auditable del asiento. `null` = el backend no lo informa (asientos anteriores al ledger canónico). */
    val balanceBefore: Int? = null,
    val balanceAfter: Int? = null,
    /** Qué originó el movimiento («admin», una compra, una recompensa…), tal como lo guarda el ledger. */
    val originType: String? = null,
    val originId: String? = null,
)

/**
 * Resumen agregado de la economía de OposiTickets. Cada cifra es `null` cuando el backend no ha podido contarla:
 * un cero aquí significaría «no hay», que es otra afirmación.
 */
data class TicketsOverview(
    val wallets: Int? = null,
    val movements: Int? = null,
    val credits: Int? = null,
    val debits: Int? = null,
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
)
