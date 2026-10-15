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
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
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
    /**
     * D4-10: todas las páginas (activos e inactivos). Un fallo en cualquier página es un fallo, nunca una lista parcial
     * o vacía que se lea como «no hay tipos».
     */
    override suspend fun getNewsTypes(): AppResult<List<NewsType>> = adapter.safeCall {
        val all = mutableListOf<NewsType>()
        var offset = 0
        while (true) {
            val page = client.get("${adminV1Url()}/tipos-noticia") {
                backendHeaders()
                parameter("includeInactive", true)
                parameter("limit", NEWS_TYPES_PAGE_SIZE)
                parameter("offset", offset)
            }.checkedBody<TipoNoticiaPageDto>("/api/v1/admin/tipos-noticia")
            all += page.items.map { it.toDomain() }
            offset += page.items.size
            val total = page.total
            val more = if (total != null) offset < total else page.items.size >= NEWS_TYPES_PAGE_SIZE
            if (!more || page.items.isEmpty() || offset >= NEWS_TYPES_MAX_ROWS) break
        }
        all
    }

    /** D4-11: alta o edición por `codigo` (clave natural). Sin código no hay a qué tipo escribir. */
    override suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType> = adapter.safeCall {
        val code = newsType.code.trim()
        require(code.isNotEmpty()) { "El tipo de noticia necesita código." }
        client.put("${adminV1Url()}/tipos-noticia/$code") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(newsType.toPutDto())
        }.checkedBody<TipoNoticiaDto>("/api/v1/admin/tipos-noticia/$code").toDomain()
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
            // El `assignee` del llamador no viaja: el backend nunca lo aplicó (ver el KDoc de la interfaz).
            setBody(SupportReplyDto(text = text, assignee = "", internal = internal))
        }.checkedBody<SupportMessageDto>("/api/v1/admin/soporte/conversaciones/$conversationId/mensajes").toDomain()
    }

    override suspend fun getModerationActions(): AppResult<List<ModerationAction>> = adapter.safeCall {
        client.get("${adminV1Url()}/moderacion/actions") { backendHeaders() }
            .checkedBody<List<ModerationActionDto>>("/api/v1/admin/moderacion/actions")
            .map { it.toDomain() }
    }

    /** RC-01: el cuerpo canónico (`targetUserId` uuid + `actionType` + `reason`); el ejecutor sale del JWT. Responde 201 sin fila. */
    override suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction> = adapter.safeCall {
        client.post("${adminV1Url()}/moderacion/actions") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(ModerationCommandDto(targetUserId = action.targetUser.trim(), actionType = action.actionType, reason = action.reason))
        }.requireSuccess("/api/v1/admin/moderacion/actions")
        action
    }

    private fun adminV1Url(): String = "${appConfig.baseUrl.trimEnd('/')}/api/v1/admin"

    private fun HttpRequestBuilder.backendHeaders() {
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }

    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        requireSuccess(endpoint)
        return body()
    }

    private companion object {
        /** El tope del servidor (TYPES_MAX_LIMIT): menos peticiones, y la misma página siempre. */
        const val NEWS_TYPES_PAGE_SIZE = 200

        /** Techo de seguridad del bucle de páginas: un catálogo editorial no llega ni de lejos. */
        const val NEWS_TYPES_MAX_ROWS = 2_000
    }
}

/** `TipoNoticiaWire` del backend (NewsService.kt). */
@Serializable
private data class TipoNoticiaDto(
    val id: String,
    val codigo: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val activo: Boolean,
    val posicion: Int,
)

/** `OffsetPage<TipoNoticiaWire>` (AdminPagination.kt). */
@Serializable
private data class TipoNoticiaPageDto(
    val items: List<TipoNoticiaDto>,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

/** Cuerpo exacto de `NewsCommands.parseTipo`: campos desconocidos son 400, así que no viaja nada más. */
@Serializable
private data class TipoNoticiaPutDto(
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val activo: Boolean,
    val posicion: Int,
)

/** `WarnUserRequest` del backend (RC-01). */
@Serializable
private data class ModerationCommandDto(
    val targetUserId: String,
    val actionType: String,
    val reason: String,
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
    val text: String,
    // Los backends ya desplegados declaran `assignee` OBLIGATORIO en `AdminSupportReplyDto` (y lo ignoran): viaja siempre
    // vacío para no convertir cada respuesta en un 400 de deserialización.
    val assignee: String,
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

private fun TipoNoticiaDto.toDomain() = NewsType(
    id = id,
    name = nombre,
    slug = slug,
    isActive = activo,
    code = codigo,
    description = descripcion,
    position = posicion,
)

private fun NewsType.toPutDto() = TipoNoticiaPutDto(
    nombre = name.trim(),
    slug = slug.trim(),
    descripcion = description,
    activo = isActive,
    posicion = position,
)

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
