package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.CreateModerationActionRequest
import com.oposicontrol.admin.core.api.CursorPage
import com.oposicontrol.admin.core.api.ExamAccessResponse
import com.oposicontrol.admin.core.api.ModerationActionResponse
import com.oposicontrol.admin.core.api.ModerationActionType
import com.oposicontrol.admin.core.api.SendSupportMessageRequest
import com.oposicontrol.admin.core.api.SetExamAccessRequest
import com.oposicontrol.admin.core.api.SupportConversationResponse
import com.oposicontrol.admin.core.api.SupportConversationStatus
import com.oposicontrol.admin.core.api.SupportMessageResponse
import com.oposicontrol.admin.core.api.TicketAdjustmentRequest
import com.oposicontrol.admin.core.api.TicketAdjustmentResponse
import com.oposicontrol.admin.core.api.TicketTransactionResponse
import com.oposicontrol.admin.core.api.TicketTransactionType
import com.oposicontrol.admin.core.api.TicketWalletResponse
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class OperationalAdminGateway(
    private val supabaseHttp: SupabaseHttp,
) {
    suspend fun listExamAccess(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<ExamAccessResponse> {
        val users = users(actor)
        val exams = rows<OperationalExamRow>(
            "/rest/v1/examen?select=id,titulo&deleted_at=is.null&limit=$REFERENCE_LIMIT",
            actor,
        ).associate { it.id to it.title }
        val records = rows<OperationalExamAccessRow>(
            "/rest/v1/acceso_examen_usuario?select=*${operationalCursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        return records.toOperationalPage(limit, OperationalExamAccessRow::id) { row ->
            ExamAccessResponse(
                id = row.id,
                userId = row.userId,
                examId = row.examId,
                accessType = row.accessType,
                active = row.active,
                version = row.version,
                updatedAt = row.updatedAt,
                userDisplayName = users[row.userId]?.displayName.orEmpty(),
                examTitle = exams[row.examId].orEmpty(),
            )
        }
    }

    suspend fun setExamAccess(
        request: SetExamAccessRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): ExamAccessResponse {
        require(request.userId in 1..Int.MAX_VALUE) { "Usuario no válido." }
        require(request.examId in 1..Int.MAX_VALUE) { "Examen no válido." }
        require(request.accessType.isNotBlank()) { "Tipo de acceso requerido." }
        return supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_set_exam_access",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_user_id", request.userId)
                put("p_exam_id", request.examId)
                put("p_access_type", request.accessType.trim())
                put("p_active", request.active)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let(supabaseHttp::decode)
    }

    suspend fun listWallets(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<TicketWalletResponse> {
        val users = users(actor)
        val specialties = rows<OperationalSpecialtyRow>(
            "/rest/v1/especialidad?select=id,nombre&deleted_at=is.null&limit=$REFERENCE_LIMIT",
            actor,
        ).associate { it.id to it.name }
        val wallets = rows<JsonObject>(
            "/rest/v1/cartera_tickets?select=*${operationalCursorFilter(cursor, "usuario_id")}&order=usuario_id.desc&limit=${limit + 1}",
            actor,
        )
        return wallets.toOperationalPage(limit, { it.requiredLong("usuario_id") }) { row ->
            val userId = row.requiredLong("usuario_id")
            val user = users[userId]
            TicketWalletResponse(
                userId = userId,
                balance = row.requiredLong("saldo"),
                version = row.requiredLong("version"),
                updatedAt = row.text("updated_at").orEmpty(),
                userDisplayName = user?.displayName.orEmpty(),
                specialtyName = user?.specialtyId?.let(specialties::get).orEmpty(),
                status = if (user?.active == false) "inactive" else "active",
            )
        }
    }

    suspend fun listTicketTransactions(
        userId: Long,
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<TicketTransactionResponse> {
        val items = rows<JsonObject>(
            "/rest/v1/transaccion_ticket?select=*&usuario_id=eq.$userId${operationalCursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        return items.toOperationalPage(limit, { it.requiredLong("id") }) { row ->
            TicketTransactionResponse(
                id = row.requiredLong("id"),
                userId = row.requiredLong("usuario_id"),
                type = if (row.text("tipo") == "debito") TicketTransactionType.DEBIT else TicketTransactionType.CREDIT,
                amount = row.requiredLong("monto"),
                reason = row.text("razon").orEmpty(),
                referenceType = row.text("referencia_tipo"),
                referenceId = row.long("referencia_id"),
                occurredAt = row.text("created_at").orEmpty(),
            )
        }
    }

    suspend fun adjustTickets(
        userId: Long,
        request: TicketAdjustmentRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): TicketAdjustmentResponse {
        require(userId in 1..Int.MAX_VALUE) { "Usuario no válido." }
        require(request.delta != 0L && request.delta in -1_000_000_000L..1_000_000_000L) { "Ajuste no válido." }
        require(request.reason.trim().length >= 3) { "El ajuste necesita un motivo." }
        return supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_adjust_tickets",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_user_id", userId)
                put("p_delta", request.delta)
                put("p_reason", request.reason.trim())
                putNullableString("p_reference_type", request.referenceType)
                putNullableLong("p_reference_id", request.referenceId)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let(supabaseHttp::decode)
    }

    suspend fun listSupportConversations(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<SupportConversationResponse> {
        val users = users(actor)
        val messages = rows<OperationalSupportMessageRow>(
            "/rest/v1/support_message?select=*&order=id.desc&limit=$REFERENCE_LIMIT",
            actor,
        ).groupBy(OperationalSupportMessageRow::conversationId)
        val conversations = rows<OperationalSupportConversationRow>(
            "/rest/v1/support_conversation?select=*${operationalCursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        return conversations.toOperationalPage(limit, OperationalSupportConversationRow::id) { row ->
            SupportConversationResponse(
                id = row.id,
                studentUserId = row.studentUserId,
                assignedUserId = row.assignedUserId,
                status = row.status.toSupportStatus(),
                title = row.title.orEmpty(),
                updatedAt = row.updatedAt,
                studentDisplayName = users[row.studentUserId]?.displayName.orEmpty(),
                assignedDisplayName = row.assignedUserId?.let(users::get)?.displayName.orEmpty(),
                lastMessage = messages[row.id].orEmpty().maxByOrNull(OperationalSupportMessageRow::id)?.body.orEmpty(),
            )
        }
    }

    suspend fun listSupportMessages(
        conversationId: Long,
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<SupportMessageResponse> = rows<OperationalSupportMessageRow>(
            "/rest/v1/support_message?select=*&conversation_id=eq.$conversationId${operationalCursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        ).toOperationalPage(limit, OperationalSupportMessageRow::id, OperationalSupportMessageRow::toResponse)

    suspend fun sendSupportMessage(
        conversationId: Long,
        request: SendSupportMessageRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): SupportMessageResponse {
        require(request.body.isNotBlank() || !request.attachmentPath.isNullOrBlank()) {
            "El mensaje necesita texto o adjunto."
        }
        return supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_send_support_message",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_conversation_id", conversationId)
                put("p_body", request.body)
                putNullableString("p_attachment_path", request.attachmentPath)
                put("p_internal_note", request.internalNote)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let(supabaseHttp::decode)
    }

    suspend fun listModerationActions(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<ModerationActionResponse> {
        val users = users(actor)
        val actions = rows<OperationalModerationRow>(
            "/rest/v1/moderation_action?select=*${operationalCursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        return actions.toOperationalPage(limit, OperationalModerationRow::id) { row ->
            row.toResponse(
                targetDisplayName = users[row.targetUserId]?.displayName.orEmpty(),
                actorDisplayName = users[row.actorUserId]?.displayName.orEmpty(),
            )
        }
    }

    suspend fun createModerationAction(
        request: CreateModerationActionRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): ModerationActionResponse {
        require(request.targetUserId in 1..Int.MAX_VALUE) { "Usuario objetivo no válido." }
        require(request.reason.trim().length >= 3) { "La moderación necesita un motivo." }
        return supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_create_moderation_action",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_target_user_id", request.targetUserId)
                putNullableLong("p_conversation_id", request.conversationId)
                put("p_action_type", request.type.databaseValue())
                put("p_reason", request.reason.trim())
                put("p_metadata", request.metadata)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let(supabaseHttp::decode)
    }

    private suspend fun users(actor: AdminPrincipal): Map<Long, OperationalUserRow> =
        rows<OperationalUserRow>(
            "/rest/v1/usuario?select=id,nombre,email,especialidad_id,activo&deleted_at=is.null&limit=$REFERENCE_LIMIT",
            actor,
        ).associateBy(OperationalUserRow::id)

    private suspend inline fun <reified T> rows(path: String, actor: AdminPrincipal): List<T> =
        supabaseHttp.actorRequest(
            method = HttpMethod.Get,
            path = path,
            actorAccessToken = actor.accessToken,
        ).body.let(supabaseHttp::decodeList)

    companion object {
        private const val REFERENCE_LIMIT = 100
    }
}

@Serializable
private data class OperationalUserRow(
    val id: Long,
    val nombre: String? = null,
    val email: String? = null,
    @SerialName("especialidad_id") val specialtyId: Long? = null,
    val activo: Boolean = true,
) {
    val displayName: String
        get() = nombre.orEmpty().ifBlank { email.orEmpty() }
    val active: Boolean
        get() = activo
}

@Serializable
private data class OperationalSpecialtyRow(
    val id: Long,
    @SerialName("nombre") val name: String,
)

@Serializable
private data class OperationalExamRow(
    val id: Long,
    @SerialName("titulo") val title: String,
)

@Serializable
private data class OperationalExamAccessRow(
    val id: Long,
    @SerialName("usuario_id") val userId: Long,
    @SerialName("examen_id") val examId: Long,
    @SerialName("tipo_acceso") val accessType: String,
    @SerialName("activo") val active: Boolean,
    val version: Long = 1,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class OperationalSupportConversationRow(
    val id: Long,
    @SerialName("student_usuario_id") val studentUserId: Long,
    @SerialName("assigned_usuario_id") val assignedUserId: Long? = null,
    @SerialName("estado") val status: String,
    @SerialName("titulo") val title: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
private data class OperationalSupportMessageRow(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("sender_usuario_id") val senderUserId: Long,
    val body: String? = null,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("internal_note") val internalNote: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
) {
    fun toResponse() = SupportMessageResponse(
        id = id,
        conversationId = conversationId,
        senderUserId = senderUserId,
        body = body.orEmpty(),
        attachmentPath = attachmentPath,
        internalNote = internalNote,
        createdAt = createdAt,
    )
}

@Serializable
private data class OperationalModerationRow(
    val id: Long,
    @SerialName("target_usuario_id") val targetUserId: Long,
    @SerialName("actor_usuario_id") val actorUserId: Long,
    @SerialName("conversation_id") val conversationId: Long? = null,
    @SerialName("action_type") val actionType: String,
    val reason: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
) {
    fun toResponse(
        targetDisplayName: String,
        actorDisplayName: String,
    ) = ModerationActionResponse(
        id = id,
        targetUserId = targetUserId,
        actorUserId = actorUserId,
        conversationId = conversationId,
        type = actionType.toModerationType(),
        reason = reason,
        metadata = metadata,
        createdAt = createdAt,
        targetDisplayName = targetDisplayName,
        actorDisplayName = actorDisplayName,
    )
}

private fun operationalCursorFilter(cursor: Long?, column: String = "id"): String =
    cursor?.let { "&$column=lt.$it" }.orEmpty()

private inline fun <T, R> List<T>.toOperationalPage(
    limit: Int,
    id: (T) -> Long,
    transform: (T) -> R,
): CursorPage<R> {
    val visible = take(limit)
    return CursorPage(
        items = visible.map(transform),
        nextCursor = if (size > limit) visible.lastOrNull()?.let(id)?.toString() else null,
    )
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull
        ?: text(key)?.toBigDecimalOrNull()?.toLong()

private fun JsonObject.requiredLong(key: String): Long =
    long(key) ?: throw UpstreamProblem("Supabase devolvió un valor numérico no válido.")

private fun String.toSupportStatus(): SupportConversationStatus = when (this) {
    "pending" -> SupportConversationStatus.PENDING
    "resolved" -> SupportConversationStatus.RESOLVED
    "closed" -> SupportConversationStatus.CLOSED
    else -> SupportConversationStatus.OPEN
}

private fun String.toModerationType(): ModerationActionType = when (this) {
    "mute" -> ModerationActionType.MUTE
    "close_conversation" -> ModerationActionType.CLOSE_CONVERSATION
    "ban" -> ModerationActionType.BAN
    "internal_note" -> ModerationActionType.INTERNAL_NOTE
    else -> ModerationActionType.WARNING
}

private fun ModerationActionType.databaseValue(): String = when (this) {
    ModerationActionType.WARNING -> "warning"
    ModerationActionType.MUTE -> "mute"
    ModerationActionType.CLOSE_CONVERSATION -> "close_conversation"
    ModerationActionType.BAN -> "ban"
    ModerationActionType.INTERNAL_NOTE -> "internal_note"
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(name: String, value: String?) {
    if (value == null) put(name, kotlinx.serialization.json.JsonNull) else put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(name: String, value: Long?) {
    if (value == null) put(name, kotlinx.serialization.json.JsonNull) else put(name, value)
}
