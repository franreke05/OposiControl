package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Supabase FALSO para las solicitudes de acceso: una sola maquina que hace de PostgREST (`solicitudes_acceso_admin`,
 * `roles_usuario`, `auditoria_log`, `perfiles_usuario`) y de GoTrue (API de administracion, `otp`, `resend`, `verify`,
 * `logout`, `signup`, `recover`, `token`). Aplica los filtros de las consultas reales (asi una consulta mal compuesta no
 * encuentra nada) y GRABA cada peticion en orden, con su cuerpo y sus cabeceras, para comprobar exactamente lo que sale.
 *
 * Detras van el [SupabaseAdminAccessRequestGateway] REAL y, si hace falta, el [SupabaseOposiBotsAuthGateway] REAL (mismo
 * cliente: la URL de prueba es la misma), asi que una prueba ve el orden completo «borrar cuenta -> signup de GoTrue».
 */
internal class AccessRequestFakeSupabase {
    class Sent(val method: HttpMethod, val path: String, val query: String, val body: String, val headers: Map<String, String>) {
        val call: String get() = "${method.value} $path"
        fun param(name: String): String? = query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        fun jsonBody(): JsonObject = Json.parseToJsonElement(body).let { if (it is JsonArray) it.single().jsonObject else it.jsonObject }
    }

    class Row(
        val id: String,
        val usuarioId: String,
        val email: String,
        @Volatile var estado: String = ADMIN_ACCESS_ESTADO_PENDIENTE,
        @Volatile var creadoEn: Instant = Instant.now(),
        @Volatile var verificadoEn: Instant? = null,
        @Volatile var resueltoEn: Instant? = null,
        @Volatile var resueltoPor: String? = null,
        @Volatile var intentos: Int = 0,
    )

    class Account(
        val id: String,
        val email: String,
        @Volatile var confirmed: Boolean = false,
        val roles: MutableSet<String> = mutableSetOf(),
        @Volatile var password: String? = null,
        @Volatile var banned: Boolean = false,
    ) {
        /** Contrasenas fijadas por la API de administracion (PUT), en orden. */
        val passwordChanges = CopyOnWriteArrayList<String>()
    }

    val sent = CopyOnWriteArrayList<Sent>()
    val rows = CopyOnWriteArrayList<Row>()
    val accounts: MutableMap<String, Account> = ConcurrentHashMap()

    /** El codigo de 6 cifras que GoTrue «envio» a cada correo. */
    val codes: MutableMap<String, String> = ConcurrentHashMap()

    /** Usuario de la sesion que acuñan `/auth/v1/token` (sin correo conocido) y el `verify` por `token_hash` (puerta de sesion). */
    @Volatile var sessionUserId: String = UID_A

    /** `true` = el `verify` por codigo acepta pero NO devuelve sesion (revision #3 L1: aun asi se purgan las sesiones). */
    @Volatile var verifyWithoutSession: Boolean = false

    /** Estado de la fila de perfil que lee la puerta de sesion. */
    @Volatile var profileState: String = "activa"

    /** Como escribe GoTrue el correo al crear la cuenta (L2). Por defecto, tal cual llega. */
    @Volatile var upstreamEmailSpelling: (String) -> String = { it }

    /** Respuesta forzada para una peticion (estado HTTP), o `null` = el comportamiento normal. */
    @Volatile var override: (Sent) -> HttpStatusCode? = { null }

    val client = HttpClient(
        MockEngine { request: HttpRequestData ->
            val seen = Sent(
                method = request.method,
                path = request.url.encodedPath,
                query = request.url.encodedQuery,
                body = String(request.body.toByteArray(), Charsets.UTF_8),
                headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
            )
            sent += seen
            override(seen)?.let { status -> return@MockEngine json(status, """{"code":"forced","error_code":"forced","msg":"forced"}""") }
            handle(seen)
        },
    )

