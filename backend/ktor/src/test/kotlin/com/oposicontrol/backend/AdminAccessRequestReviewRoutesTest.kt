package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revision de solicitudes de acceso (`/api/v1/admin/solicitudes-acceso*`): solo superadmin (403 `SUPERADMIN_REQUIRED` para
 * un admin normal), aprobar concede `admin` -y solo `admin`- por el camino auditado (`syncRoles` -> RPC
 * `admin_sync_roles_audited` con el actor del JWT) y rechazar no toca ningun rol.
 *
 * Las solicitudes viven en un gateway en memoria; los roles pasan por el [AdminV1Gateway] REAL contra un Supabase falso,
 * para observar exactamente lo que sale hacia la RPC y hacia `auditoria_log`.
 */
class AdminAccessRequestReviewRoutesTest {
    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val adminActor = "5a5a5a5a-0000-4000-8000-000000000002"
    private val applicant = "5a5a5a5a-0000-4000-8000-000000000003"
    private val applicantWithRole = "5a5a5a5a-0000-4000-8000-000000000004"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"
    private val pendingId = "77777777-7777-4777-8777-000000000001"
    private val pendingWithRoleId = "77777777-7777-4777-8777-000000000002"
    private val unknownId = "77777777-7777-4777-8777-00000000ffff"

