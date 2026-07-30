package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.api.AdminApiHeaders
import com.oposicontrol.admin.core.api.AdminApiRoutes
import com.oposicontrol.admin.core.api.CreateModerationActionRequest
import com.oposicontrol.admin.core.api.CursorPage
import com.oposicontrol.admin.core.api.ExamAccessResponse
import com.oposicontrol.admin.core.api.ModerationActionResponse
import com.oposicontrol.admin.core.api.ModerationActionType
import com.oposicontrol.admin.core.api.SendSupportMessageRequest
import com.oposicontrol.admin.core.api.SetExamAccessRequest
import com.oposicontrol.admin.core.api.SupportConversationResponse
import com.oposicontrol.admin.core.api.SupportMessageResponse
import com.oposicontrol.admin.core.api.TicketAdjustmentRequest
import com.oposicontrol.admin.core.api.TicketAdjustmentResponse
import com.oposicontrol.admin.core.api.TicketTransactionResponse
import com.oposicontrol.admin.core.api.TicketWalletResponse
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.logging.currentOperationContextOrNull
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.BackendHttpException
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class KtorAdminOperationsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : AdminOperationsRepository {
    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = adapter.safeCall {
        client.get("${adminUrl()}/news-types") { backendHeaders() }
            .body<List<NewsTypeDto>>()
            .map { it.toDomain() }
    }

    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = adapter.safeCall {
        client.post("${adminUrl()}/news-types") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(newsType.toDto())
        }.body<NewsTypeDto>().toDomain()
    }

    override suspend fun getExamAccesses(): AppResult<List<ExamAccess>> =
        adapter.safeCall(operation = "exam-access.list") {
            getPage<ExamAccessResponse>(AdminApiRoutes.EXAM_ACCESS)
                .map(ExamAccessResponse::toDomain)
        }

    override suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess> =
        adapter.safeCall(operation = "exam-access.save") {
            val response = postCommand(
                path = AdminApiRoutes.EXAM_ACCESS,
                body = SetExamAccessRequest(
                    userId = requireNotNull(access.userId.toLongOrNull()) {
                        "Selecciona un usuario valido."
                    },
                    examId = requireNotNull(access.examId.toLongOrNull()) {
                        "Selecciona un examen valido."
                    },
                    accessType = access.accessType,
                    active = access.isActive,
                ),
            )
            response.body<ExamAccessResponse>().toDomain()
        }

    override suspend fun getTicketWallets(): AppResult<List<TicketWallet>> =
        adapter.safeCall(operation = "tickets.wallets.list") {
            getPage<TicketWalletResponse>(AdminApiRoutes.WALLETS)
                .map(TicketWalletResponse::toDomain)
        }

    override suspend fun getTicketTransactions(
        userId: String,
    ): AppResult<List<TicketTransaction>> = adapter.safeCall(operation = "tickets.transactions.list") {
        getPage<TicketTransactionResponse>(
            AdminApiRoutes.walletTransactions(userId.requireLongId("usuario")),
        ).map(TicketTransactionResponse::toDomain)
    }

    override suspend fun appendTicketAdjustment(
        userId: String,
        amount: Long,
        reason: String,
    ): AppResult<TicketTransaction> = adapter.safeCall(operation = "tickets.adjust") {
        val response = postCommand(
            path = AdminApiRoutes.walletAdjustment(userId.requireLongId("usuario")),
            body = TicketAdjustmentRequest(
                delta = amount,
                reason = reason,
            ),
        )
        response.body<TicketAdjustmentResponse>().transaction.toDomain()
    }

    override suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>> =
        adapter.safeCall(operation = "support.conversations.list") {
            getPage<SupportConversationResponse>(AdminApiRoutes.SUPPORT_CONVERSATIONS)
                .map(SupportConversationResponse::toDomain)
        }

    override suspend fun getSupportMessages(
        conversationId: String,
    ): AppResult<List<SupportMessage>> = adapter.safeCall(operation = "support.messages.list") {
        getPage<SupportMessageResponse>(
            AdminApiRoutes.supportMessages(conversationId.requireLongId("conversacion")),
        ).map(SupportMessageResponse::toDomain)
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String,
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage> = adapter.safeCall(operation = "support.message.send") {
        val response = postCommand(
            path = AdminApiRoutes.supportMessages(conversationId.requireLongId("conversacion")),
            body = SendSupportMessageRequest(
                body = text,
                internalNote = internal,
            ),
        )
        response.body<SupportMessageResponse>().toDomain()
    }

    override suspend fun getModerationActions(): AppResult<List<ModerationAction>> =
        adapter.safeCall(operation = "moderation.actions.list") {
            getPage<ModerationActionResponse>(AdminApiRoutes.MODERATION_ACTIONS)
                .map(ModerationActionResponse::toDomain)
        }

    override suspend fun appendModerationAction(
        action: ModerationAction,
    ): AppResult<ModerationAction> = adapter.safeCall(operation = "moderation.action.create") {
        val response = postCommand(
            path = AdminApiRoutes.MODERATION_ACTIONS,
            body = CreateModerationActionRequest(
                targetUserId = action.targetUserId.requireLongId("usuario"),
                conversationId = action.conversationId?.toLongOrNull(),
                type = action.actionType.toModerationActionType(),
                reason = action.reason,
                metadata = JsonObject(emptyMap()),
            ),
        )
        response.body<ModerationActionResponse>().toDomain()
    }

    private suspend inline fun <reified T : Any> getPage(path: String): List<T> {
        val response = client.get(canonicalUrl(path)) {
            expectSuccess = false
            backendHeaders()
        }
        response.requireBackendSuccess()
        return response.body<CursorPage<T>>().items
    }

    private suspend inline fun <reified T : Any> postCommand(
        path: String,
        body: T,
    ): HttpResponse {
        val response = client.post(canonicalUrl(path)) {
            expectSuccess = false
            backendHeaders()
            header(
                AdminApiHeaders.IDEMPOTENCY_KEY,
                currentOperationContextOrNull()?.correlationId ?: OperationContext().correlationId,
            )
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.requireBackendSuccess()
        return response
    }

    private fun canonicalUrl(path: String): String = appConfig.baseUrl.trimEnd('/') + path

    private fun HttpResponse.requireBackendSuccess() {
        if (!status.isSuccess()) {
            throw BackendHttpException(status.value)
        }
    }

    private fun adminUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/admin"

    private fun HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() } ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable
private data class NewsTypeDto(
    val id: String,
    val name: String,
    val slug: String,
    val isActive: Boolean,
)

private fun NewsTypeDto.toDomain() = NewsType(id = id, name = name, slug = slug, isActive = isActive)

private fun NewsType.toDto() = NewsTypeDto(id = id, name = name, slug = slug, isActive = isActive)

private fun ExamAccessResponse.toDomain() = ExamAccess(
    id = id.toString(),
    userName = userDisplayName,
    examTitle = examTitle,
    accessType = accessType,
    isActive = active,
    userId = userId.toString(),
    examId = examId.toString(),
    version = version,
    updatedAt = updatedAt,
)

private fun TicketWalletResponse.toDomain() = TicketWallet(
    userId = userId.toString(),
    userName = userDisplayName,
    specialty = specialtyName,
    balance = balance,
    status = status,
)

private fun TicketTransactionResponse.toDomain() = TicketTransaction(
    id = id.toString(),
    userId = userId.toString(),
    type = type.name.lowercase(),
    amount = amount,
    reason = reason,
    occurredAt = occurredAt,
)

private fun SupportConversationResponse.toDomain() = SupportConversationSummary(
    id = id.toString(),
    userName = studentDisplayName,
    subject = title,
    status = status.name.lowercase(),
    assignedTo = assignedDisplayName,
    lastMessage = lastMessage,
)

private fun SupportMessageResponse.toDomain() = SupportMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    authorName = senderUserId.toString(),
    text = body,
    timestamp = createdAt,
    internal = internalNote,
    attachmentLabel = attachmentPath,
)

private fun ModerationActionResponse.toDomain() = ModerationAction(
    id = id.toString(),
    actionType = type.name.lowercase(),
    targetUser = targetDisplayName,
    executor = actorDisplayName,
    reason = reason,
    createdAt = createdAt,
    targetUserId = targetUserId.toString(),
    conversationId = conversationId?.toString(),
)

private fun String.requireLongId(label: String): Long =
    toLongOrNull() ?: throw IllegalArgumentException("Selecciona un $label valido.")

private fun String.toModerationActionType(): ModerationActionType = when (trim().lowercase()) {
    "warning", "advertencia" -> ModerationActionType.WARNING
    "mute", "silencio" -> ModerationActionType.MUTE
    "close_conversation", "cerrar_conversacion" -> ModerationActionType.CLOSE_CONVERSATION
    "ban", "bloqueo" -> ModerationActionType.BAN
    "internal_note", "nota_interna" -> ModerationActionType.INTERNAL_NOTE
    else -> throw IllegalArgumentException("Selecciona un tipo de moderacion valido.")
}