    fun calls(): List<String> = sent.map { it.call }
    fun audits(): List<JsonObject> = sent.filter { it.call == "POST /rest/v1/auditoria_log" }.map { it.jsonBody() }
    fun mails(): List<Sent> = sent.filter {
        it.path == "/auth/v1/resend" || it.path == "/auth/v1/signup" || it.path == "/auth/v1/recover" || it.path == "/auth/v1/otp"
    }
    fun logouts(): List<Sent> = sent.filter { it.path == "/auth/v1/logout" }

    /** Cuentas cuyas sesiones se purgaron con la RPC (revision #3 L1), en orden. */
    val purgedSessions = CopyOnWriteArrayList<String>()
    fun adminPuts(userId: String): List<JsonObject> =
        sent.filter { it.call == "PUT /auth/v1/admin/users/$userId" }.map { it.jsonBody() }

    /** Una solicitud con su cuenta de Auth (sin confirmar y sin roles salvo que se diga). */
    fun seed(
        email: String,
        userId: String = UUID.randomUUID().toString(),
        estado: String = ADMIN_ACCESS_ESTADO_PENDIENTE,
        createdAgoSeconds: Long = 60,
        verified: Boolean = false,
        accountConfirmed: Boolean = false,
        roles: Set<String> = emptySet(),
        rowId: String = UUID.randomUUID().toString(),
        attempts: Int = 0,
        password: String? = ATTACKER_PASSWORD,
    ): Row {
        accounts[userId] = Account(userId, email, accountConfirmed || verified, roles.toMutableSet(), password = password, banned = verified && estado != ADMIN_ACCESS_ESTADO_APROBADA)
        val created = Instant.now().minusSeconds(createdAgoSeconds)
        return Row(
            id = rowId,
            usuarioId = userId,
            email = email,
            estado = estado,
            creadoEn = created,
            verificadoEn = if (verified) created.plusSeconds(10) else null,
            resueltoEn = if (estado == ADMIN_ACCESS_ESTADO_PENDIENTE) null else created.plusSeconds(20),
            intentos = attempts,
        ).also { rows += it }
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String): HttpResponseData =
        respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun Row.toJson(): String =
        """{"id":"$id","usuario_id":"$usuarioId","nombre":"Ada Lovelace","email":"$email","motivo":"Coordino el equipo.",""" +
            """"estado":"$estado","creado_en":"$creadoEn","correo_verificado_en":${verificadoEn?.let { "\"$it\"" } ?: "null"},""" +
            """"resuelto_en":${resueltoEn?.let { "\"$it\"" } ?: "null"},"resuelto_por":${resueltoPor?.let { "\"$it\"" } ?: "null"},""" +
            """"motivo_resolucion":null,"intentos_verificacion":$intentos}"""

    private fun Account.toJson(): String =
        """{"id":"$id","email":"$email","email_confirmed_at":${if (confirmed) "\"2026-10-14T10:00:00Z\"" else "null"},""" +
            """"banned_until":${if (banned) "\"2126-10-14T10:00:00Z\"" else "null"}}"""

    /** Filtros PostgREST que usan las consultas reales; cualquier otro filtro hace fallar la prueba (consulta inesperada). */
    private fun Sent.matches(row: Row): Boolean = query.split('&').filter { it.isNotBlank() }.all { pair ->
        val name = pair.substringBefore('=')
        val value = URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
        when (name) {
            "select", "limit", "order", "offset" -> true
            "email" -> value == "eq.${row.email}"
            "usuario_id" -> value == "eq.${row.usuarioId}"
            "id" -> value == "eq.${row.id}"
            "estado" -> if (value.startsWith("neq.")) row.estado != value.removePrefix("neq.") else row.estado == value.removePrefix("eq.")
            "correo_verificado_en" -> { check(value == "is.null") { "filtro inesperado $pair" }; row.verificadoEn == null }
            "intentos_verificacion" -> value == "eq.${row.intentos}"
            "resuelto_por" -> value == "eq.${row.resueltoPor}"
            else -> error("filtro inesperado en $call: $pair")
        }
    }

