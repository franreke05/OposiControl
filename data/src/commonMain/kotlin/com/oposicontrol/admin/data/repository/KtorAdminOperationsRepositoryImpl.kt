package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.model.TicketsOverview
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KtorAdminOperationsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : AdminOperationsRepository {
    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = adapter.safeCall {
        client.get("${adminUrl()}/news-types") { backendHeaders() }
            .checkedBody<List<NewsTypeDto>>("/admin/news-types")
            .map { it.toDomain() }
    }

    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = adapter.safeCall {
        client.post("${adminUrl()}/news-types") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(newsType.toDto())
        }.checkedBody<NewsTypeDto>("/admin/news-types").toDomain()
    }

    override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> = adapter.safeCall {
        client.get("${adminV1Url()}/accesos") { backendHeaders() }
            .checkedBody<List<ExamAccessDto>>("/api/v1/admin/accesos")
            .map { it.toDomain() }
    }

    override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> = adapter.safeCall {
        client.post("${adminV1Url()}/accesos") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(access.toDto())
        }.checkedBody<ExamAccessDto>("/api/v1/admin/accesos").toDomain()
    }

    override suspend fun revokeExamAccess(accessId: String): AppResult<ExamAccess> = adapter.safeCall {
        client.post("${adminV1Url()}/accesos/$accessId/revocar") { backendHeaders() }
            .checkedBody<ExamAccessDto>("/api/v1/admin/accesos/$accessId/revocar")
            .toDomain()
    }

    override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> = adapter.safeCall {
        client.get("${adminV1Url()}/tickets") { backendHeaders() }
            .checkedBody<List<TicketWalletDto>>("/api/v1/admin/tickets")
            .map { it.toDomain() }
    }

    override suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>> = adapter.safeCall {
        client.get("${adminV1Url()}/tickets/$userId/transacciones") { backendHeaders() }
            .checkedBody<List<TicketTransactionDto>>("/api/v1/admin/tickets/$userId/transacciones")
            .map { it.toDomain() }
    }

    override suspend fun appendTicketAdjustment(
        userId: String,
        amount: Int,
        reason: String,
        idempotencyKey: String,
    ): AppResult<TicketTransaction> = adapter.safeCall {
        client.post("${adminV1Url()}/tickets/$userId/transacciones") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            // El backend exige la clave (422 WALLET_IDEMPOTENCY_KEY_REQUIRED sin ella): sin clave, un reintento tras un
            // timeout aplicaría el ajuste dos veces.
            setBody(TicketAdjustmentDto(amount = amount.toDouble(), reason = reason, idempotencyKey = idempotencyKey))
        }.checkedBody<TicketTransactionDto>("/api/v1/admin/tickets/$userId/transacciones").toDomain()
    }

    override suspend fun getTicketsOverview(): AppResult<TicketsOverview> = adapter.safeCall {
        client.get("${adminV1Url()}/tickets/resumen") { backendHeaders() }
            .checkedBody<TicketsOverviewResponseDto>("/api/v1/admin/tickets/resumen").data.toDomain()
    }

    override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> = adapter.safeCall {
        client.get("${adminV1Url()}/soporte/conversaciones") { backendHeaders() }
            .checkedBody<List<SupportConversationDto>>("/api/v1/admin/soporte/conversaciones")
            .map { it.toDomain() }
    }

    override suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>> = adapter.safeCall {
        client.get("${adminV1Url()}/soporte/conversaciones/$conversationId/mensajes") { backendHeaders() }
            .checkedBody<List<SupportMessageDto>>("/api/v1/admin/soporte/conversaciones/$conversationId/mensajes")
            .map { it.toDomain() }
    }

    override suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String,
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage> = adapter.safeCall {
        client.post("${adminV1Url()}/soporte/conversaciones/$conversationId/mensajes") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(SupportReplyDto(assignee = assignee, text = text, internal = internal))
        }.checkedBody<SupportMessageDto>("/api/v1/admin/soporte/conversaciones/$conversationId/mensajes").toDomain()
    }

    override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = adapter.safeCall {
        client.get("${adminV1Url()}/moderacion/actions") { backendHeaders() }
            .checkedBody<List<ModerationActionDto>>("/api/v1/admin/moderacion/actions")
            .map { it.toDomain() }
    }

    override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = adapter.safeCall {
        client.post("${adminUrl()}/moderation/actions") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(action.toDto())
        }.checkedBody<ModerationActionDto>("/admin/moderation/actions").toDomain()
    }

    private fun adminUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/admin"

    private fun adminV1Url(): String = "${appConfig.baseUrl.trimEnd('/')}/api/v1/admin"

    private fun HttpRequestBuilder.backendHeaders() {
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }

    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
        return body()
    }
}

