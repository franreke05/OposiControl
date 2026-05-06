package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant

fun Route.adminRoutes(gateway: SupabaseAdminGateway) {
    route("/admin") {
        get("/users") { call.respond(gateway.listUsers()) }
        get("/users/{userId}") { call.respond(gateway.getUser(call.requiredPath("userId"))) }
        post("/users") { call.respond(HttpStatusCode.Created, gateway.saveUser(call.receive())) }
        post("/users/{userId}") { call.respond(HttpStatusCode.Created, gateway.saveUser(call.receive())) }
        put("/users/{userId}") { call.respond(gateway.saveUser(call.receive())) }
        delete("/users/{userId}") {
            gateway.deleteUser(call.requiredPath("userId"))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/roles") { call.respond(gateway.listRoles()) }
        post("/roles") { call.respond(HttpStatusCode.Created, gateway.saveRole(call.receive())) }
        post("/roles/{roleId}") { call.respond(HttpStatusCode.Created, gateway.saveRole(call.receive())) }
        put("/roles/{roleId}") { call.respond(gateway.saveRole(call.receive())) }
        get("/settings") { call.respond(gateway.settings()) }
        post("/settings") { call.respond(call.receive<AdminSettingsDto>()) }
        put("/settings") { call.respond(call.receive<AdminSettingsDto>()) }
        get("/audit") { call.respond(gateway.listAudit()) }
        post("/audit") {
            gateway.appendAudit(call.receive())
            call.respond(HttpStatusCode.Created)
        }
        get("/news-types") { call.respond(gateway.listNewsTypes()) }
        post("/news-types") { call.respond(HttpStatusCode.Created, gateway.saveNewsType(call.receive())) }
        post("/news-types/{newsTypeId}") { call.respond(HttpStatusCode.Created, gateway.saveNewsType(call.receive())) }
        put("/news-types/{newsTypeId}") { call.respond(gateway.saveNewsType(call.receive())) }
        get("/exam-access") { call.respond(gateway.listExamAccess()) }
        post("/exam-access") { call.respond(HttpStatusCode.Created, gateway.saveExamAccess(call.receive())) }
        post("/exam-access/{accessId}") { call.respond(HttpStatusCode.Created, gateway.saveExamAccess(call.receive())) }
        put("/exam-access/{accessId}") { call.respond(gateway.saveExamAccess(call.receive())) }
        get("/ticket-wallets") { call.respond(gateway.listTicketWallets()) }
        get("/ticket-wallets/{userId}/transactions") {
            call.respond(gateway.listTicketTransactions(call.requiredPath("userId")))
        }
        post("/ticket-wallets/{userId}/transactions") {
            call.respond(HttpStatusCode.Created, gateway.appendTicketAdjustment(call.requiredPath("userId"), call.receive()))
        }
        get("/support/conversations") { call.respond(gateway.listSupportConversations()) }
        get("/support/conversations/{conversationId}/messages") {
            call.respond(gateway.listSupportMessages(call.requiredPath("conversationId")))
        }
        post("/support/conversations/{conversationId}/messages") {
            call.respond(HttpStatusCode.Created, gateway.sendSupportMessage(call.requiredPath("conversationId"), call.receive()))
        }
        get("/moderation/actions") { call.respond(gateway.listModerationActions()) }
        post("/moderation/actions") {
            call.respond(HttpStatusCode.Created, gateway.appendModerationAction(call.receive()))
        }
    }
    get("/dashboard/metrics") {
        call.respond(gateway.dashboardMetrics())
    }
}

class SupabaseAdminGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun listUsers(): List<AdminUserDto> {
        val rolesByUser = roleAssignments()
        return usuarios().map { user ->
            user.toDto(rolesByUser[user.id].orEmpty())
        }
    }

    fun getUser(userId: String): AdminUserDto {
        return listUsers().firstOrNull { it.id == userId }
            ?: throw IllegalArgumentException("Usuario no encontrado.")
    }

    fun saveUser(input: AdminUserDto): AdminUserDto {
        require(input.email.isNotBlank()) { "El usuario necesita email." }
        val saved = request(
            method = "POST",
            path = "/rest/v1/usuario?on_conflict=id",
            body = json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<UsuarioRow>().first()
        replaceUserRoles(saved.id ?: error("Supabase no devolvio id de usuario."), input.roleIds)
        appendAudit(
            AdminAuditEventDto(
                id = "",
                actorName = "backend-admin",
                action = "USER_UPSERT",
                target = saved.id.toString(),
                occurredAt = Instant.now().toString(),
                metadata = mapOf("email" to input.email),
            ),
        )
        return saved.toDto(input.roleIds)
    }

    fun deleteUser(userId: String) {
        request(
            method = "PATCH",
            path = "/rest/v1/usuario?id=eq.${userId.toIntId()}",
            body = """{"deleted_at":"${Instant.now()}"}""",
            prefer = "return=minimal",
        )
    }

    fun listRoles(): List<AdminRoleDto> {
        return request("GET", "/rest/v1/app_role?select=id,name,description&order=name.asc", null)
            .decodeList<RoleRow>()
            .map { it.toDto() }
    }

    fun saveRole(input: AdminRoleDto): AdminRoleDto {
        require(input.name.isNotBlank()) { "El rol necesita nombre." }
        return request(
            method = "POST",
            path = "/rest/v1/app_role?on_conflict=id",
            body = json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RoleRow>().first().toDto()
    }

    fun settings(): AdminSettingsDto {
        return AdminSettingsDto(
            displayName = System.getenv("OPOSICONTROL_ADMIN_DISPLAY_NAME").orEmpty().ifBlank { "Administrador" },
            email = System.getenv("OPOSICONTROL_ADMIN_EMAIL").orEmpty(),
            receiveReleaseNotes = false,
            defaultEnvironment = System.getenv("APP_ENV").orEmpty().ifBlank { "dev" },
            preferFakeData = false,
            enableVerboseLogs = System.getenv("ENABLE_VERBOSE_NETWORK_LOGS")?.toBooleanStrictOrNull() ?: false,
        )
    }

    fun listAudit(): List<AdminAuditEventDto> {
        val usersById = usuarios().associateBy { it.id }
        return request("GET", "/rest/v1/audit_log?select=*&order=created_at.desc&limit=200", null)
            .decodeList<AuditRow>()
            .map { row ->
                AdminAuditEventDto(
                    id = row.id?.toString().orEmpty(),
                    actorName = usersById[row.actorUsuarioId]?.displayName().orEmpty().ifBlank {
                        row.actorAuthUserId.orEmpty().ifBlank { "Sistema" }
                    },
                    action = row.action,
                    target = listOf(row.resource, row.resourceId.orEmpty()).filter(String::isNotBlank).joinToString(":"),
                    occurredAt = row.createdAt,
                    metadata = row.metadata ?: emptyMap(),
                )
            }
    }

    fun appendAudit(input: AdminAuditEventDto) {
        request(
            method = "POST",
            path = "/rest/v1/audit_log",
            body = json.encodeToString(listOf(input.toRow())),
            prefer = "return=minimal",
        )
    }

    fun listNewsTypes(): List<AdminNewsTypeDto> {
        return request("GET", "/rest/v1/tipo_noticia?select=id,nombre,slug,activo&deleted_at=is.null&order=nombre.asc", null)
            .decodeList<NewsTypeRow>()
            .map { it.toDto() }
    }

    fun saveNewsType(input: AdminNewsTypeDto): AdminNewsTypeDto {
        require(input.name.isNotBlank()) { "El tipo de noticia necesita nombre." }
        return request(
            method = "POST",
            path = "/rest/v1/tipo_noticia?on_conflict=id",
            body = json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<NewsTypeRow>().first().toDto()
    }

    fun listExamAccess(): List<AdminExamAccessDto> {
        val usersById = usuarios().associateBy { it.id }
        val examsById = examenes().associateBy { it.id }
        return request("GET", "/rest/v1/acceso_examen_usuario?select=*&order=created_at.desc", null)
            .decodeList<ExamAccessRow>()
            .map { row ->
                AdminExamAccessDto(
                    id = row.id?.toString().orEmpty(),
                    userName = usersById[row.usuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.usuarioId}" },
                    examTitle = examsById[row.examenId]?.titulo.orEmpty().ifBlank { "Examen ${row.examenId}" },
                    accessType = row.tipoAcceso,
                    isActive = row.activo,
                )
            }
    }

    fun saveExamAccess(input: AdminExamAccessDto): AdminExamAccessDto {
        val user = findUser(input.userName)
        val exam = findExam(input.examTitle)
        val saved = request(
            method = "POST",
            path = "/rest/v1/acceso_examen_usuario?on_conflict=id",
            body = json.encodeToString(listOf(input.toRow(user.id.requireId("usuario"), exam.id.requireId("examen")))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ExamAccessRow>().first()
        return input.copy(id = saved.id?.toString().orEmpty())
    }

    fun listTicketWallets(): List<AdminTicketWalletDto> {
        val usersById = usuarios().associateBy { it.id }
        val specialtiesById = especialidades().associateBy { it.id }
        return request("GET", "/rest/v1/cartera_tickets?select=*&order=updated_at.desc", null)
            .decodeList<TicketWalletRow>()
            .map { row ->
                val user = usersById[row.usuarioId]
                AdminTicketWalletDto(
                    userId = row.usuarioId.toString(),
                    userName = user?.displayName().orEmpty().ifBlank { "Usuario ${row.usuarioId}" },
                    specialty = user?.especialidadId?.let { specialtiesById[it]?.nombre }.orEmpty(),
                    balance = row.saldo,
                    status = "active",
                )
            }
    }

    fun listTicketTransactions(userId: String): List<AdminTicketTransactionDto> {
        return request(
            "GET",
            "/rest/v1/transaccion_ticket?select=*&usuario_id=eq.${userId.toIntId()}&order=created_at.desc&limit=100",
            null,
        ).decodeList<TicketTransactionRow>().map { it.toDto() }
    }

    fun appendTicketAdjustment(userId: String, input: AdminTicketAdjustmentDto): AdminTicketTransactionDto {
        require(input.amount != 0.0) { "El ajuste no puede ser 0." }
        require(input.reason.isNotBlank()) { "El ajuste necesita razon." }
        val numericUserId = userId.toIntId()
        val current = request("GET", "/rest/v1/cartera_tickets?select=*&usuario_id=eq.$numericUserId", null)
            .decodeList<TicketWalletRow>()
            .firstOrNull()
        val newBalance = (current?.saldo ?: 0.0) + input.amount
        if (current == null) {
            request(
                "POST",
                "/rest/v1/cartera_tickets",
                json.encodeToString(listOf(TicketWalletUpsertRow(numericUserId, newBalance))),
                prefer = "return=minimal",
            )
        } else {
            request("PATCH", "/rest/v1/cartera_tickets?usuario_id=eq.$numericUserId", """{"saldo":$newBalance}""")
        }
        return request(
            "POST",
            "/rest/v1/transaccion_ticket",
            json.encodeToString(
                listOf(
                    TicketTransactionInsertRow(
                        usuarioId = numericUserId,
                        tipo = if (input.amount >= 0) "credito" else "debito",
                        monto = kotlin.math.abs(input.amount),
                        razon = input.reason,
                    ),
                ),
            ),
            prefer = "return=representation",
        ).decodeList<TicketTransactionRow>().first().toDto(signedAmount = input.amount)
    }

    fun listSupportConversations(): List<AdminSupportConversationDto> {
        val usersById = usuarios().associateBy { it.id }
        val messages = supportMessages().groupBy { it.conversationId }
        return request("GET", "/rest/v1/support_conversation?select=*&order=updated_at.desc", null)
            .decodeList<SupportConversationRow>()
            .map { row ->
                val latest = messages[row.id].orEmpty().maxByOrNull { it.createdAt }
                AdminSupportConversationDto(
                    id = row.id?.toString().orEmpty(),
                    userName = usersById[row.studentUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.studentUsuarioId}" },
                    subject = row.titulo.orEmpty().ifBlank { "Sin asunto" },
                    status = row.estado,
                    assignedTo = row.assignedUsuarioId?.let { usersById[it]?.displayName() }.orEmpty(),
                    lastMessage = latest?.body.orEmpty(),
                )
            }
    }

    fun listSupportMessages(conversationId: String): List<AdminSupportMessageDto> {
        val usersById = usuarios().associateBy { it.id }
        return request(
            "GET",
            "/rest/v1/support_message?select=*&conversation_id=eq.${conversationId.toIntId()}&order=created_at.asc",
            null,
        ).decodeList<SupportMessageRow>().map { row ->
            AdminSupportMessageDto(
                id = row.id?.toString().orEmpty(),
                conversationId = row.conversationId.toString(),
                authorName = usersById[row.senderUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.senderUsuarioId}" },
                text = row.body.orEmpty(),
                timestamp = row.createdAt,
                internal = row.internalNote,
                attachmentLabel = row.attachmentPath,
            )
        }
    }

    fun sendSupportMessage(conversationId: String, input: AdminSupportReplyDto): AdminSupportMessageDto {
        require(input.text.isNotBlank()) { "El mensaje no puede estar vacio." }
        val conversation = request(
            "GET",
            "/rest/v1/support_conversation?select=*&id=eq.${conversationId.toIntId()}",
            null,
        ).decodeList<SupportConversationRow>().firstOrNull() ?: throw IllegalArgumentException("Conversacion no encontrada.")
        val sender = input.assignee.takeIf { it.isNotBlank() }?.let(::findUser)
            ?: conversation.assignedUsuarioId?.let { id -> usuarios().firstOrNull { it.id == id } }
            ?: usuarios().firstOrNull()
            ?: throw IllegalArgumentException("No hay usuarios reales para enviar el mensaje.")
        val saved = request(
            "POST",
            "/rest/v1/support_message",
            json.encodeToString(
                listOf(
                    SupportMessageInsertRow(
                        conversationId = conversationId.toIntId(),
                        senderUsuarioId = sender.id.requireId("usuario"),
                        body = input.text,
                        internalNote = input.internal,
                    ),
                ),
            ),
            prefer = "return=representation",
        ).decodeList<SupportMessageRow>().first()
        request("PATCH", "/rest/v1/support_conversation?id=eq.${conversationId.toIntId()}", """{"updated_at":"${Instant.now()}"}""")
        return AdminSupportMessageDto(
            id = saved.id?.toString().orEmpty(),
            conversationId = conversationId,
            authorName = sender.displayName(),
            text = saved.body.orEmpty(),
            timestamp = saved.createdAt,
            internal = saved.internalNote,
            attachmentLabel = saved.attachmentPath,
        )
    }

    fun listModerationActions(): List<AdminModerationActionDto> {
        val usersById = usuarios().associateBy { it.id }
        return request("GET", "/rest/v1/moderation_action?select=*&order=created_at.desc&limit=200", null)
            .decodeList<ModerationActionRow>()
            .map { row ->
                AdminModerationActionDto(
                    id = row.id?.toString().orEmpty(),
                    actionType = row.actionType,
                    targetUser = usersById[row.targetUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.targetUsuarioId}" },
                    executor = usersById[row.actorUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.actorUsuarioId}" },
                    reason = row.reason,
                    createdAt = row.createdAt,
                )
            }
    }

    fun appendModerationAction(input: AdminModerationActionDto): AdminModerationActionDto {
        require(input.reason.isNotBlank()) { "La accion necesita razon." }
        val target = findUser(input.targetUser)
        val actor = findUser(input.executor)
        val saved = request(
            "POST",
            "/rest/v1/moderation_action",
            json.encodeToString(listOf(input.toRow(target.id.requireId("usuario objetivo"), actor.id.requireId("actor")))),
            prefer = "return=representation",
        ).decodeList<ModerationActionRow>().first()
        return input.copy(id = saved.id?.toString().orEmpty(), createdAt = saved.createdAt)
    }

    fun dashboardMetrics(): AdminDashboardMetricsDto {
        val specialties = especialidades()
        val exams = examenes()
        val questions = request("GET", "/rest/v1/pregunta?select=id,examen_id&deleted_at=is.null&limit=10000", null)
            .decodeList<QuestionCountRow>()
        val resources = request("GET", "/rest/v1/recurso_pregunta?select=id,pregunta_id&deleted_at=is.null&limit=10000", null)
            .decodeList<ResourceCountRow>()
        val news = request("GET", "/rest/v1/noticia?select=id,estado&deleted_at=is.null&limit=10000", null)
            .decodeList<NewsCountRow>()
        val questionsWithResources = resources.map { it.preguntaId }.toSet()
        return AdminDashboardMetricsDto(
            totalSpecialties = specialties.size,
            totalExams = exams.size,
            publishedExams = exams.count { it.estado == "published" },
            totalQuestions = questions.size,
            lastUpdatedAt = Instant.now().toString(),
            draftExams = exams.count { it.estado == "draft" },
            questionsWithoutResources = questions.count { it.id !in questionsWithResources },
            linkedResources = resources.size,
            pendingResources = 0,
            unlinkedResources = 0,
            publishedNews = news.count { it.estado == "published" },
            draftNews = news.count { it.estado == "draft" },
            activeStoreItems = runCatching {
                request("GET", "/rest/v1/articulo_tienda?select=id&activo=eq.true&deleted_at=is.null", null)
                    .decodeList<IdOnlyRow>().size
            }.getOrDefault(0),
            activeOffers = 0,
            scheduledOffers = 0,
            activeBurstCampaigns = 0,
            activeLimitedResources = 0,
            nextCommerceExpiryLabel = "Sin vencimientos programados",
            latestImportLabel = "Ultima actualizacion: ${Instant.now()}",
        )
    }

    private fun usuarios(): List<UsuarioRow> {
        return request("GET", "/rest/v1/usuario?select=*&deleted_at=is.null&order=created_at.desc", null).decodeList()
    }

    private fun especialidades(): List<EspecialidadAdminRow> {
        return request("GET", "/rest/v1/especialidad?select=id,nombre&deleted_at=is.null", null).decodeList()
    }

    private fun examenes(): List<ExamRow> {
        return request("GET", "/rest/v1/examen?select=id,titulo,estado&deleted_at=is.null", null).decodeList()
    }

    private fun roleAssignments(): Map<Int?, List<String>> {
        val rolesById = request("GET", "/rest/v1/app_role?select=id,name", null)
            .decodeList<RoleRow>()
            .associateBy { it.id }
        return request("GET", "/rest/v1/user_role?select=usuario_id,role_id", null)
            .decodeList<UserRoleRow>()
            .groupBy { it.usuarioId }
            .mapValues { (_, rows) -> rows.mapNotNull { rolesById[it.roleId]?.id?.toString() } }
    }

    private fun replaceUserRoles(userId: Int, roleIds: List<String>) {
        request("DELETE", "/rest/v1/user_role?usuario_id=eq.$userId", null)
        val rows = roleIds.mapNotNull { roleId ->
            roleId.toIntOrNull()?.let { UserRoleInsertRow(usuarioId = userId, roleId = it) }
        }
        if (rows.isNotEmpty()) {
            request("POST", "/rest/v1/user_role", json.encodeToString(rows), prefer = "return=minimal")
        }
    }

    private fun supportMessages(): List<SupportMessageRow> {
        return request("GET", "/rest/v1/support_message?select=*&order=created_at.desc&limit=1000", null).decodeList()
    }

    private fun findUser(query: String): UsuarioRow {
        val normalized = query.trim().lowercase()
        return usuarios().firstOrNull {
            it.id?.toString() == normalized ||
                it.email.orEmpty().lowercase() == normalized ||
                it.nombre.orEmpty().lowercase() == normalized
        } ?: throw IllegalArgumentException("No se encontro usuario real para '$query'.")
    }

    private fun findExam(query: String): ExamRow {
        val normalized = query.trim().lowercase()
        return examenes().firstOrNull {
            it.id?.toString() == normalized || it.titulo.lowercase() == normalized
        } ?: throw IllegalArgumentException("No se encontro examen real para '$query'.")
    }

    private fun request(method: String, path: String, body: String?, prefer: String? = null): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("Supabase request failed with status ${response.statusCode()}")
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): SupabaseAdminGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseAdminGateway(url, serviceRoleKey, json)
        }
    }
}

@Serializable
data class AdminUserDto(
    val id: String,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    val isActive: Boolean,
    val createdAt: String,
)

@Serializable
data class AdminRoleDto(val id: String, val name: String, val permissions: List<AdminPermissionDto> = emptyList())

@Serializable
data class AdminPermissionDto(val key: String, val label: String)

@Serializable
data class AdminSettingsDto(
    val displayName: String,
    val email: String,
    val receiveReleaseNotes: Boolean,
    val defaultEnvironment: String,
    val preferFakeData: Boolean,
    val enableVerboseLogs: Boolean,
)

@Serializable
data class AdminAuditEventDto(
    val id: String,
    val actorName: String,
    val action: String,
    val target: String,
    val occurredAt: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AdminNewsTypeDto(val id: String, val name: String, val slug: String, val isActive: Boolean)

@Serializable
data class AdminExamAccessDto(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val isActive: Boolean,
)

@Serializable
data class AdminTicketWalletDto(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Double,
    val status: String,
)

@Serializable
data class AdminTicketTransactionDto(
    val id: String,
    val userId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val occurredAt: String,
)

@Serializable
data class AdminTicketAdjustmentDto(val amount: Double, val reason: String)

@Serializable
data class AdminSupportConversationDto(
    val id: String,
    val userName: String,
    val subject: String,
    val status: String,
    val assignedTo: String,
    val lastMessage: String,
)

@Serializable
data class AdminSupportMessageDto(
    val id: String,
    val conversationId: String,
    val authorName: String,
    val text: String,
    val timestamp: String,
    val internal: Boolean,
    val attachmentLabel: String? = null,
)

@Serializable
data class AdminSupportReplyDto(val assignee: String, val text: String, val internal: Boolean)

@Serializable
data class AdminModerationActionDto(
    val id: String,
    val actionType: String,
    val targetUser: String,
    val executor: String,
    val reason: String,
    val createdAt: String,
)

@Serializable
data class AdminDashboardMetricsDto(
    val totalSpecialties: Int,
    val totalExams: Int,
    val publishedExams: Int,
    val totalQuestions: Int,
    val lastUpdatedAt: String,
    val draftExams: Int,
    val questionsWithoutResources: Int,
    val linkedResources: Int,
    val pendingResources: Int,
    val unlinkedResources: Int,
    val publishedNews: Int,
    val draftNews: Int,
    val activeStoreItems: Int,
    val activeOffers: Int,
    val scheduledOffers: Int,
    val activeBurstCampaigns: Int,
    val activeLimitedResources: Int,
    val nextCommerceExpiryLabel: String,
    val latestImportLabel: String,
)

@Serializable
private data class UsuarioRow(
    val id: Int? = null,
    @SerialName("auth_user_id") val authUserId: String? = null,
    val email: String? = null,
    val nombre: String? = null,
    @SerialName("especialidad_id") val especialidadId: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
private data class RoleRow(val id: Int? = null, val name: String, val description: String? = null)

@Serializable
private data class UserRoleRow(@SerialName("usuario_id") val usuarioId: Int, @SerialName("role_id") val roleId: Int)

@Serializable
private data class UserRoleInsertRow(@SerialName("usuario_id") val usuarioId: Int, @SerialName("role_id") val roleId: Int)

@Serializable
private data class AuditRow(
    val id: Long? = null,
    @SerialName("actor_auth_user_id") val actorAuthUserId: String? = null,
    @SerialName("actor_usuario_id") val actorUsuarioId: Int? = null,
    val action: String,
    val resource: String,
    @SerialName("resource_id") val resourceId: String? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class NewsTypeRow(val id: Int? = null, val nombre: String, val slug: String, val activo: Boolean = true)

@Serializable
private data class ExamAccessRow(
    val id: Int? = null,
    @SerialName("usuario_id") val usuarioId: Int,
    @SerialName("examen_id") val examenId: Int,
    @SerialName("tipo_acceso") val tipoAcceso: String,
    val activo: Boolean = true,
)

@Serializable
private data class TicketWalletRow(@SerialName("usuario_id") val usuarioId: Int, val saldo: Double)

@Serializable
private data class TicketWalletUpsertRow(@SerialName("usuario_id") val usuarioId: Int, val saldo: Double)

@Serializable
private data class TicketTransactionRow(
    val id: Int? = null,
    @SerialName("usuario_id") val usuarioId: Int,
    val tipo: String,
    val monto: Double,
    val razon: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class TicketTransactionInsertRow(
    @SerialName("usuario_id") val usuarioId: Int,
    val tipo: String,
    val monto: Double,
    val razon: String,
)

@Serializable
private data class SupportConversationRow(
    val id: Int? = null,
    @SerialName("student_usuario_id") val studentUsuarioId: Int,
    @SerialName("assigned_usuario_id") val assignedUsuarioId: Int? = null,
    val estado: String,
    val titulo: String? = null,
)

@Serializable
private data class SupportMessageRow(
    val id: Int? = null,
    @SerialName("conversation_id") val conversationId: Int,
    @SerialName("sender_usuario_id") val senderUsuarioId: Int,
    val body: String? = null,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("internal_note") val internalNote: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class SupportMessageInsertRow(
    @SerialName("conversation_id") val conversationId: Int,
    @SerialName("sender_usuario_id") val senderUsuarioId: Int,
    val body: String,
    @SerialName("internal_note") val internalNote: Boolean,
)

@Serializable
private data class ModerationActionRow(
    val id: Int? = null,
    @SerialName("target_usuario_id") val targetUsuarioId: Int,
    @SerialName("actor_usuario_id") val actorUsuarioId: Int,
    @SerialName("action_type") val actionType: String,
    val reason: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class ModerationActionInsertRow(
    @SerialName("target_usuario_id") val targetUsuarioId: Int,
    @SerialName("actor_usuario_id") val actorUsuarioId: Int,
    @SerialName("action_type") val actionType: String,
    val reason: String,
)

@Serializable
private data class EspecialidadAdminRow(val id: Int? = null, val nombre: String)

@Serializable
private data class ExamRow(val id: Int? = null, val titulo: String, val estado: String = "draft")

@Serializable
private data class QuestionCountRow(val id: Int? = null, @SerialName("examen_id") val examenId: Int)

@Serializable
private data class ResourceCountRow(val id: Int? = null, @SerialName("pregunta_id") val preguntaId: Int)

@Serializable
private data class NewsCountRow(val id: Int? = null, val estado: String)

@Serializable
private data class IdOnlyRow(val id: Int? = null)

private fun UsuarioRow.toDto(roleIds: List<String>) = AdminUserDto(
    id = id?.toString().orEmpty(),
    fullName = displayName(),
    email = email.orEmpty(),
    roleIds = roleIds,
    isActive = true,
    createdAt = createdAt,
)

private fun AdminUserDto.toRow() = UsuarioRow(
    id = id.toIntOrNull(),
    email = email,
    nombre = fullName,
)

private fun RoleRow.toDto() = AdminRoleDto(
    id = id?.toString().orEmpty(),
    name = name,
    permissions = description.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { AdminPermissionDto(key = it, label = it) },
)

private fun AdminRoleDto.toRow() = RoleRow(
    id = id.toIntOrNull(),
    name = name,
    description = permissions.joinToString(",") { it.key },
)

private fun AdminAuditEventDto.toRow(): AuditRow {
    val parts = target.split(':', limit = 2)
    return AuditRow(
        action = action,
        resource = parts.firstOrNull().orEmpty().ifBlank { "admin" },
        resourceId = parts.getOrNull(1),
        metadata = metadata,
        createdAt = occurredAt.ifBlank { Instant.now().toString() },
    )
}

private fun NewsTypeRow.toDto() = AdminNewsTypeDto(
    id = id?.toString().orEmpty(),
    name = nombre,
    slug = slug,
    isActive = activo,
)

private fun AdminNewsTypeDto.toRow() = NewsTypeRow(
    id = id.toIntOrNull(),
    nombre = name,
    slug = slug.ifBlank { name.toSlug() },
    activo = isActive,
)

private fun AdminExamAccessDto.toRow(userId: Int, examId: Int) = ExamAccessRow(
    id = id.toIntOrNull(),
    usuarioId = userId,
    examenId = examId,
    tipoAcceso = accessType.ifBlank { "manual" },
    activo = isActive,
)

private fun TicketTransactionRow.toDto(signedAmount: Double? = null) = AdminTicketTransactionDto(
    id = id?.toString().orEmpty(),
    userId = usuarioId.toString(),
    type = tipo,
    amount = signedAmount ?: if (tipo == "debito") -monto else monto,
    reason = razon,
    occurredAt = createdAt,
)

private fun AdminModerationActionDto.toRow(targetUserId: Int, actorUserId: Int) = ModerationActionInsertRow(
    targetUsuarioId = targetUserId,
    actorUsuarioId = actorUserId,
    actionType = actionType.ifBlank { "warning" },
    reason = reason,
)

private fun UsuarioRow.displayName(): String = nombre.orEmpty().ifBlank { email.orEmpty() }

private fun Int?.requireId(label: String): Int = this ?: throw IllegalArgumentException("No se encontro id de $label.")

private fun String.toIntId(): Int = toIntOrNull() ?: throw IllegalArgumentException("Id no valido.")

private fun String.toSlug(): String {
    val normalized = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
    return URLEncoder.encode(normalized.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), StandardCharsets.UTF_8)
        .ifBlank { "item" }
}