    /** Supabase falso para el gateway de roles: catalogo, roles por usuario (mutables por la RPC) y auditoria. */
    private inner class Stub {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val rolesOf = ConcurrentHashMap(
            mapOf(
                superActor to listOf(roleSuper),
                adminActor to listOf(roleAdmin),
                applicant to emptyList(),
                applicantWithRole to listOf(roleUser),
            ),
        )
        var rpcStatus = 200

        fun respond(uri: String, body: String): V1StubResponse = when {
            "/rest/v1/roles_app" in uri -> V1StubResponse(
                200,
                """[{"id":"$roleSuper","codigo":"superadmin","nombre":"Superadmin"},{"id":"$roleAdmin","codigo":"admin","nombre":"Admin"},""" +
                    """{"id":"$roleUser","codigo":"usuario","nombre":"Usuario"}]""",
            )
            "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq." in uri -> {
                val id = uri.substringAfter("usuario_id=eq.").substringBefore('&')
                V1StubResponse(200, rolesOf[id].orEmpty().joinToString(",", "[", "]") { """{"rol_id":"$it"}""" })
            }
            "/rest/v1/rpc/admin_sync_roles_audited" in uri -> if (rpcStatus >= 400) {
                V1StubResponse(rpcStatus, """{"message":"boom"}""")
            } else {
                timeline += "rol"
                val args = Json.parseToJsonElement(body).jsonObject
                val user = args["p_usuario_id"]!!.jsonPrimitive.content
                val before = rolesOf[user].orEmpty()
                val after = args["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                rolesOf[user] = after
                V1StubResponse(200, """{"antes":${before.map { "\"$it\"" }},"despues":${after.map { "\"$it\"" }}}""")
            }
            else -> V1StubResponse(201, "")
        }

        fun rpcBodies(): List<JsonObject> = sent.filter { "/rest/v1/rpc/admin_sync_roles_audited" in it.uri().toString() }
            .map { Json.parseToJsonElement(readRequestBody(it)).jsonObject }

        fun audits(): List<JsonObject> = sent.filter { "/rest/v1/auditoria_log" in it.uri().toString() }
            .map { Json.parseToJsonElement(readRequestBody(it)).jsonArray.single().jsonObject }
    }

    private class InMemoryRequests(vararg seed: AdminAccessRequestDto) : UnusedAdminAccessRequestGateway() {
        val rows = ConcurrentHashMap(seed.associateBy { it.id })
        val listCalls = CopyOnWriteArrayList<Triple<String?, Int, Int>>()
        var resolveCalls = 0

        /** Cuentas de Auth con el correo confirmado (lo que diria `GET /auth/v1/admin/users/{id}`); por defecto, todas. */
        val unconfirmedAccounts = CopyOnWriteArrayList<String>()
        val missingAccounts = CopyOnWriteArrayList<String>()
        val accountReads = CopyOnWriteArrayList<String>()
        var accountFailure: Exception? = null
        var resolveFailure: Exception? = null
        var onEvent: (String) -> Unit = {}

        override suspend fun authAccount(usuarioId: String): AuthAccountState? {
            accountReads += usuarioId
            accountFailure?.let { throw it }
            if (usuarioId in missingAccounts) return null
            return AuthAccountState(emailConfirmed = usuarioId !in unconfirmedAccounts)
        }

        /** Cuentas de Auth borradas (lo que haria `DELETE /auth/v1/admin/users/{id}`). */
        val deleted = CopyOnWriteArrayList<String>()
        var deleteFailure: Exception? = null

        override suspend fun deleteAccount(usuarioId: String) {
            deleteFailure?.let { throw it }
            onEvent("borrar")
            deleted += usuarioId
        }

        override suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto> {
            listCalls += Triple(estado, limit, offset)
            return rows.values.filter { estado == null || it.estado == estado }.sortedBy { it.id }
        }

        override suspend fun find(id: String) = rows[id]

        /** Se ejecuta justo antes de aplicar una resolucion (simula a otro superadministrador que llega a la vez). */
        var beforeResolve: (String) -> Unit = {}

        override suspend fun resolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto? {
            resolveCalls++
            resolveFailure?.let { throw it }
            beforeResolve(id)
            onEvent("marcar:$estado")
            return applyResolve(id, estado, actorId, motivo)
        }

        fun applyResolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto? {
            val current = rows[id]?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_PENDIENTE } ?: return null
            return current.copy(estado = estado, resueltoEn = "2026-10-13T10:00:00Z", resueltoPor = actorId, motivoResolucion = motivo)
                .also { rows[id] = it }
        }

        /** Baneos de GoTrue (M1): `true` = baneada. */
        val banned = ConcurrentHashMap<String, Boolean>()
        var unbanFailure: Exception? = null

        var rebanFailure: Exception? = null

        /** Revision #3 L1: purgas de sesiones (RPC), en orden. */
        val purged = CopyOnWriteArrayList<String>()
        var purgeFailure: Exception? = null

        override suspend fun purgeSessions(usuarioId: String): Int {
            purgeFailure?.let { throw it }
            onEvent("purgar")
            purged += usuarioId
            return 0
        }

        override suspend fun setBanned(usuarioId: String, banned: Boolean) {
            if (!banned) unbanFailure?.let { throw it }
            if (banned) rebanFailure?.let { throw it }
            onEvent(if (banned) "banear" else "desbanear")
            this.banned[usuarioId] = banned
        }

        var revertCalls = 0

        override suspend fun revertApproval(id: String, actorId: String): AdminAccessRequestDto? {
            revertCalls++
            onEvent("revertir")
            val current = rows[id]?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_APROBADA && it.resueltoPor == actorId } ?: return null
            return current.copy(estado = ADMIN_ACCESS_ESTADO_PENDIENTE, resueltoEn = null, resueltoPor = null, motivoResolucion = null)
                .also { rows[id] = it }
        }
    }

    private fun request(
        id: String,
        usuarioId: String,
        estado: String = ADMIN_ACCESS_ESTADO_PENDIENTE,
        verified: Boolean = true,
        expired: Boolean = false,
    ) = AdminAccessRequestDto(
        id = id,
        usuarioId = usuarioId,
        nombre = "Ada Lovelace",
        email = "ada@example.com",
        motivo = "Coordino el equipo de contenido.",
        estado = estado,
        creadoEn = "2026-10-13T09:00:00Z",
        correoVerificado = verified,
        caducada = !verified && expired,
        correoVerificadoEn = if (verified) "2026-10-13T09:05:00Z" else null,
    )

    /** Orden real de los pasos de una aprobacion (L1/M1): `marcar:<estado>`, `desbanear`, `rol` (la RPC auditada); y la reversion. */
    private val timeline = CopyOnWriteArrayList<String>()

    private val unverifiedId = "77777777-7777-4777-8777-000000000003"
    private val expiredId = "77777777-7777-4777-8777-000000000004"
    private val unverifiedApplicant = "5a5a5a5a-0000-4000-8000-000000000005"
    private val expiredApplicant = "5a5a5a5a-0000-4000-8000-000000000006"

    private fun requests() = InMemoryRequests(
        request(pendingId, applicant),
        request(pendingWithRoleId, applicantWithRole),
        request(unverifiedId, unverifiedApplicant, verified = false),
        request(expiredId, expiredApplicant, verified = false, expired = true),
    ).also { it.onEvent = { event -> timeline += event } }

    private fun Application.module(stub: Stub, requests: AdminAccessRequestGateway?) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } }
        }
        val roles = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ stub.sent += it }, { uri -> stub.respond(uri, stub.sent.lastOrNull()?.let(::readRequestBody).orEmpty()) }),
        )
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminAccessRequestRoutes(requests, roles)
            }
        }
    }

    private val base = "/api/v1/admin/solicitudes-acceso"

    private suspend fun ApplicationTestBuilder.list(actor: String, query: String = ""): HttpResponse =
        client.get("$base$query") { header(HttpHeaders.Authorization, "Bearer $actor") }

    private suspend fun ApplicationTestBuilder.approve(actor: String, id: String): HttpResponse =
        client.post("$base/$id/aprobar") { header(HttpHeaders.Authorization, "Bearer $actor") }

    private suspend fun ApplicationTestBuilder.reject(actor: String, id: String, body: String? = null): HttpResponse =
        client.post("$base/$id/rechazar") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code(): String? = json()["code"]?.jsonPrimitive?.content

    // ------------------------------------------------------------------------------------------------ solo superadmin

    @Test
    fun aPlainAdminGetsSuperadminRequiredOnTheThreeRoutesAndNothingIsReadOrWritten() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val responses = listOf(list(adminActor), approve(adminActor, pendingId), reject(adminActor, pendingId, """{"motivo":"no"}"""))

        responses.forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals("SUPERADMIN_REQUIRED", response.code())
        }
        assertTrue(requests.listCalls.isEmpty() && requests.resolveCalls == 0, "un admin normal no llega a leer ni a resolver")
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertTrue(stub.rpcBodies().isEmpty() && stub.audits().isEmpty())
    }

    @Test
    fun aCallerThatIsNotAPersonIsRejectedTheSameWay() = testApplication {
        val stub = Stub()
        application { module(stub, requests()) }

        // El token estatico legacy autentica como un nombre que no es un uuid: no hay actor, se falla cerrado.
        val response = approve("oposicontrol-admin", pendingId)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("SUPERADMIN_REQUIRED", response.code())
        assertTrue(stub.sent.isEmpty(), "sin actor no se consulta nada")
    }

    @Test
    fun withoutCredentialsTheRoutesAnswer401() = testApplication {
        application { module(Stub(), requests()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get(base).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$base/$pendingId/aprobar").status)
    }

    // ------------------------------------------------------------------------------------------------ listado

    @Test
    fun aSuperadminListsTheRequestsAndCanFilterByState() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val all = list(superActor)
        assertEquals(HttpStatusCode.OK, all.status, all.bodyAsText())
        assertEquals("no-store", all.headers[HttpHeaders.CacheControl])
        val items = all.json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(pendingId, pendingWithRoleId, unverifiedId, expiredId), items.map { it["id"]!!.jsonPrimitive.content })
        val first = items.first()
        assertEquals("Ada Lovelace", first["nombre"]!!.jsonPrimitive.content)
        assertEquals("ada@example.com", first["email"]!!.jsonPrimitive.content)
        assertEquals("Coordino el equipo de contenido.", first["motivo"]!!.jsonPrimitive.content)
        assertEquals("pendiente", first["estado"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T09:00:00Z", first["creadoEn"]!!.jsonPrimitive.content)
        // Lo que el panel necesita para decidir si ensena «Aprobar»: siempre presentes, tambien cuando son `false`.
        assertEquals(listOf("true", "true", "false", "false"), items.map { it["correoVerificado"]!!.jsonPrimitive.content })
        assertEquals(listOf("false", "false", "false", "true"), items.map { it["caducada"]!!.jsonPrimitive.content })
        assertEquals("2026-10-13T09:05:00Z", first["correoVerificadoEn"]!!.jsonPrimitive.content)
        assertFalse("correoVerificadoEn" in items[2], "sin verificar: el instante no viaja (null omitido)")
        assertFalse("password" in all.bodyAsText().lowercase())

        assertEquals(HttpStatusCode.OK, list(superActor, "?estado=Rechazada&limit=999&offset=5").status)
        assertEquals(
            listOf(Triple(null, ADMIN_ACCESS_LIST_DEFAULT_LIMIT, 0), Triple("rechazada", ADMIN_ACCESS_LIST_MAX_LIMIT, 5)),
            requests.listCalls.toList(),
        )
    }

    @Test
    fun invalidListFiltersAre400() = testApplication {
        val requests = requests()
        application { module(Stub(), requests) }
        listOf("?estado=superadmin", "?limit=0", "?limit=abc", "?offset=-1").forEach { query ->
            val response = list(superActor, query)
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("VALIDATION", response.code())
        }
        assertTrue(requests.listCalls.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ aprobar

    @Test
    fun approvingGrantsAdminAndOnlyAdminThroughTheAuditedRoleSync() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val response = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("aprobada", body["estado"]!!.jsonPrimitive.content)
        assertEquals(superActor, body["resueltoPor"]!!.jsonPrimitive.content)
        // La RPC auditada, con el actor del JWT y el rol admin (jamas superadmin).
        val rpc = stub.rpcBodies().single()
        assertEquals(applicant, rpc["p_usuario_id"]!!.jsonPrimitive.content)
        assertEquals(superActor, rpc["p_actor_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleAdmin), rpc["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), rpc["p_expected_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
        // L1/M1: la cuenta se comprueba (solo lectura), PRIMERO se marca (condicionado), despues se desbanea y al final el rol.
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(listOf(applicant), requests.accountReads.toList())
        // Auditoria: intencion y resultado de la sincronizacion de roles y la resolucion, todas con el actor.
        val audits = stub.audits()
        assertEquals(
            listOf(ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNCED, ACTION_ACCESS_REQUEST_APPROVED),
            audits.map { it["accion"]!!.jsonPrimitive.content },
        )
        assertTrue(audits.all { it["usuario_id"]!!.jsonPrimitive.content == superActor }, "$audits")
        val resolution = audits.last()
        assertEquals("true", resolution["despues"]!!.jsonObject["correoVerificado"]!!.jsonPrimitive.content)
        assertFalse("emailConfirmado" in resolution["despues"]!!.jsonObject, "la aprobacion ya no confirma ningun correo")
        assertEquals(ADMIN_ACCESS_TABLE, resolution["tabla_afectada"]!!.jsonPrimitive.content)
        assertEquals(pendingId, resolution["registro_id"]!!.jsonPrimitive.content)
        assertEquals("pendiente", resolution["antes"]!!.jsonObject["estado"]!!.jsonPrimitive.content)
        assertEquals("aprobada", resolution["despues"]!!.jsonObject["estado"]!!.jsonPrimitive.content)
        assertEquals(applicant, resolution["despues"]!!.jsonObject["usuarioId"]!!.jsonPrimitive.content)
        // La auditoria no copia datos personales de la solicitud.
        assertFalse("ada@example.com" in resolution.toString() || "Ada Lovelace" in resolution.toString())
    }

    @Test
    fun approvingKeepsTheRolesTheAccountAlreadyHad() = testApplication {
        val stub = Stub()
        application { module(stub, requests()) }

        assertEquals(HttpStatusCode.OK, approve(superActor, pendingWithRoleId).status)

        val rpc = stub.rpcBodies().single()
        assertEquals(listOf(roleUser, roleAdmin), rpc["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(roleUser), rpc["p_expected_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theRoleNeverComesFromTheRequestEvenIfTheBodyAsksForSuperadmin() = testApplication {
        val stub = Stub()
        application { module(stub, requests()) }

        val response = client.post("$base/$pendingId/aprobar") {
            header(HttpHeaders.Authorization, "Bearer $superActor")
            contentType(ContentType.Application.Json)
            setBody("""{"rol":"superadmin","roleIds":["$roleSuper"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(roleAdmin), stub.rpcBodies().single()["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun approvingTwiceIsIdempotentAndDoesNotSyncRolesAgain() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val first = approve(superActor, pendingId)
        val second = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, stub.rpcBodies().size)
        assertEquals(1, requests.resolveCalls)
        assertEquals(1, requests.accountReads.size, "una ya aprobada no vuelve a mirar la cuenta")
        assertEquals(1, stub.audits().count { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })
    }

    /** Sin verificar (caducada o no): 409 CONFLICT y NADA mas (ni lectura de la cuenta, ni rol, ni marca, ni auditoria). */
    @Test
    fun anUnverifiedOrExpiredRequestCannotBeApproved() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val unverified = approve(superActor, unverifiedId)
        val expired = approve(superActor, expiredId)

        for (response in listOf(unverified, expired)) {
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertEquals("CONFLICT", response.code())
        }
        assertTrue("introducir el código" in unverified.json()["message"]!!.jsonPrimitive.content, unverified.bodyAsText())
        assertTrue("caducó" in expired.json()["message"]!!.jsonPrimitive.content, expired.bodyAsText())
        assertTrue(requests.accountReads.isEmpty())
        assertTrue(stub.rpcBodies().isEmpty(), "sin el buzon demostrado no se concede ningun rol")
        assertEquals(0, requests.resolveCalls)
        assertTrue(stub.audits().isEmpty())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[unverifiedId]!!.estado)
    }

    /** Doble comprobacion: verificada en la tabla pero Supabase Auth no tiene el correo confirmado (o no hay cuenta): 409. */
    @Test
    fun aVerifiedRequestWhoseAuthAccountIsNotConfirmedCannotBeApproved() = testApplication {
        val stub = Stub()
        val requests = requests().apply {
            unconfirmedAccounts += applicant
            missingAccounts += applicantWithRole
        }
        application { module(stub, requests) }

        val unconfirmed = approve(superActor, pendingId)
        val missing = approve(superActor, pendingWithRoleId)

        for (response in listOf(unconfirmed, missing)) {
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertEquals("CONFLICT", response.code())
        }
        assertEquals(listOf(applicant, applicantWithRole), requests.accountReads.toList())
        assertTrue(stub.rpcBodies().isEmpty())
        assertEquals(0, requests.resolveCalls)
        assertTrue(stub.audits().isEmpty())
    }

    /** Una lectura de la cuenta que falla PROPAGA (5xx), nunca se convierte en un «si»: nada se concede. */
    @Test
    fun ifTheAccountLookupFailsNothingIsGrantedAndARetryCompletesIt() = testApplication {
        val stub = Stub()
        val requests = requests().apply { accountFailure = SupabaseHttpStatusException(502, "Supabase GET /auth/v1/admin/users -> 502 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertTrue(stub.rpcBodies().isEmpty())
        assertEquals(0, requests.resolveCalls)
        assertTrue(stub.audits().isEmpty())

        requests.accountFailure = null
        assertEquals(HttpStatusCode.OK, approve(superActor, pendingId).status)
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado)
    }

    @Test
    fun ifTheRoleSyncFailsTheRequestStaysPendingAndCanBeRetried() = testApplication {
        val stub = Stub().apply { rpcStatus = 500 }
        val requests = requests()
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)
        // L1: la marca ya estaba; el rol falla -> se vuelve a banear, la marca vuelve a `pendiente`, se audita y 503.
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertEquals("UPSTREAM_UNAVAILABLE", failed.code())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertEquals(null, requests.rows[pendingId]!!.resueltoPor)
        assertEquals(1, requests.resolveCalls)
        assertEquals(1, requests.revertCalls)
        assertEquals(true, requests.banned[applicant], "sin rol, la cuenta vuelve a quedar baneada")
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "banear", "revertir"), timeline.toList())
        val audits = stub.audits()
        assertFalse(audits.any { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })
        val reverted = audits.single { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVAL_REVERTED }
        assertEquals("pendiente", reverted["despues"]!!.jsonObject["estado"]!!.jsonPrimitive.content)
        assertEquals("rol", reverted["despues"]!!.jsonObject["pasoFallido"]!!.jsonPrimitive.content)
        assertEquals("true", reverted["despues"]!!.jsonObject["revertida"]!!.jsonPrimitive.content)
        assertEquals(superActor, reverted["usuario_id"]!!.jsonPrimitive.content)

        timeline.clear()
        stub.rpcStatus = 200
        assertEquals(HttpStatusCode.OK, approve(superActor, pendingId).status)
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado)
        // El reintento vuelve a comprobar la cuenta (solo lectura) y completa marca, desbaneo y rol.
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(listOf(applicant, applicant), requests.accountReads.toList())
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
        assertEquals(false, requests.banned[applicant])
    }

    /** M1: si el desbaneo falla, no se concede el rol, la marca se revierte y la aprobacion falla con 503. */
    @Test
    fun ifTheUnbanFailsNoRoleIsGrantedAndTheApprovalIsReverted() = testApplication {
        val stub = Stub()
        val requests = requests().apply { unbanFailure = SupabaseHttpStatusException(500, "Supabase PUT /auth/v1/admin/users -> 500 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertTrue(stub.rpcBodies().isEmpty(), "sin desbaneo no hay rol")
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertEquals(listOf("marcar:aprobada", "purgar", "revertir"), timeline.toList(), "nunca desbaneada: no se vuelve a banear")
        val reverted = stub.audits().single { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVAL_REVERTED }
        assertEquals("desbaneo", reverted["despues"]!!.jsonObject["pasoFallido"]!!.jsonPrimitive.content)
    }

    @Test
    fun ifMarkingTheRequestFailsNothingElseHappensAndARetryCompletesIt() = testApplication {
        val stub = Stub()
        val requests = requests().apply { resolveFailure = SupabaseHttpStatusException(503, "Supabase PATCH /rest/v1/solicitudes_acceso_admin -> 503 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        // L1: la marca va PRIMERO; si falla, ni desbaneo ni rol.
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertTrue(requests.banned.isEmpty())
        assertFalse(stub.audits().any { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })

        requests.resolveFailure = null
        val retried = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, retried.status, retried.bodyAsText())
        assertEquals("aprobada", retried.json()["estado"]!!.jsonPrimitive.content)
        assertEquals(1, stub.rpcBodies().size)
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(1, stub.audits().count { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })
    }

    /** L1, carrera: el rechazo gana justo antes de que la aprobacion marque. La aprobacion no marca, no desbanea ni concede rol: 409. */
    @Test
    fun aRejectionThatWinsTheRaceLeavesNoAdminRoleOnTheRejectedRequest() = testApplication {
        val stub = Stub()
        val requests = requests()
        requests.beforeResolve = { id ->
            if (requests.rows[id]!!.estado == ADMIN_ACCESS_ESTADO_PENDIENTE) requests.applyResolve(id, ADMIN_ACCESS_ESTADO_RECHAZADA, superActor, null)
        }
        application { module(stub, requests) }

        val response = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_RECHAZADA, requests.rows[pendingId]!!.estado)
        assertTrue(stub.rpcBodies().isEmpty(), "una solicitud rechazada nunca recibe admin")
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertTrue(requests.banned.isEmpty())
    }

    /** L1, carrera inversa: la aprobacion ya marco; un rechazo que llega mientras concede el rol no puede marcar (409 en su ruta). */
    @Test
    fun aRejectionDuringTheGrantCannotFlipAnApprovedRequest() = testApplication {
        val stub = Stub()
        val requests = requests()
        var rejectSawResolution: AdminAccessRequestDto? = AdminAccessRequestDto("", "", "", "", "", "", "", false, false)
        requests.onEvent = { event ->
            timeline += event
            if (event == "desbanear") rejectSawResolution = requests.applyResolve(pendingId, ADMIN_ACCESS_ESTADO_RECHAZADA, superActor, null)
        }
        application { module(stub, requests) }

        val response = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(null, rejectSawResolution, "el rechazo concurrente no encontro la solicitud pendiente")
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado)
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
        val rejected = reject(superActor, pendingId)
        assertEquals(HttpStatusCode.Conflict, rejected.status)
    }

    @Test
    fun anUnknownRequestIs404AndAMalformedIdIs400() = testApplication {
        val stub = Stub()
        application { module(stub, requests()) }

        val missing = approve(superActor, unknownId)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NOT_FOUND", missing.code())
        assertEquals(HttpStatusCode.NotFound, reject(superActor, unknownId).status)

        val malformed = approve(superActor, "no-es-un-uuid")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("VALIDATION", malformed.code())
        assertTrue(stub.rpcBodies().isEmpty())
    }

    @Test
    fun withoutAGatewayTheRoutesFailClosed() = testApplication {
        application { module(Stub(), requests = null) }
        assertEquals(HttpStatusCode.InternalServerError, list(superActor).status)
        assertEquals(HttpStatusCode.InternalServerError, approve(superActor, pendingId).status)
    }

    // ------------------------------------------------------------------------------------------------ rechazar

    @Test
    fun rejectingStoresTheOptionalReasonAndTouchesNoRole() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        val withReason = reject(superActor, pendingId, """{"motivo":"  No pertenece al equipo.  "}""")
        val withoutBody = reject(superActor, pendingWithRoleId)

        assertEquals(HttpStatusCode.OK, withReason.status, withReason.bodyAsText())
        assertEquals("rechazada", withReason.json()["estado"]!!.jsonPrimitive.content)
        assertEquals("No pertenece al equipo.", withReason.json()["motivoResolucion"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, withoutBody.status, withoutBody.bodyAsText())
        assertNull(withoutBody.json()["motivoResolucion"])
        assertTrue(stub.rpcBodies().isEmpty(), "rechazar no concede ni retira roles")
        assertTrue(requests.accountReads.isEmpty(), "rechazar no consulta ni confirma la cuenta")
        // Primero se marca y se audita; la cuenta se borra despues (si el borrado falla, ya consta como rechazada).
        assertEquals(listOf("marcar:rechazada", "borrar", "marcar:rechazada", "borrar"), timeline.toList())
        assertEquals(listOf(applicant, applicantWithRole), requests.deleted.toList(), "rechazar no deja la cuenta creada")
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertEquals(
            listOf(
                ACTION_ACCESS_REQUEST_REJECTED, ACTION_ACCESS_REQUEST_ACCOUNT_DELETED,
                ACTION_ACCESS_REQUEST_REJECTED, ACTION_ACCESS_REQUEST_ACCOUNT_DELETED,
            ),
            stub.audits().map { it["accion"]!!.jsonPrimitive.content },
        )
        // El motivo del rechazo es texto libre sobre una persona: no se copia a la auditoria.
        assertFalse("No pertenece" in stub.audits().toString())
    }

    @Test
    fun aFailedAccountDeletionLeavesTheRequestRejectedAndRejectingAgainFinishesIt() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }

        requests.deleteFailure = IllegalStateException("auth caido")
        val failed = reject(superActor, pendingId)
        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertEquals("rechazada", requests.rows[pendingId]!!.estado, "la decision ya consta aunque el borrado fallara")
        assertTrue(requests.deleted.isEmpty())

        requests.deleteFailure = null
        assertEquals(HttpStatusCode.OK, reject(superActor, pendingId).status)
        assertEquals(listOf(applicant), requests.deleted.toList())
        assertEquals(1, requests.resolveCalls, "repetir el rechazo no vuelve a marcar la solicitud")
    }

    @Test
    fun anAccountThatWasGivenAnAdminRoleInTheMeantimeIsNotDeleted() = testApplication {
        val stub = Stub()
        val requests = requests()
        application { module(stub, requests) }
        stub.rolesOf[applicant] = listOf(roleAdmin)

        val response = reject(superActor, pendingId)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertTrue(requests.deleted.isEmpty(), "nunca se borra una cuenta con rol de administracion")
    }

    @Test
    fun aResolvedRequestCannotBeFlippedAndRepeatingTheSameDecisionIsIdempotent() = testApplication {
        val stub = Stub()
        application { module(stub, requests()) }

        assertEquals(HttpStatusCode.OK, reject(superActor, pendingId).status)
        assertEquals(HttpStatusCode.OK, reject(superActor, pendingId).status)
        val approveRejected = approve(superActor, pendingId)
        assertEquals(HttpStatusCode.Conflict, approveRejected.status)
        assertEquals("CONFLICT", approveRejected.code())
        assertTrue(stub.rpcBodies().isEmpty(), "una rechazada no concede el rol")

        assertEquals(HttpStatusCode.OK, approve(superActor, pendingWithRoleId).status)
        val rejectApproved = reject(superActor, pendingWithRoleId)
        assertEquals(HttpStatusCode.Conflict, rejectApproved.status)
        assertEquals("CONFLICT", rejectApproved.code())
    }

    @Test
    fun anInvalidRejectionBodyIs400AndResolvesNothing() = testApplication {
        val requests = requests()
        application { module(Stub(), requests) }

        assertEquals(HttpStatusCode.BadRequest, reject(superActor, pendingId, """{"motivo":"${"x".repeat(501)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, reject(superActor, pendingId, """{"motivo":42}""").status)
        assertEquals(HttpStatusCode.BadRequest, reject(superActor, pendingId, "[]").status)
        assertEquals(0, requests.resolveCalls)
        assertEquals(HttpStatusCode.OK, reject(superActor, pendingId, """{"motivo":"${"x".repeat(500)}"}""").status)
    }

    // ------------------------------------------------------------------------------------------------ revision #3

    /** L1: las sesiones se purgan justo ANTES del desbaneo; si la purga falla no se desbanea, no hay rol y se revierte (503). */
    @Test
    fun aFailedSessionPurgeAtApprovalFailsClosedWithoutUnbanning() = testApplication {
        val stub = Stub()
        val requests = requests().apply { purgeFailure = SupabaseHttpStatusException(500, "Supabase POST /rest/v1/rpc/admin_purge_user_sessions -> 500 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertEquals("UPSTREAM_UNAVAILABLE", failed.code())
        assertEquals(null, requests.banned[applicant], "ni desbaneada ni tocada")
        assertTrue(stub.rpcBodies().isEmpty(), "sin purga no hay rol")
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertEquals(listOf("marcar:aprobada", "revertir"), timeline.toList())
        val reverted = stub.audits().single { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVAL_REVERTED }
        assertEquals("purga", reverted["despues"]!!.jsonObject["pasoFallido"]!!.jsonPrimitive.content)

        timeline.clear()
        requests.purgeFailure = null
        assertEquals(HttpStatusCode.OK, approve(superActor, pendingId).status)
        assertEquals(listOf("marcar:aprobada", "purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(listOf(applicant), requests.purged.toList())
    }

    /**
     * L2: el rol falla y el RE-BANEO de la reversion tambien. La cuenta queda desbaneada y sin rol: la solicitud NO vuelve a
     * `pendiente` (fuera del camino de reanudacion) sino que se queda `aprobada`, auditada `revertida:false` con el motivo.
     * Repetir la aprobacion la reanuda: purga, desbaneo y rol.
     */
    @Test
    fun aFailedReBanKeepsTheRequestApprovedAndApprovingAgainResumesIt() = testApplication {
        val stub = Stub().apply { rpcStatus = 500 }
        val requests = requests().apply { rebanFailure = SupabaseHttpStatusException(500, "Supabase PUT /auth/v1/admin/users -> 500 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado, "no se revierte: la reanudacion la termina")
        assertEquals(superActor, requests.rows[pendingId]!!.resueltoPor)
        assertEquals(0, requests.revertCalls)
        assertEquals(false, requests.banned[applicant], "el re-baneo fallo")
        assertEquals(emptyList(), stub.rolesOf[applicant])
        val reverted = stub.audits().single { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVAL_REVERTED }.getValue("despues").jsonObject
        assertEquals("false", reverted["revertida"]!!.jsonPrimitive.content)
        assertEquals("false", reverted["rebaneada"]!!.jsonPrimitive.content)
        assertEquals("aprobada", reverted["estado"]!!.jsonPrimitive.content)
        assertEquals("rebaneo_fallido_pendiente_de_reanudar", reverted["motivoNoRevertida"]!!.jsonPrimitive.content)

        timeline.clear()
        stub.rpcStatus = 200
        requests.rebanFailure = null
        val resumed = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, resumed.status, resumed.bodyAsText())
        assertEquals(listOf("purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
        assertEquals(false, requests.banned[applicant])
        val audit = stub.audits().last { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED }
        assertEquals("true", audit["despues"]!!.jsonObject["reanudada"]!!.jsonPrimitive.content)
    }

    /** L1 en la reanudacion: purga antes del desbaneo; si falla, 503 sin desbanear ni rol. */
    @Test
    fun resumingAStuckApprovalPurgesSessionsFirstAndFailsClosedIfThePurgeFails() = testApplication {
        val stub = Stub()
        val stuckId = "77777777-7777-4777-8777-000000000005"
        val requests = InMemoryRequests(request(stuckId, applicant, estado = ADMIN_ACCESS_ESTADO_APROBADA))
            .also { it.onEvent = { event -> timeline += event } }
        requests.purgeFailure = IllegalStateException("Supabase network error on POST /rest/v1/rpc/admin_purge_user_sessions")
        application { module(stub, requests) }

        val failed = approve(superActor, stuckId)

        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertEquals(emptyList(), timeline.toList(), "sin purga no se desbanea")
        assertTrue(stub.rpcBodies().isEmpty())

        requests.purgeFailure = null
        assertEquals(HttpStatusCode.OK, approve(superActor, stuckId).status)
        assertEquals(listOf("purgar", "desbanear", "rol"), timeline.toList())
        assertEquals(listOf(applicant), requests.purged.toList())
    }

    /** Revision #3 (c): la reanudacion repite la doble comprobacion: cuenta inexistente o sin confirmar = 409 y nada mas. */
    @Test
    fun resumingAStuckApprovalRechecksThatTheAuthAccountIsConfirmed() = testApplication {
        val stub = Stub()
        val stuckId = "77777777-7777-4777-8777-000000000005"
        val requests = InMemoryRequests(request(stuckId, applicant, estado = ADMIN_ACCESS_ESTADO_APROBADA))
            .also { it.onEvent = { event -> timeline += event } }
        requests.unconfirmedAccounts += applicant
        application { module(stub, requests) }

        val unconfirmed = approve(superActor, stuckId)
        assertEquals(HttpStatusCode.Conflict, unconfirmed.status, unconfirmed.bodyAsText())
        assertEquals("CONFLICT", unconfirmed.code())

        requests.unconfirmedAccounts.clear()
        requests.missingAccounts += applicant
        assertEquals(HttpStatusCode.Conflict, approve(superActor, stuckId).status)

        assertEquals(listOf(applicant, applicant), requests.accountReads.toList())
        assertEquals(emptyList(), timeline.toList(), "ni purga, ni desbaneo, ni rol")
        assertTrue(stub.rpcBodies().isEmpty())
        assertEquals(emptyList(), stub.rolesOf[applicant])
    }
}
