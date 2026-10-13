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

    private class InMemoryRequests(vararg seed: AdminAccessRequestDto) : AdminAccessRequestGateway {
        val rows = ConcurrentHashMap(seed.associateBy { it.id })
        val listCalls = CopyOnWriteArrayList<Triple<String?, Int, Int>>()
        var resolveCalls = 0

        /** Cuentas de Auth cuyo correo se ha confirmado (lo que haria `PUT /auth/v1/admin/users/{id}`). */
        val confirmed = CopyOnWriteArrayList<String>()
        var confirmFailure: Exception? = null
        var resolveFailure: Exception? = null
        var onEvent: (String) -> Unit = {}

        override suspend fun confirmEmail(usuarioId: String) {
            confirmFailure?.let { throw it }
            onEvent("confirmar")
            confirmed += usuarioId
        }

        override suspend fun submit(submission: AdminAccessSubmission) = error("no interviene")

        override suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto> {
            listCalls += Triple(estado, limit, offset)
            return rows.values.filter { estado == null || it.estado == estado }.sortedBy { it.id }
        }

        override suspend fun find(id: String) = rows[id]

        override suspend fun resolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto? {
            resolveCalls++
            resolveFailure?.let { throw it }
            onEvent("marcar:$estado")
            val current = rows[id]?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_PENDIENTE } ?: return null
            return current.copy(estado = estado, resueltoEn = "2026-10-13T10:00:00Z", resueltoPor = actorId, motivoResolucion = motivo)
                .also { rows[id] = it }
        }
    }

    private fun request(id: String, usuarioId: String, estado: String = ADMIN_ACCESS_ESTADO_PENDIENTE) = AdminAccessRequestDto(
        id = id,
        usuarioId = usuarioId,
        nombre = "Ada Lovelace",
        email = "ada@example.com",
        motivo = "Coordino el equipo de contenido.",
        estado = estado,
        creadoEn = "2026-10-13T09:00:00Z",
    )

    /** Orden real de los pasos de una aprobacion: `confirmar`, `rol` (la RPC auditada) y `marcar:<estado>`. */
    private val timeline = CopyOnWriteArrayList<String>()

    private fun requests() = InMemoryRequests(request(pendingId, applicant), request(pendingWithRoleId, applicantWithRole))
        .also { it.onEvent = { event -> timeline += event } }

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
        assertEquals(listOf(pendingId, pendingWithRoleId), items.map { it["id"]!!.jsonPrimitive.content })
        val first = items.first()
        assertEquals("Ada Lovelace", first["nombre"]!!.jsonPrimitive.content)
        assertEquals("ada@example.com", first["email"]!!.jsonPrimitive.content)
        assertEquals("Coordino el equipo de contenido.", first["motivo"]!!.jsonPrimitive.content)
        assertEquals("pendiente", first["estado"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T09:00:00Z", first["creadoEn"]!!.jsonPrimitive.content)
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
        // El orden: primero se confirma el correo de ESA cuenta, despues el rol y al final se marca la solicitud.
        assertEquals(listOf("confirmar", "rol", "marcar:aprobada"), timeline.toList())
        assertEquals(listOf(applicant), requests.confirmed.toList())
        // Auditoria: la confirmacion, intencion y resultado de la sincronizacion de roles y la resolucion, todas con el actor.
        val audits = stub.audits()
        assertEquals(
            listOf(ACTION_ACCESS_REQUEST_EMAIL_CONFIRMED, ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNCED, ACTION_ACCESS_REQUEST_APPROVED),
            audits.map { it["accion"]!!.jsonPrimitive.content },
        )
        assertTrue(audits.all { it["usuario_id"]!!.jsonPrimitive.content == superActor }, "$audits")
        val confirmation = audits.first()
        assertEquals(pendingId, confirmation["registro_id"]!!.jsonPrimitive.content)
        assertEquals(applicant, confirmation["despues"]!!.jsonObject["usuarioId"]!!.jsonPrimitive.content)
        assertEquals("true", confirmation["despues"]!!.jsonObject["emailConfirmado"]!!.jsonPrimitive.content)
        assertFalse("ada@example.com" in confirmation.toString() || "Ada Lovelace" in confirmation.toString())
        val resolution = audits.last()
        assertEquals("true", resolution["despues"]!!.jsonObject["emailConfirmado"]!!.jsonPrimitive.content)
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
        assertEquals(1, requests.confirmed.size, "una ya aprobada no vuelve a tocar la cuenta")
        assertEquals(1, stub.audits().count { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })
    }

    @Test
    fun ifTheConfirmationFailsNothingElseHappensAndARetryCompletesIt() = testApplication {
        val stub = Stub()
        val requests = requests().apply { confirmFailure = SupabaseHttpStatusException(502, "Supabase PUT /auth/v1/admin/users -> 502 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertTrue(requests.confirmed.isEmpty())
        assertTrue(stub.rpcBodies().isEmpty(), "sin confirmar la cuenta no se concede ningun rol")
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertEquals(0, requests.resolveCalls)
        assertTrue(stub.audits().isEmpty())

        requests.confirmFailure = null
        assertEquals(HttpStatusCode.OK, approve(superActor, pendingId).status)
        assertEquals(listOf("confirmar", "rol", "marcar:aprobada"), timeline.toList())
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado)
    }

    @Test
    fun ifTheRoleSyncFailsTheRequestStaysPendingAndCanBeRetried() = testApplication {
        val stub = Stub().apply { rpcStatus = 500 }
        val requests = requests()
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)
        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertEquals(0, requests.resolveCalls)
        // La cuenta ya quedo confirmada (paso 1) y queda auditado, pero sin rol y con la solicitud pendiente.
        assertEquals(listOf(applicant), requests.confirmed.toList())
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertEquals(ACTION_ACCESS_REQUEST_EMAIL_CONFIRMED, stub.audits().first()["accion"]!!.jsonPrimitive.content)
        assertFalse(stub.audits().any { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })

        stub.rpcStatus = 200
        assertEquals(HttpStatusCode.OK, approve(superActor, pendingId).status)
        assertEquals(ADMIN_ACCESS_ESTADO_APROBADA, requests.rows[pendingId]!!.estado)
        // El reintento repite los tres pasos; confirmar otra vez la misma cuenta es inocuo.
        assertEquals(listOf("confirmar", "confirmar", "rol", "marcar:aprobada"), timeline.toList())
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
    }

    @Test
    fun ifMarkingTheRequestFailsARetryDoesNotSyncRolesAgainAndCompletesIt() = testApplication {
        val stub = Stub()
        val requests = requests().apply { resolveFailure = SupabaseHttpStatusException(503, "Supabase PATCH /rest/v1/solicitudes_acceso_admin -> 503 (sin codigo)") }
        application { module(stub, requests) }

        val failed = approve(superActor, pendingId)

        assertTrue(failed.status.value >= 500, failed.bodyAsText())
        assertEquals(ADMIN_ACCESS_ESTADO_PENDIENTE, requests.rows[pendingId]!!.estado)
        assertEquals(listOf(roleAdmin), stub.rolesOf[applicant])
        assertFalse(stub.audits().any { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })

        requests.resolveFailure = null
        val retried = approve(superActor, pendingId)

        assertEquals(HttpStatusCode.OK, retried.status, retried.bodyAsText())
        assertEquals("aprobada", retried.json()["estado"]!!.jsonPrimitive.content)
        assertEquals(1, stub.rpcBodies().size, "el rol ya estaba: el reintento no vuelve a sincronizar")
        assertEquals(listOf("confirmar", "rol", "confirmar", "marcar:aprobada"), timeline.toList())
        assertEquals(1, stub.audits().count { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_APPROVED })
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
        assertTrue(requests.confirmed.isEmpty(), "rechazar no confirma la cuenta: sigue sin poder iniciar sesion")
        assertEquals(listOf("marcar:rechazada", "marcar:rechazada"), timeline.toList())
        assertEquals(emptyList(), stub.rolesOf[applicant])
        assertEquals(
            listOf(ACTION_ACCESS_REQUEST_REJECTED, ACTION_ACCESS_REQUEST_REJECTED),
            stub.audits().map { it["accion"]!!.jsonPrimitive.content },
        )
        // El motivo del rechazo es texto libre sobre una persona: no se copia a la auditoria.
        assertFalse("No pertenece" in stub.audits().toString())
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
}
