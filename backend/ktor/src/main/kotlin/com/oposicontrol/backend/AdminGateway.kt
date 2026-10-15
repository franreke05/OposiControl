package com.oposicontrol.backend

import io.ktor.http.HttpMethod
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

/** [moderation]: el gateway canonico cuyo servicio de moderacion usa el adaptador EP-144 (RC-01). */
fun Route.adminRoutes(gateway: SupabaseAdminGateway, moderation: AdminV1Gateway) {
    route("/admin") {
        get("/users") { call.respond(gateway.listUsers()) }
        get("/users/{userId}") { call.respond(gateway.getUser(call.requiredPath("userId"))) }
        // RC-10 (MASTERF1-API-002): escritores legacy sin llamador vivo, retirados con 410 (mismo patron que EP-150 y la
        // cartera legacy): el cuerpo NO se lee y no hay I/O upstream antes del rechazo. Contados por `legacyRoute`.
        post("/users") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_152)
        post("/users/{userId}") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_153)
        put("/users/{userId}") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_224)
        delete("/users/{userId}") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_001)
        get("/roles") { call.respond(gateway.listRoles()) }
        post("/roles") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_147)
        post("/roles/{roleId}") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_148)
        put("/roles/{roleId}") { rejectLegacyAdminWriter() }.legacyRoute(LegacyEndpoints.EP_222)
        get("/settings") { call.respond(gateway.settings()) }
        // Auditoria de controles (E1): escritores sin llamador vivo, retirados con 410 y contados (RETIRED_LEGACY_WRITERS).
        // `/settings` respondia el eco sin persistir nada; `/audit` dejaba fabricar filas de auditoria a mano.
        retiredLegacyWriter(HttpMethod.Post, "/settings", LegacyEndpoints.retiredWriter("POST", "/admin/settings"))
        retiredLegacyWriter(HttpMethod.Put, "/settings", LegacyEndpoints.retiredWriter("PUT", "/admin/settings"))
        get("/audit") { call.respond(gateway.listAudit()) }
        retiredLegacyWriter(HttpMethod.Post, "/audit", LegacyEndpoints.retiredWriter("POST", "/admin/audit"))
        get("/news-types") { call.respond(gateway.listNewsTypes()) }.legacyRoute(LegacyEndpoints.EP_025)
        // EP-145: el panel escribe ya por `PUT /api/v1/admin/tipos-noticia/{codigo}` (D4-11). Sigue en SLICE_0 con su sucesora.
        post("/news-types") { rejectLegacyAdminWriter(NEWS_TYPE_WRITER_RETIRED) }.legacyRoute(LegacyEndpoints.EP_145)
        retiredLegacyWriter(HttpMethod.Post, "/news-types/{newsTypeId}", LegacyEndpoints.retiredWriter("POST", "/admin/news-types/{newsTypeId}"))
        retiredLegacyWriter(HttpMethod.Put, "/news-types/{newsTypeId}", LegacyEndpoints.retiredWriter("PUT", "/admin/news-types/{newsTypeId}"))
        get("/exam-access") { call.respond(gateway.listExamAccess()) }
        retiredLegacyWriter(HttpMethod.Post, "/exam-access", LegacyEndpoints.retiredWriter("POST", "/admin/exam-access"))
        retiredLegacyWriter(HttpMethod.Post, "/exam-access/{accessId}", LegacyEndpoints.retiredWriter("POST", "/admin/exam-access/{accessId}"))
        retiredLegacyWriter(HttpMethod.Put, "/exam-access/{accessId}", LegacyEndpoints.retiredWriter("PUT", "/admin/exam-access/{accessId}"))
        get("/ticket-wallets") { call.respond(gateway.listTicketWallets()) }
        get("/ticket-wallets/{userId}/transactions") {
            call.respond(gateway.listTicketTransactions(call.requiredPath("userId")))
        }
        post("/ticket-wallets/{userId}/transactions") {
            // El cuerpo NO se lee: la ruta está deshabilitada y `call.receive()` se
            // evaluaría antes que el rechazo (Kotlin evalúa los argumentos de
            // izquierda a derecha), de modo que un JSON mal formado moría en
            // BadRequestException con un 500 en lugar del 410 previsto.
            gateway.rejectLegacyTicketAdjustment(call.requiredPath("userId"))
        }
        get("/support/conversations") { call.respond(gateway.listSupportConversations()) }
        get("/support/conversations/{conversationId}/messages") {
            call.respond(gateway.listSupportMessages(call.requiredPath("conversationId")))
        }
        post("/support/conversations/{conversationId}/messages") {
            // MASTER7-API-006: ruta retirada. El cuerpo NO se lee (mismo motivo que la cartera legacy: un JSON mal
            // formado debe recibir el 410, no un 400/500) y no hay I/O upstream antes del rechazo.
            gateway.rejectLegacySupportReply(call.requiredPath("conversationId"))
        }
        get("/moderation/actions") { call.respond(gateway.listModerationActions()) }
        post("/moderation/actions") {
            // RC-01: EP-144 es un ADAPTADOR fino sobre el MISMO servicio canonico que `POST /api/v1/admin/moderacion/actions`
            // ([AdminV1Gateway.applyModerationAction]): objetivo = uuid, actor = `sub` del JWT (403 al token estatico antes de
            // leer el cuerpo); el `executor` del cliente se IGNORA y la respuesta devuelve el actor real.
            val actorId = call.moderationActorOrForbidden()
            val input = call.receive<AdminModerationActionDto>()
            val command = ModerationCommand.fromLegacy(input)
            withUpstreamIo { moderation.applyModerationAction(actorId, command) }
            call.respond(
                HttpStatusCode.Created,
                AdminModerationActionDto(
                    id = "",
                    actionType = input.actionType,
                    targetUser = command.targetUserId,
                    executor = actorId,
                    reason = command.reason,
                    createdAt = "",
                ),
            )
        }.legacyRoute(LegacyEndpoints.EP_144)
    }
    get("/dashboard/metrics") {
        call.respond(gateway.dashboardMetrics())
    }
}