    private fun MockRequestHandleScope.handle(s: Sent): HttpResponseData {
        val adminUser = Regex("^/auth/v1/admin/users/([0-9a-fA-F-]{36})$").find(s.path)?.groupValues?.get(1)?.lowercase()
        return when {
            s.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE" ->
                json(HttpStatusCode.OK, rows.filter { s.matches(it) }.take(s.param("limit")?.toInt() ?: 1_000).joinToString(",", "[", "]") { it.toJson() })
            s.call == "POST /rest/v1/$ADMIN_ACCESS_TABLE" -> {
                val body = s.jsonBody()
                rows += Row(
                    id = UUID.randomUUID().toString(),
                    usuarioId = body["usuario_id"]!!.jsonPrimitive.content,
                    email = body["email"]!!.jsonPrimitive.content,
                )
                json(HttpStatusCode.Created, "")
            }
            s.call == "PATCH /rest/v1/$ADMIN_ACCESS_TABLE" -> {
                val body = s.jsonBody()
                val hit = rows.filter { s.matches(it) }
                hit.forEach { row ->
                    body["correo_verificado_en"]?.let { row.verificadoEn = Instant.parse(it.jsonPrimitive.content) }
                    body["estado"]?.let { row.estado = it.jsonPrimitive.content }
                    body["resuelto_en"]?.let { row.resueltoEn = if (it is JsonNull) null else Instant.parse(it.jsonPrimitive.content) }
                    body["resuelto_por"]?.let { row.resueltoPor = if (it is JsonNull) null else it.jsonPrimitive.content }
                    body["intentos_verificacion"]?.let { row.intentos = it.jsonPrimitive.content.toInt() }
                }
                json(HttpStatusCode.OK, hit.joinToString(",", "[", "]") { it.toJson() })
            }
            s.call == "DELETE /rest/v1/$ADMIN_ACCESS_TABLE" -> {
                val hit = rows.filter { s.matches(it) }
                rows.removeAll(hit.toSet())
                json(HttpStatusCode.OK, hit.joinToString(",", "[", "]") { it.toJson() })
            }
            s.call == "GET /rest/v1/roles_usuario" -> {
                val user = s.param("usuario_id")!!.removePrefix("eq.")
                json(HttpStatusCode.OK, accounts[user]?.roles.orEmpty().joinToString(",", "[", "]") { """{"rol_id":"$it"}""" })
            }
            s.call == "POST /rest/v1/auditoria_log" -> json(HttpStatusCode.Created, "")
            s.call == "GET /rest/v1/perfiles_usuario" ->
                json(HttpStatusCode.OK, """[{"usuario_id":"$sessionUserId","estado_cuenta":"$profileState","onboarding_completado":true}]""")
            s.call == "POST /rest/v1/rpc/oposibots_claim_oauth_credential" -> json(HttpStatusCode.NoContent, "")
            // Revision #3 L1: la purga de sesiones (RPC solo service_role). Devuelve un entero, como PostgREST.
            s.call == "POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC" -> {
                purgedSessions += s.jsonBody()["p_user_id"]!!.jsonPrimitive.content
                json(HttpStatusCode.OK, "1")
            }
            s.call == "POST /auth/v1/admin/users" -> {
                val body = s.jsonBody()
                val email = upstreamEmailSpelling(body["email"]!!.jsonPrimitive.content)
                if (accounts.values.any { it.email == email }) {
                    json(HttpStatusCode.UnprocessableEntity, """{"code":422,"error_code":"email_exists","msg":"exists"}""")
                } else {
                    val id = UUID.randomUUID().toString()
                    accounts[id] = Account(
                        id,
                        email,
                        confirmed = body["email_confirm"]?.jsonPrimitive?.content == "true",
                        password = body["password"]?.jsonPrimitive?.content,
                    )
                    json(HttpStatusCode.OK, """{"id":"$id","email":"$email"}""")
                }
            }
            adminUser != null && s.method == HttpMethod.Get -> accounts[adminUser]?.let { account -> json(HttpStatusCode.OK, account.toJson()) }
                ?: json(HttpStatusCode.NotFound, """{"code":404,"error_code":"user_not_found","msg":"User not found"}""")
            adminUser != null && s.method == HttpMethod.Delete -> if (accounts.remove(adminUser) != null) {
                rows.removeAll { it.usuarioId == adminUser } // FK ON DELETE CASCADE
                json(HttpStatusCode.OK, "{}")
            } else {
                json(HttpStatusCode.NotFound, """{"code":404,"error_code":"user_not_found","msg":"User not found"}""")
            }
            adminUser != null && s.method == HttpMethod.Put -> accounts[adminUser]?.let { account ->
                val body = s.jsonBody()
                body["password"]?.jsonPrimitive?.content?.let { account.password = it; account.passwordChanges += it }
                body["ban_duration"]?.jsonPrimitive?.content?.let { account.banned = it != "none" }
                json(HttpStatusCode.OK, account.toJson())
            } ?: json(HttpStatusCode.NotFound, """{"code":404,"error_code":"user_not_found","msg":"User not found"}""")
            s.call == "POST /auth/v1/resend" || s.call == "POST /auth/v1/recover" -> json(HttpStatusCode.OK, "{}")
            s.call == "POST /auth/v1/otp" -> {
                val body = s.jsonBody()
                val email = body["email"]!!.jsonPrimitive.content
                if (body["create_user"]?.jsonPrimitive?.content == "false" && accounts.values.none { it.email == email }) {
                    json(HttpStatusCode.UnprocessableEntity, """{"code":422,"error_code":"otp_disabled","msg":"Signups not allowed for otp"}""")
                } else {
                    json(HttpStatusCode.OK, "{}")
                }
            }
            s.call == "POST /auth/v1/signup" -> {
                val email = s.jsonBody()["email"]!!.jsonPrimitive.content
                val account = accounts.values.firstOrNull { it.email == email }
                    ?: Account(UUID.randomUUID().toString(), email).also { accounts[it.id] = it }
                json(HttpStatusCode.OK, """{"user":{"id":"${account.id}"}}""")
            }
            s.call == "POST /auth/v1/verify" -> {
                val body = s.jsonBody()
                if (body["token_hash"] != null) {
                    json(HttpStatusCode.OK, session(sessionUserId))
                } else {
                    val email = body["email"]!!.jsonPrimitive.content
                    val account = accounts.values.firstOrNull { it.email == email }
                    val token = body["token"]?.jsonPrimitive?.content
                    if (account != null && !account.banned && token != null && token == codes[email] && body["type"]?.jsonPrimitive?.content == "email") {
                        account.confirmed = true
                        codes.remove(email)
                        if (verifyWithoutSession) {
                            json(HttpStatusCode.OK, """{"user":{"id":"${account.id}"}}""")
                        } else {
                            json(HttpStatusCode.OK, session(account.id, prefix = "verify"))
                        }
                    } else {
                        json(HttpStatusCode.Forbidden, """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}""")
                    }
                }
            }
            s.call == "POST /auth/v1/token" -> {
                // Inicio con contrasena de una cuenta conocida (la neutralizacion): solo con SU contrasena y sin baneo.
                val body = runCatching { s.jsonBody() }.getOrNull()
                val email = body?.get("email")?.jsonPrimitive?.content
                val account = email?.let { e -> accounts.values.firstOrNull { it.email == e } }
                when {
                    account == null -> json(HttpStatusCode.OK, session(sessionUserId))
                    account.banned -> json(HttpStatusCode.BadRequest, """{"code":400,"error_code":"user_banned","msg":"User is banned"}""")
                    !account.confirmed || account.password != body["password"]?.jsonPrimitive?.content ->
                        json(HttpStatusCode.BadRequest, """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
                    else -> json(HttpStatusCode.OK, session(account.id, prefix = "neutralise"))
                }
            }
            s.call == "POST /auth/v1/logout" -> json(HttpStatusCode.NoContent, "")
            else -> error("Peticion inesperada: ${s.method.value} ${s.path}?${s.query}")
        }
    }

    private fun session(userId: String, prefix: String = "minted"): String {
        val email = accounts[userId]?.email ?: "ada@example.com"
        return """{"access_token":"$prefix.access.jwt","refresh_token":"$prefix-refresh","expires_in":3600,"user":{"id":"$userId","email":"$email"}}"""
    }

    /** El gateway REAL de solicitudes de acceso sobre este Supabase falso, con el seudonimo con clave de las pruebas. */
    fun accessGateway(): SupabaseAdminAccessRequestGateway = SupabaseAdminAccessRequestGateway(
        supabaseUrl = testCanonicalConfiguration().supabaseUrl,
        serviceRoleKey = "service-role-key",
        json = testJson,
        client = client,
        emailRef = testCanonicalConfiguration()::authLogEmailRef,
    )

    /** La guarda REAL sobre el gateway REAL. */
    fun guard(): PostgrestAccessRequestMailGuard =
        PostgrestAccessRequestMailGuard(store = accessGateway(), emailRef = testCanonicalConfiguration()::authLogEmailRef)

    companion object {
        const val UID_A = "33333333-3333-4333-8333-333333333333"

        /** La contrasena que fijo quien creo la solicitud en las cuentas sembradas. */
        const val ATTACKER_PASSWORD = "Contrasena-Del-Creador-1"
    }
}

/** Sin `Json` del proyecto: lee un array JSON de texto (para comprobar cuerpos de prueba). */
internal fun String.jsonArrayOf(): JsonArray = Json.parseToJsonElement(this).jsonArray

/** Base de los dobles en memoria del gateway: todo lo que una prueba no sobrescribe falla («no interviene»). */
internal open class UnusedAdminAccessRequestGateway : AdminAccessRequestGateway {
    override suspend fun submit(submission: AdminAccessSubmission): AdminAccessSubmitOutcome = error("no interviene")
    override suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto> = error("no interviene")
    override suspend fun find(id: String): AdminAccessRequestDto? = error("no interviene")
    override suspend fun resolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto? = error("no interviene")
    override suspend fun sendAccessCode(email: String): Unit = error("no interviene")
    override suspend fun verifyAccessCode(email: String, code: String): VerifiedSignup? = error("no interviene")
    override suspend fun revokeSession(accessToken: String): Boolean = error("no interviene")
    override suspend fun markEmailVerified(id: String, usuarioId: String): AdminAccessRequestDto? = error("no interviene")
    override suspend fun setBanned(usuarioId: String, banned: Boolean): Unit = error("no interviene")
    override suspend fun setPassword(usuarioId: String, password: String): Unit = error("no interviene")
    override suspend fun purgeSessions(usuarioId: String): Int = error("no interviene")
    override suspend fun recordFailedVerification(reservation: AccessRequestReservation): Int? = error("no interviene")
    override suspend fun revertApproval(id: String, actorId: String): AdminAccessRequestDto? = error("no interviene")
    override suspend fun nonApprovedByEmail(email: String): List<AccessRequestReservation> = error("no interviene")
    override suspend fun nonApprovedByUser(userId: String): AccessRequestReservation? = error("no interviene")
    override suspend fun authAccount(usuarioId: String): AuthAccountState? = error("no interviene")
    override suspend fun hasAnyRole(usuarioId: String): Boolean = error("no interviene")
    override suspend fun deleteAccount(usuarioId: String): Unit = error("no interviene")
    override suspend fun deleteUnverifiedRequest(id: String): Boolean = error("no interviene")
    override suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean): Unit = error("no interviene")
    override suspend fun audit(accion: String, registroId: String, despues: JsonObject): Unit = error("no interviene")
}
