package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LOOP 3, area KTOR_SEC: MASTER3-API-004 (PATCH de usuario condicionado a la version), MASTER3-OBS-001 (intencion
 * obligatoria ANTES de admin_sync_roles), MASTER3-OBS-002 (23505 de `tickets_grant` = ya auditada; contrapartida de
 * fallo de las intenciones legacy).
 */
class AdminAuditAtomicityLoop3Test {
    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val v1 = "2026-09-30T10:00:00.111111+00:00"
    private val v2 = "2026-09-30T10:00:05.222222+00:00"

    private class Sent(val method: String, val uri: String, val body: String)

    private fun perfil(nombre: String, version: String) =
        """{"usuario_id":"$u1","username":"ada_l","nombre_mostrar":"$nombre","es_invitado":false,"onboarding_completado":true,""" +
            """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05.123456+00:00","actualizado_en":"$version"}"""

    private fun gateway(sent: MutableList<Sent>, responder: (method: String, uri: String) -> V1StubResponse): AdminV1Gateway {
        var lastMethod = ""
        return AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ request: HttpRequest ->
                lastMethod = request.method()
                sent += Sent(request.method(), request.uri().toString(), readRequestBody(request))
            }) { uri -> responder(lastMethod, uri) },
        )
    }

    private fun List<Sent>.audits() = filter { it.method == "POST" && "/rest/v1/auditoria_log" in it.uri }
        .map { Json.parseToJsonElement(it.body).jsonArray.single().jsonObject }

    private fun JsonObject.accion() = this["accion"]!!.jsonPrimitive.content

    // ------------------------------------------------------------------ MASTER3-API-004

    /**
     * A y B leen X. A escribe Y (version v2). B pide Z: su PATCH condicionado a v1 no toca filas; se relee (Y, v2) y se
     * escribe condicionado a v2. La auditoria de B dice Y -> Z (antes: X -> Z, el `antes` rancio).
     */
    @Test
    fun aConcurrentWriteIsDetectedRereadAndTheAuditCarriesTheRealBeforeValue() {
        val sent = CopyOnWriteArrayList<Sent>()
        val reads = AtomicInteger()
        val g = gateway(sent) { method, uri ->
            when {
                method == "GET" && "/rest/v1/perfiles_usuario" in uri ->
                    V1StubResponse(200, if (reads.getAndIncrement() == 0) "[${perfil("X", v1)}]" else "[${perfil("Y", v2)}]")
                method == "PATCH" && "/rest/v1/perfiles_usuario" in uri ->
                    V1StubResponse(200, if ("actualizado_en=eq." in uri && decoded(uri).contains(v2)) "[${perfil("Z", v2)}]" else "[]")
                else -> V1StubResponse(200, "[]")
            }
        }
        val saved = g.updateUsuario(u1, "Z")!!
        assertEquals("Z", saved.fullName)
        val patches = sent.filter { it.method == "PATCH" }
        assertEquals(2, patches.size)
        assertTrue(decoded(patches[0].uri).endsWith("actualizado_en=eq.$v1"), patches[0].uri)
        assertTrue(decoded(patches[1].uri).endsWith("actualizado_en=eq.$v2"), patches[1].uri)
        assertTrue("%2B00%3A00" in patches[0].uri, "el '+' de la zona va codificado (sin codificar seria un espacio): ${patches[0].uri}")
        val audit = sent.audits().single()
        assertEquals("usuario_actualizado", audit.accion())
        assertEquals("Y", audit["antes"]!!.jsonObject["nombreMostrar"]!!.jsonPrimitive.content, "el antes REAL")
        assertEquals("Z", audit["despues"]!!.jsonObject["nombreMostrar"]!!.jsonPrimitive.content)
    }

    @Test
    fun ifTheConcurrentWriterAlreadySetTheSameValueThereIsNoSecondWriteNorAudit() {
        val sent = CopyOnWriteArrayList<Sent>()
        val reads = AtomicInteger()
        val g = gateway(sent) { method, uri ->
            when {
                method == "GET" && "/rest/v1/perfiles_usuario" in uri ->
                    V1StubResponse(200, if (reads.getAndIncrement() == 0) "[${perfil("X", v1)}]" else "[${perfil("Z", v2)}]")
                method == "PATCH" -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, "[]")
            }
        }
        assertEquals("Z", g.updateUsuario(u1, "Z")!!.fullName)
        assertEquals(1, sent.count { it.method == "PATCH" })
        assertTrue(sent.audits().isEmpty(), "el atajo de valor identico se evalua sobre la relectura")
    }

    @Test
    fun aRowThatKeepsChangingIsBoundedAndNeverAuditedAsWritten() {
        val sent = CopyOnWriteArrayList<Sent>()
        val reads = AtomicInteger()
        val g = gateway(sent) { method, uri ->
            when {
                method == "GET" && "/rest/v1/perfiles_usuario" in uri ->
                    V1StubResponse(200, "[${perfil("X${reads.getAndIncrement()}", "2026-09-30T10:00:0${reads.get()}.000000+00:00")}]")
                else -> V1StubResponse(200, "[]")
            }
        }
        assertFailsWith<IllegalStateException> { g.updateUsuario(u1, "Z") }
        assertEquals(ADMIN_USER_UPDATE_ATTEMPTS, sent.count { it.method == "PATCH" })
        assertTrue(sent.audits().isEmpty())
    }

    // ------------------------------------------------------------------ MASTER3-OBS-001

    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val plainUser = "5a5a5a5a-0000-4000-8000-000000000003"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"

    private fun rolesResponder(
        rpc: () -> V1StubResponse = { V1StubResponse(204, "") },
        audit: (JsonObject) -> V1StubResponse = { V1StubResponse(201, "") },
    ): (String, String) -> V1StubResponse {
        val rolesOf = mapOf(superActor to listOf(roleSuper), plainUser to listOf(roleUser))
        return { _, uri ->
            when {
                "/rest/v1/roles_app" in uri -> V1StubResponse(
                    200,
                    """[{"id":"$roleSuper","codigo":"superadmin","nombre":"S"},{"id":"$roleAdmin","codigo":"admin","nombre":"A"},""" +
                        """{"id":"$roleUser","codigo":"usuario","nombre":"U"}]""",
                )
                "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq." in uri -> {
                    val id = uri.substringAfter("usuario_id=eq.").substringBefore('&')
                    V1StubResponse(200, rolesOf[id].orEmpty().joinToString(",", "[", "]") { """{"usuario_id":"$id","rol_id":"$it"}""" })
                }
                "/rest/v1/rpc/admin_sync_roles" in uri -> rpc()
                "/rest/v1/auditoria_log" in uri -> audit(JsonObject(emptyMap()))
                else -> V1StubResponse(200, "[]")
            }
        }
    }

    private fun Application.adminModule(g: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminV1Routes(g)
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.grantAdmin() =
        client.post("/api/v1/admin/usuarios/$plainUser/roles") {
            header(HttpHeaders.Authorization, "Bearer $superActor")
            contentType(ContentType.Application.Json)
            setBody("""{"roleIds":["$roleUser","$roleAdmin"]}""")
        }

    @Test
    fun theIntentRowWithActorAndRequestIdIsWrittenBeforeTheRpc() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = gateway(sent, rolesResponder())
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val order = sent.map { if ("/rest/v1/auditoria_log" in it.uri) "audit" else if ("admin_sync_roles" in it.uri) "rpc" else null }.filterNotNull()
        assertEquals(listOf("audit", "rpc", "audit"), order)
        val (intent, outcome) = sent.audits()
        assertEquals(ACTION_ROLES_SYNC_INTENT, intent.accion())
        assertEquals(superActor, intent["usuario_id"]!!.jsonPrimitive.content, "quien concede queda en la intencion")
        assertEquals(listOf(roleUser), intent["antes"]!!.jsonObject["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(roleUser, roleAdmin), intent["despues"]!!.jsonObject["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val requestId = response.headers["X-Request-Id"]
        assertEquals(requestId, intent["despues"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
        assertEquals(ACTION_ROLES_SYNCED, outcome.accion())
        assertEquals(requestId, outcome["despues"]!!.jsonObject["requestId"]!!.jsonPrimitive.content, "intencion y resultado correlacionados")
    }

    @Test
    fun aFailedOutcomeAuditNoLongerTurnsACompletedGrantIntoAnError() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val audits = AtomicInteger()
        // La intencion entra; el resultado falla.
        val g = gateway(sent, rolesResponder(audit = { if (audits.incrementAndGet() == 1) V1StubResponse(201, "") else V1StubResponse(500, """{"message":"down"}""") }))
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, "la concesion ya se hizo y la intencion la registra: ${response.bodyAsText()}")
        assertEquals(1, sent.count { "admin_sync_roles" in it.uri })
    }

    @Test
    fun aRejectedSyncWritesAFailureCounterpartAndPropagatesTheDomainError() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = gateway(sent, rolesResponder(rpc = { V1StubResponse(400, """{"code":"P0001","message":"ROLE_SYNC_LAST_SUPERADMIN"}""") }))
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val rows = sent.audits()
        assertEquals(listOf(ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNC_FAILED), rows.map { it.accion() })
        assertEquals("rechazada", rows.last()["despues"]!!.jsonObject["resultado"]!!.jsonPrimitive.content)
        assertEquals("ROLE_SYNC_LAST_SUPERADMIN", rows.last()["despues"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER3-OBS-002 (tickets_grant)

    private val tx = "7c7c7c7c-0000-4000-8000-000000000002"

    @Test
    fun aUniqueViolationOnTheTicketsGrantRowMeansAlreadyAuditedNotAFailure() {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = gateway(sent) { method, uri ->
            when {
                "/rest/v1/rpc/admin_grant_tickets" in uri -> V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
                // La comprobacion previa no ve la fila (carrera): el INSERT choca con el indice unico parcial.
                method == "GET" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(200, "[]")
                method == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(
                    409,
                    """{"code":"23505","message":"duplicate key value violates unique constraint \"auditoria_log_tickets_grant_tx_uq\""}""",
                )
                else -> V1StubResponse(200, "[]")
            }
        }
        assertEquals(15, g.grantTickets(u1, 5, "premio", "ajuste", "key-1"), "la concesion ya estaba auditada: sin error")
    }

    @Test
    fun anyOtherTicketsGrantAuditFailureStillFailsTheRequest() {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = gateway(sent) { method, uri ->
            when {
                "/rest/v1/rpc/admin_grant_tickets" in uri -> V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
                method == "GET" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(200, "[]")
                method == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(500, """{"message":"down"}""")
                else -> V1StubResponse(200, "[]")
            }
        }
        assertFailsWith<IllegalStateException> { g.grantTickets(u1, 5, "premio", "ajuste", "key-1") }
    }

    @Test
    fun aUniqueViolationOnAnOrdinaryAuditIsStillAFailureWhenRequired() {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = gateway(sent) { _, uri ->
            if ("/rest/v1/auditoria_log" in uri) V1StubResponse(409, """{"code":"23505","message":"dup"}""") else V1StubResponse(200, "[]")
        }
        assertFailsWith<IllegalStateException> { g.registrarAuditoria("otra", "t", u1, required = true) }
    }

    // ------------------------------------------------------------------ MASTER3-OBS-002 (intenciones legacy)

    private class LegacyFake(val deleteFailure: Exception? = null) : OposiBotsUserAdminGateway {
        private fun dto(id: Int) = OposiBotsUserAdminDto(id, "55555555-5555-4555-8555-555555555555", "x@example.com", "N", "A", "u", "Celador", true, null, "t", "t")
        override suspend fun listUsers(limit: Int, offset: Int, search: String?) = listOf(dto(7))
        override suspend fun getUser(id: Int) = if (id == 7) dto(id) else null
        override suspend fun createUser(request: OposiBotsUserAdminCreateRequest) = error("upstream down")
        override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest) = null
        override suspend fun deleteUser(id: Int): Boolean = deleteFailure?.let { throw it } ?: true
    }

    private fun Application.legacyModule(fake: LegacyFake, audits: MutableList<Pair<String, JsonObject>>) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                oposiBotsUserAdminRoutes(
                    fake,
                    OposiBotsUserAdminPolicy(isAdministrative = { false }, audit = { accion, _, detalle -> audits += accion to detalle }),
                )
            }
        }
    }

    @Test
    fun everyLegacyIntentEndsInAnOutcomeOrAFailureCounterpart() = testApplication {
        val audits = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        application { legacyModule(LegacyFake(deleteFailure = IllegalStateException("socket reset")), audits) }
        val actor = "44444444-4444-4444-8444-444444444444"

        val deleted = client.delete("/api/v1/admin/oposibots/usuarios/7") { header(HttpHeaders.Authorization, "Bearer $actor") }
        assertTrue(deleted.status.value >= 500, "${deleted.status}")
        val updated = client.patch("/api/v1/admin/oposibots/usuarios/7") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody("""{"nombre":"N2"}""")
        }
        assertEquals(HttpStatusCode.NotFound, updated.status)
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"n@example.com","nombre":"N","apellidos":"A","especialidadRegistro":"Celador"}""")
        }
        assertTrue(created.status.value >= 500, "${created.status}")

        assertEquals(
            listOf(
                ACTION_DELETE_INTENT, ACTION_DELETE_FAILED,
                ACTION_UPDATE_INTENT, ACTION_UPDATE_FAILED,
                ACTION_CREATE_INTENT, ACTION_CREATE_FAILED,
            ),
            audits.map { it.first },
        )
        val resultados = audits.filter { it.first.endsWith("_fallida") }.map { it.second["resultado"]!!.jsonPrimitive.content }
        assertEquals(listOf("desconocido", "rechazada", "desconocido"), resultados)
        // Cada contrapartida lleva el requestId de su intencion.
        audits.chunked(2).forEach { (intent, failure) ->
            assertEquals(intent.second["requestId"], failure.second["requestId"], "$intent / $failure")
        }
    }

    private fun decoded(uri: String) = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8)
}