/**
 * RC-10: rechazo de los escritores legacy de usuarios/roles. No recibe el gateway: por construccion no puede hacer I/O
 * upstream. Las sucesoras auditadas y con la guarda MASTER-AUTH-001 son las rutas `/api/v1/admin/usuarios/...`.
 */
internal fun rejectLegacyAdminWriter(
    message: String = "La escritura legacy de usuarios/roles esta deshabilitada (sin guarda de cuentas administrativas ni auditoria). " +
        "Usa las rutas /api/v1/admin/usuarios/{id} (PATCH) y /api/v1/admin/usuarios/{id}/roles (POST), que la aplican.",
): Nothing {
    throw BackendDomainException("ADMIN_LEGACY_ROUTE_DISABLED", HttpStatusCode.Gone, message)
}

private const val NEWS_TYPE_WRITER_RETIRED =
    "La escritura legacy de tipos de noticia esta retirada. Usa PUT /api/v1/admin/tipos-noticia/{codigo} (D4-11)."

/**
 * [LegacyEndpoints.RETIRED_LEGACY_WRITERS]: mismo rechazo (410 ADMIN_LEGACY_ROUTE_DISABLED, sin leer el cuerpo ni I/O
 * upstream) y la ruta sigue contada por `legacyRoute`. Monta la hoja ya marcada.
 */
internal fun Route.retiredLegacyWriter(method: HttpMethod, path: String, endpoint: LegacyEndpoint): Route {
    val message = "Esta escritura legacy (${endpoint.endpointId}) esta retirada: no tiene llamador vivo y escribia sin las " +
        "guardas ni la auditoria de su sucesora. Usa la ruta canonica /api/v1/admin/... equivalente."
    val leaf = when (method) {
        HttpMethod.Post -> post(path) { rejectLegacyAdminWriter(message) }
        HttpMethod.Put -> put(path) { rejectLegacyAdminWriter(message) }
        HttpMethod.Delete -> delete(path) { rejectLegacyAdminWriter(message) }
        else -> error("Solo se retiran escritores: $method $path")
    }
    return leaf.legacyRoute(endpoint)
}