@Serializable
private data class NewsTypeDto(
    val id: String,
    val name: String,
    val slug: String,
    val isActive: Boolean,
)

@Serializable
private data class ExamAccessDto(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val isActive: Boolean,
    val userId: String? = null,
    val examId: String? = null,
    val grantedAt: String? = null,
    val validUntil: String? = null,
)

@Serializable
private data class TicketWalletDto(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Double,
    val status: String,
)

@Serializable
private data class TicketTransactionDto(
    val id: String,
    val userId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val occurredAt: String,
    val balanceBefore: Int? = null,
    val balanceAfter: Int? = null,
    val originType: String? = null,
    val originId: String? = null,
)

@Serializable
private data class TicketsOverviewResponseDto(val data: TicketsOverviewDto = TicketsOverviewDto())

@Serializable
private data class TicketsOverviewDto(
    val carteras: Int? = null,
    val movimientos: Int? = null,
    val movimientosCredito: Int? = null,
    val movimientosDebito: Int? = null,
)

@Serializable
private data class TicketAdjustmentDto(
    val amount: Double,
    val reason: String,
    val idempotencyKey: String,
)

@Serializable
private data class SupportConversationDto(
    val id: String,
    val userName: String,
    val subject: String,
    val status: String,
    val assignedTo: String,
    val lastMessage: String,
)

@Serializable
private data class SupportMessageDto(
    val id: String,
    val conversationId: String,
    val authorName: String,
    val text: String,
    val timestamp: String,
    val internal: Boolean,
    val attachmentLabel: String? = null,
)

@Serializable
private data class SupportReplyDto(
    val assignee: String,
    val text: String,
    val internal: Boolean,
)

@Serializable
private data class ModerationActionDto(
    val id: String,
    val actionType: String,
    val targetUser: String,
    val executor: String,
    val reason: String,
    val createdAt: String,
)

private fun NewsTypeDto.toDomain() = NewsType(id = id, name = name, slug = slug, isActive = isActive)

private fun NewsType.toDto() = NewsTypeDto(id = id, name = name, slug = slug, isActive = isActive)

private fun ExamAccessDto.toDomain() = ExamAccess(
    id = id,
    userName = userName,
    examTitle = examTitle,
    accessType = accessType,
    isActive = isActive,
    userId = userId,
    examId = examId,
    grantedAt = grantedAt,
    validUntil = validUntil,
)

private fun ExamAccess.toDto() = ExamAccessDto(
    id = id,
    userName = userName,
    examTitle = examTitle,
    accessType = accessType,
    isActive = isActive,
    userId = userId,
    examId = examId,
)

private fun TicketWalletDto.toDomain() = TicketWallet(
    userId = userId,
    userName = userName,
    specialty = specialty,
    balance = balance,
    status = status,
)

private fun TicketTransactionDto.toDomain() = TicketTransaction(
    id = id,
    userId = userId,
    type = type,
    amount = amount,
    reason = reason,
    occurredAt = occurredAt,
    balanceBefore = balanceBefore,
    balanceAfter = balanceAfter,
    originType = originType,
    originId = originId,
)

private fun TicketsOverviewDto.toDomain() = TicketsOverview(
    wallets = carteras,
    movements = movimientos,
    credits = movimientosCredito,
    debits = movimientosDebito,
)

private fun SupportConversationDto.toDomain() = SupportConversationSummary(
    id = id,
    userName = userName,
    subject = subject,
    status = status,
    assignedTo = assignedTo,
    lastMessage = lastMessage,
)

private fun SupportMessageDto.toDomain() = SupportMessage(
    id = id,
    conversationId = conversationId,
    authorName = authorName,
    text = text,
    timestamp = timestamp,
    internal = internal,
    attachmentLabel = attachmentLabel,
)

private fun ModerationActionDto.toDomain() = ModerationAction(
    id = id,
    actionType = actionType,
    targetUser = targetUser,
    executor = executor,
    reason = reason,
    createdAt = createdAt,
)

private fun ModerationAction.toDto() = ModerationActionDto(
    id = id,
    actionType = actionType,
    targetUser = targetUser,
    executor = executor,
    reason = reason,
    createdAt = createdAt,
)