class SupabaseAdminGateway internal constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = newUpstreamHttpClient(),
) {
    fun listUsers(): List<AdminUserDto> = emptyListOnMissingLegacySchema("listUsers") {
        val rolesByUser = roleAssignments()
        usuarios().map { user ->
            user.toDto(rolesByUser[user.id].orEmpty())
        }
    }

    fun getUser(userId: String): AdminUserDto {
        return listUsers().firstOrNull { it.id == userId }
            ?: throw IllegalArgumentException("Usuario no encontrado.")
    }

    fun listRoles(): List<AdminRoleDto> = emptyListOnMissingLegacySchema("listRoles") {
        request("GET", "/rest/v1/app_role?select=id,name,description&order=name.asc", null)
            .decodeList<RoleRow>()
            .map { it.toDto() }
    }

    fun settings(): AdminSettingsDto = adminSettingsFromEnvironment()

    fun listAudit(): List<AdminAuditEventDto> = emptyListOnMissingLegacySchema("listAudit") {
        val usersById = usuarios().associateBy { it.id }
        request("GET", "/rest/v1/audit_log?select=*&order=id.desc&limit=200", null)
            .decodeList<AuditRow>()
            .map { row ->
                AdminAuditEventDto(
                    id = row.id?.toString().orEmpty(),
                    actorName = usersById[row.actorUsuarioId]?.displayName().orEmpty().ifBlank {
                        row.actorAuthUserId.orEmpty().ifBlank { "Sistema" }
                    },
                    action = row.action,
                    target = listOf(row.resource, row.resourceId.orEmpty()).filter(String::isNotBlank).joinToString(":"),
                    occurredAt = "",
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

    fun listNewsTypes(): List<AdminNewsTypeDto> = emptyListOnMissingLegacySchema("listNewsTypes") {
        request("GET", "/rest/v1/tipo_noticia?select=id,nombre,slug,activo&order=nombre.asc", null)
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

    fun listExamAccess(): List<AdminExamAccessDto> = emptyListOnMissingLegacySchema("listExamAccess") {
        val usersById = usuarios().associateBy { it.id }
        val examsById = examenes().associateBy { it.id }
        request("GET", "/rest/v1/acceso_examen_usuario?select=*&order=id.desc", null)
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
        require(input.accessType.isNotBlank()) { "El acceso necesita tipo_acceso." }
        val user = findUser(input.userName)
        val exam = findExam(input.examTitle)
        val saved = request(
            method = "POST",
            path = "/rest/v1/acceso_examen_usuario?on_conflict=id",
            body = json.encodeToString(listOf(input.toRow(user.id.requireId("usuario"), exam.id.requireId("examen")))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ExamAccessRow>().first()
        appendAudit(
            AdminAuditEventDto(
                id = "",
                actorName = "backend-admin",
                action = if (input.isActive) "EXAM_ACCESS_GRANT" else "EXAM_ACCESS_REVOKE",
                target = "acceso_examen_usuario:${saved.id}",
                occurredAt = Instant.now().toString(),
                metadata = mapOf("usuario_id" to user.id.toString(), "examen_id" to exam.id.toString()),
            ),
        )
        return input.copy(id = saved.id?.toString().orEmpty())
    }

    fun listTicketWallets(): List<AdminTicketWalletDto> = emptyListOnMissingLegacySchema("listTicketWallets") {
        val usersById = usuarios().associateBy { it.id }
        val specialtiesById = especialidades().associateBy { it.id }
        request("GET", "/rest/v1/cartera_tickets?select=*&order=usuario_id.desc", null)
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

    fun listTicketTransactions(userId: String): List<AdminTicketTransactionDto> = emptyListOnMissingLegacySchema("listTicketTransactions") {
        request(
            "GET",
            "/rest/v1/transaccion_ticket?select=*&usuario_id=eq.${userId.toIntId()}&order=id.desc&limit=100",
            null,
        ).decodeList<TicketTransactionRow>().map { it.toDto() }
    }

    /**
     * DESHABILITADA A PROPOSITO — no borrada (ver ADR de legacy: primero se
     * sustituye funcionalmente, el cleanup destructivo va despues).
     *
     * Esta ruta legacy hacia una mutacion monetaria NO atomica sobre
     * `cartera_tickets`: leia el saldo, sumaba en memoria y escribia el
     * resultado, y despues insertaba la fila de ledger en una segunda llamada
     * suelta. Eso pierde escrituras concurrentes (lost update) y puede dejar
     * saldo y ledger divergentes si la segunda llamada falla. Ademas nunca
     * registraba saldo_antes/saldo_despues ni aceptaba clave de idempotencia.
     *
     * El escritor canonico `admin_grant_tickets` ya resuelve esto (atomico,
     * idempotente y seguro ante concurrencia) y se expone en
     * `/api/v1/admin/tickets/{usuarioId}/transacciones`.
     *
     * No se traduce automaticamente la llamada legacy al RPC canonico porque las
     * familias de identidad no coinciden: aqui el usuario es un entero legacy y
     * el RPC exige el uuid de `auth.users`. Adivinar esa correspondencia moveria
     * dinero a la cartera equivocada, asi que se rechaza en la puerta.
     *
     * Las lecturas legacy se conservan (`listTicketWallets` /
     * `listTicketTransactions`): solo se cierra la escritura.
     */
    fun rejectLegacyTicketAdjustment(userId: String): Nothing {
        throw BackendDomainException(
            "WALLET_LEGACY_ROUTE_DISABLED",
            HttpStatusCode.Gone,
            "El ajuste manual de cartera legacy esta deshabilitado porque no era atomico. " +
                "Usa POST /api/v1/admin/tickets/{usuarioId}/transacciones, que escribe mediante " +
                "el RPC atomico e idempotente admin_grant_tickets.",
        )
    }

    fun listSupportConversations(): List<AdminSupportConversationDto> = emptyListOnMissingLegacySchema("listSupportConversations") {
        val usersById = usuarios().associateBy { it.id }
        val messages = supportMessages().groupBy { it.conversationId }
        request("GET", "/rest/v1/support_conversation?select=*&order=id.desc", null)
            .decodeList<SupportConversationRow>()
            .map { row ->
                val latest = messages[row.id].orEmpty().maxByOrNull { it.id ?: 0 }
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

    fun listSupportMessages(conversationId: String): List<AdminSupportMessageDto> = emptyListOnMissingLegacySchema("listSupportMessages") {
        val usersById = usuarios().associateBy { it.id }
        request(
            "GET",
            "/rest/v1/support_message?select=*&conversation_id=eq.${conversationId.toIntId()}&order=id.asc",
            null,
        ).decodeList<SupportMessageRow>().map { row ->
            AdminSupportMessageDto(
                id = row.id?.toString().orEmpty(),
                conversationId = row.conversationId.toString(),
                authorName = usersById[row.senderUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.senderUsuarioId}" },
                text = row.body.orEmpty(),
                timestamp = "",
                internal = row.internalNote,
                attachmentLabel = row.attachmentPath,
            )
        }
    }

    /**
     * MASTER7-API-006: la respuesta de soporte legacy elegia el remitente por `assignee` de texto libre, por el
     * asignado de la conversacion o por el primer usuario (suplantacion de autoria) y no dejaba auditoria. Se retira
     * igual que [rejectLegacyTicketAdjustment]: 410 antes de leer el cuerpo o tocar Supabase. El escritor canonico
     * (`POST /api/v1/admin/soporte/conversaciones/{id}/mensajes`) atribuye la respuesta al actor verificado y la audita.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rejectLegacySupportReply(conversationId: String): Nothing {
        throw BackendDomainException(
            "SUPPORT_LEGACY_ROUTE_DISABLED",
            HttpStatusCode.Gone,
            "La respuesta de soporte legacy esta deshabilitada. " +
                "Usa POST /api/v1/admin/soporte/conversaciones/{id}/mensajes, que atribuye la respuesta al actor " +
                "autenticado y la audita.",
        )
    }

    fun listModerationActions(): List<AdminModerationActionDto> = emptyListOnMissingLegacySchema("listModerationActions") {
        val usersById = usuarios().associateBy { it.id }
        request("GET", "/rest/v1/moderation_action?select=*&order=id.desc&limit=200", null)
            .decodeList<ModerationActionRow>()
            .map { row ->
                AdminModerationActionDto(
                    id = row.id?.toString().orEmpty(),
                    actionType = row.actionType,
                    targetUser = usersById[row.targetUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.targetUsuarioId}" },
                    executor = usersById[row.actorUsuarioId]?.displayName().orEmpty().ifBlank { "Usuario ${row.actorUsuarioId}" },
                    reason = row.reason,
                    createdAt = "",
                )
            }
    }

    fun dashboardMetrics(): AdminDashboardMetricsDto = fallbackOnMissingLegacySchema("dashboardMetrics", ::emptyDashboardMetrics) {
        val specialties = especialidades()
        val exams = examenes()
        val questions = request("GET", "/rest/v1/pregunta?select=id,examen_id&limit=10000", null)
            .decodeList<QuestionCountRow>()
        val resources = request("GET", "/rest/v1/recurso_pregunta?select=id,pregunta_id&limit=10000", null)
            .decodeList<ResourceCountRow>()
        val news = request("GET", "/rest/v1/noticia?select=id,estado&limit=10000", null)
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
                request("GET", "/rest/v1/articulo_tienda?select=id&activo=eq.true", null)
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
        return request("GET", "/rest/v1/usuario?select=*&order=id.desc", null).decodeList()
    }

    private fun especialidades(): List<EspecialidadAdminRow> {
        return request("GET", "/rest/v1/especialidad?select=id,nombre", null).decodeList()
    }

    private fun examenes(): List<ExamRow> {
        return request("GET", "/rest/v1/examen?select=id,titulo,estado", null).decodeList()
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

    private fun supportMessages(): List<SupportMessageRow> {
        return request("GET", "/rest/v1/support_message?select=*&order=id.desc&limit=1000", null).decodeList()
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
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = try {
            client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            val preview = response.body().take(300)
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: $preview")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    private inline fun <T> emptyListOnMissingLegacySchema(operation: String, block: () -> List<T>): List<T> =
        fallbackOnMissingLegacySchema(operation, { emptyList() }, block)

    private inline fun <T> fallbackOnMissingLegacySchema(operation: String, fallback: () -> T, block: () -> T): T {
        return try {
            block()
        } catch (error: IllegalStateException) {
            if (!error.isMissingLegacySchema()) throw error
            System.err.println("[ktor] legacy admin fallback ($operation): ${error.message?.take(180)}")
            fallback()
        }
    }

    private fun Throwable.isMissingLegacySchema(): Boolean {
        val text = message.orEmpty()
        return text.contains("PGRST205") ||
            text.contains("Could not find the table") ||
            text.contains("schema cache")
    }

    private fun emptyDashboardMetrics(): AdminDashboardMetricsDto = AdminDashboardMetricsDto(
        totalSpecialties = 0,
        totalExams = 0,
        publishedExams = 0,
        totalQuestions = 0,
        lastUpdatedAt = Instant.now().toString(),
        draftExams = 0,
        questionsWithoutResources = 0,
        linkedResources = 0,
        pendingResources = 0,
        unlinkedResources = 0,
        publishedNews = 0,
        draftNews = 0,
        activeStoreItems = 0,
        activeOffers = 0,
        scheduledOffers = 0,
        activeBurstCampaigns = 0,
        activeLimitedResources = 0,
        nextCommerceExpiryLabel = "Pendiente de migracion",
        latestImportLabel = "Legacy pendiente de migracion: ${Instant.now()}",
    )

    companion object {
        /**
         * F-01/F-02: clave por [SupabaseRequestAuthPolicy.secretFromEnvironment] y
         * cabeceras por [supabaseElevatedAuth]. Leía sólo `SUPABASE_SERVICE_ROLE_KEY`
         * (con la configuración canónica el backend no arrancaba) y firmaba `Bearer`
         * sin mirar la forma de la clave.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): SupabaseAdminGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
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
    /** RC-06: ids exactos (uuid). En `POST /api/v1/admin/accesos` se prefieren a `userName`/`examTitle`; opcionales. */
    val userId: String? = null,
    val examId: String? = null,
    /** Solo salida: cuando se concedio y, si ya no esta vigente, hasta cuando lo estuvo. */
    val grantedAt: String? = null,
    val validUntil: String? = null,
)

@Serializable
data class AdminTicketWalletDto(
    val userId: String,
    val userName: String,
    val specialty: String,
    val balance: Double,
    val status: String,
)

/**
 * Movimiento de cartera de OposiTickets.
 *
 * Los cuatro últimos campos son la parte auditable y llegan sólo desde el ledger
 * canónico (`transacciones_cartera`): sin saldo antes/después no se puede
 * reconstruir el histórico ni detectar un descuadre, y sin origen y clave de
 * idempotencia no se sabe qué provocó el movimiento ni si un reintento lo
 * duplicó. Son nullable porque la superficie legacy no los tiene.
 */
@Serializable
data class AdminTicketTransactionDto(
    val id: String,
    val userId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val occurredAt: String,
    val balanceBefore: Int? = null,
    val balanceAfter: Int? = null,
    /** Qué originó el movimiento: "admin", una compra, una recompensa… */
    val originType: String? = null,
    val originId: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
data class AdminTicketAdjustmentDto(
    val amount: Double,
    val reason: String,
    val idempotencyKey: String? = null,
)

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
)

@Serializable
private data class RoleRow(val id: Int? = null, val name: String, val description: String? = null)

@Serializable
private data class UserRoleRow(@SerialName("usuario_id") val usuarioId: Int, @SerialName("role_id") val roleId: Int)

@Serializable
private data class AuditRow(
    val id: Long? = null,
    @SerialName("actor_auth_user_id") val actorAuthUserId: String? = null,
    @SerialName("actor_usuario_id") val actorUsuarioId: Int? = null,
    val action: String,
    val resource: String,
    @SerialName("resource_id") val resourceId: String? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * Fila de `public.tipo_noticia`.
 *
 * `id` es `uuid`, no entero. Declarado como `Int?` la deserialización de
 * PostgREST reventaba y el catálogo entero llegaba al panel como «No
 * disponible», sin poder clasificar ninguna noticia. Mismo defecto que ya se
 * corrigió en `NewsGateway`: la tabla nació con claves `uuid` y el backend
 * asumía las de un esquema que nunca existió.
 */
@Serializable
private data class NewsTypeRow(val id: String? = null, val nombre: String, val slug: String, val activo: Boolean = true)

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
)

@Serializable
private data class ModerationActionRow(
    val id: Int? = null,
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
    createdAt = "",
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

private fun AdminAuditEventDto.toRow(): AuditRow {
    val parts = target.split(':', limit = 2)
    return AuditRow(
        action = action,
        resource = parts.firstOrNull().orEmpty().ifBlank { "admin" },
        resourceId = parts.getOrNull(1),
        metadata = metadata,
    )
}

private fun NewsTypeRow.toDto() = AdminNewsTypeDto(
    id = id?.toString().orEmpty(),
    name = nombre,
    slug = slug,
    isActive = activo,
)

private fun AdminNewsTypeDto.toRow() = NewsTypeRow(
    // En alta se omite y Postgres aplica `gen_random_uuid()`; en edición viaja
    // el uuid y `on_conflict=id` fusiona. `toIntOrNull()` lo anulaba siempre,
    // así que editar un tipo creaba uno nuevo en vez de actualizarlo.
    id = id.takeIf { it.isNotBlank() },
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
    occurredAt = "",
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

/**
 * Los ajustes del panel, leídos del entorno del proceso.
 *
 * Estaba escrito dos veces, byte a byte: una en `AdminGateway.settings()` y
 * otra en el gateway de `AdminV1Routes`. Son dos clases distintas que sirven a
 * dos rutas distintas, así que las dos siguen teniendo su `settings()`; lo que
 * ya no tienen es su propia copia de QUÉ variables de entorno se leen y con
 * qué valores por defecto. Añadir un ajuste era acertar en dos sitios.
 */
internal fun adminSettingsFromEnvironment(): AdminSettingsDto = AdminSettingsDto(
    displayName = System.getenv("OPOSICONTROL_ADMIN_DISPLAY_NAME").orEmpty().ifBlank { "Administrador" },
    email = System.getenv("OPOSICONTROL_ADMIN_EMAIL").orEmpty(),
    receiveReleaseNotes = false,
    defaultEnvironment = AppEnvironment.fromSystemEnvironment(),
    preferFakeData = false,
    enableVerboseLogs = System.getenv("ENABLE_VERBOSE_NETWORK_LOGS")?.toBooleanStrictOrNull() ?: false,
)
