package com.oposicontrol.backend

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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER-AUTH-003: D1-26 exige SUPERADMIN para conceder o retirar `admin`/`superadmin` (profiles.yaml), antes de
 * llamar a `admin_sync_roles`. MASTER-OBS-002: la auditoria lleva el actor (sub del JWT), `antes`/`despues`, y en
 * un cambio privilegiado un fallo al auditar falla la peticion.
 */
class AdminRoleSyncSuperadminAndAuditTest {
    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val adminActor = "5a5a5a5a-0000-4000-8000-000000000002"
    private val plainUser = "5a5a5a5a-0000-4000-8000-000000000003"
    private val otherAdmin = "5a5a5a5a-0000-4000-8000-000000000004"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"
    private val roleMod = "aaaaaaaa-0000-4000-8000-00000000000d"

    private val rolesOf = mapOf(
        superActor to listOf(roleSuper),
        adminActor to listOf(roleAdmin),
        plainUser to listOf(roleUser),
        otherAdmin to listOf(roleAdmin),
    )

    private class Stub {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        var auditStatus = 201
    }

    private fun responder(stub: Stub): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/roles_app" in uri -> V1StubResponse(
                200,
                """[{"id":"$roleSuper","codigo":"superadmin","nombre":"Superadmin"},{"id":"$roleAdmin","codigo":" ADMIN ","nombre":"Admin"},""" +
                    """{"id":"$roleUser","codigo":"usuario","nombre":"Usuario"},{"id":"$roleMod","codigo":"moderador","nombre":"Moderador"}]""",
            )
            "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq." in uri -> {
                val id = uri.substringAfter("usuario_id=eq.").substringBefore('&')
                V1StubResponse(200, rolesOf[id].orEmpty().joinToString(",", "[", "]") { """{"usuario_id":"$id","rol_id":"$it"}""" })
            }
            "/rest/v1/rpc/admin_sync_roles" in uri -> V1StubResponse(204, "")
            "/rest/v1/auditoria_log" in uri -> V1StubResponse(stub.auditStatus, if (stub.auditStatus >= 400) """{"message":"audit down"}""" else "")
            else -> V1StubResponse(200, "[]")
        }
    }

    private fun Application.module(stub: Stub) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } }
        }
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ stub.sent += it }, responder(stub)),
        )
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminV1Routes(gateway)
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.syncRoles(actor: String, target: String, vararg roles: String): HttpResponse =
        client.post("/api/v1/admin/usuarios/$target/roles") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody(roles.joinToString(",", """{"roleIds":[""", "]}") { "\"$it\"" })
        }

    private fun Stub.rpcCalls() = sent.count { "/rest/v1/rpc/admin_sync_roles" in it.uri().toString() }
    private fun Stub.auditBodies() = sent.filter { "/rest/v1/auditoria_log" in it.uri().toString() }
        .map { Json.parseToJsonElement(readRequestBody(it)).jsonArray.single().jsonObject }

    private suspend fun HttpResponse.code(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    // ---------------------------------------------------------------- MASTER-AUTH-003

    @Test
    fun aPlainAdminCannotGrantSuperadminToSelfAndTheRpcIsNeverCalled() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val response = syncRoles(adminActor, adminActor, roleAdmin, roleSuper)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("SUPERADMIN_REQUIRED", response.code())
        assertEquals(0, stub.rpcCalls())
        assertTrue(stub.auditBodies().isEmpty())
    }

    @Test
    fun aPlainAdminCannotRemoveAdminFromAnotherUserNorGrantAdmin() = testApplication {
        val stub = Stub()
        application { module(stub) }
        assertEquals(HttpStatusCode.Forbidden, syncRoles(adminActor, otherAdmin, roleUser).status)
        assertEquals(HttpStatusCode.Forbidden, syncRoles(adminActor, plainUser, roleUser, roleAdmin).status)
        assertEquals(0, stub.rpcCalls())
    }

    @Test
    fun aPlainAdminMayStillChangeOnlyModeratorAndUserRoles() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val response = syncRoles(adminActor, plainUser, roleUser, roleMod)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, stub.rpcCalls())
        // Mantener el rol admin que ya tenia otro usuario tampoco exige superadmin.
        assertEquals(HttpStatusCode.OK, syncRoles(adminActor, otherAdmin, roleAdmin, roleMod).status)
    }

    @Test
    fun aSuperadminMayGrantAndRemoveAdministrativeRoles() = testApplication {
        val stub = Stub()
        application { module(stub) }
        assertEquals(HttpStatusCode.OK, syncRoles(superActor, plainUser, roleUser, roleAdmin).status)
        assertEquals(HttpStatusCode.OK, syncRoles(superActor, otherAdmin, roleUser).status)
        assertEquals(2, stub.rpcCalls())
    }

    @Test
    fun withoutAPersonActorAnAdministrativeChangeFailsClosed() {
        val stub = Stub()
        val gateway = AdminV1Gateway.forTesting("https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true }, V1StubHttpClient({ stub.sent += it }, responder(stub)))
        val failure = runCatching { gateway.syncRoles(plainUser, listOf(roleAdmin), actorId = null) }.exceptionOrNull()
        assertEquals("SUPERADMIN_REQUIRED", (failure as BackendDomainException).errorCode)
        assertEquals(0, stub.rpcCalls())
    }

    @Test
    fun theDecisionIsTheSymmetricDifferenceRestrictedToAdministrativeRoles() {
        assertTrue(RoleSyncInvariants.changesAdministrativeRoles(setOf("usuario"), setOf("usuario", "admin")))
        assertTrue(RoleSyncInvariants.changesAdministrativeRoles(setOf("admin"), setOf("usuario")))
        assertTrue(RoleSyncInvariants.changesAdministrativeRoles(setOf("admin"), setOf("superadmin")))
        assertFalse(RoleSyncInvariants.changesAdministrativeRoles(setOf("admin", "usuario"), setOf("admin", "moderador")))
        assertFalse(RoleSyncInvariants.changesAdministrativeRoles(setOf("usuario"), setOf("moderador")))
    }

    // ---------------------------------------------------------------- MASTER-OBS-002

    @Test
    fun theRoleSyncAuditRowCarriesTheActorAndTheBeforeAndAfterRoles() = testApplication {
        val stub = Stub()
        application { module(stub) }
        assertEquals(HttpStatusCode.OK, syncRoles(superActor, plainUser, roleUser, roleAdmin).status)
        // MASTER3-OBS-001 (cambio DELIBERADO): ahora hay DOS filas, intencion (antes de la RPC) y resultado.
        val rows = stub.auditBodies()
        assertEquals(listOf(ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNCED), rows.map { it["accion"]!!.jsonPrimitive.content })
        assertTrue(rows.all { it["usuario_id"]!!.jsonPrimitive.content == superActor }, "ambas con el actor: $rows")
        val row = rows.last()
        assertEquals(superActor, row["usuario_id"]!!.jsonPrimitive.content)
        assertEquals("roles_sincronizados", row["accion"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleUser), row["antes"]!!.jsonObject["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(roleUser, roleAdmin), row["despues"]!!.jsonObject["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aPrivilegedChangeWhoseAuditCannotBeWrittenFailsTheRequest() = testApplication {
        val stub = Stub().apply { auditStatus = 500 }
        application { module(stub) }
        val response = syncRoles(superActor, plainUser, roleUser, roleAdmin)
        // MASTER5-API-004 (cambio DELIBERADO): antes 502 UPSTREAM_ERROR, fuera del catalogo congelado de D1-26; una
        // auditoria obligatoria que no se escribe es un fallo del servidor: 500 INTERNAL (declarado).
        assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
        assertEquals("INTERNAL", response.code())
        // MASTER3-OBS-001: la fila obligatoria es la de INTENCION y va antes: sin ella los roles NO cambian.
        assertEquals(0, stub.rpcCalls(), "sin auditoria de intencion no se llama a admin_sync_roles")
    }

    @Test
    fun theWarningReasonIsKeptInTheAuditRow() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val ok = warn(adminActor, """{"targetUserId":"$plainUser","actionType":"advertencia","reason":"spam reiterado"}""")
        assertEquals(HttpStatusCode.Created, ok.status)
        val row = stub.auditBodies().single()
        assertEquals(adminActor, row["usuario_id"]!!.jsonPrimitive.content)
        assertEquals("advertencia_usuario", row["accion"]!!.jsonPrimitive.content)
        assertEquals("spam reiterado", row["despues"]!!.jsonObject["motivo"]!!.jsonPrimitive.content)
    }

    // MASTER7-API-007 (cambio DELIBERADO): antes `aLowRiskActionStaysBestEffort...` fijaba que una advertencia
    // respondiera 201 con la auditoria caida (defecto: la advertencia ES la fila de auditoria).
    @Test
    fun aWarningWhoseAuditCannotBeWrittenFailsInsteadOfAnswering201() = testApplication {
        val stub = Stub().apply { auditStatus = 500 }
        application { module(stub) }
        val failing = warn(adminActor, """{"targetUserId":"$plainUser","actionType":"advertencia","reason":"x"}""")
        assertEquals(HttpStatusCode.InternalServerError, failing.status, failing.bodyAsText())
    }

    @Test
    fun anUnknownOrInjectedActionTypeIs400AndNothingIsAudited() = testApplication {
        val stub = Stub()
        application { module(stub) }
        for (bad in listOf("x\nevent=FORGED", "borrar", "")) {
            val r = warn(adminActor, """{"targetUserId":"$plainUser","actionType":"$bad","reason":"x"}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$bad: ${r.bodyAsText()}")
        }
        assertTrue(stub.auditBodies().isEmpty(), "ninguna fila de auditoria")
        // RC-01 (cambio DELIBERADO): antes `suspension` y `ban` respondian 201 escribiendo solo una fila de auditoria, sin
        // ningun efecto sobre la cuenta. Ahora solo `advertencia` (efecto definido) es 201; los tipos sin aplicacion son
        // 422 MODERATION_ACTION_NOT_ENFORCED y tampoco auditan nada.
        assertEquals(HttpStatusCode.Created, warn(adminActor, """{"targetUserId":"$plainUser","actionType":"advertencia","reason":"x"}""").status)
        for (notEnforced in listOf("suspension", "ban")) {
            val r = warn(adminActor, """{"targetUserId":"$plainUser","actionType":"$notEnforced","reason":"x"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status, notEnforced)
            assertTrue("MODERATION_ACTION_NOT_ENFORCED" in r.bodyAsText(), r.bodyAsText())
        }
        assertEquals(1, stub.auditBodies().size, "solo la advertencia deja fila")
    }

    @Test
    fun anOverlongWarningReasonIs400() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val tooLong = "a".repeat(1_001)
        // RC-01: `ban` ya no es un tipo aceptado (422); el limite del motivo se fija con la advertencia.
        assertEquals(HttpStatusCode.BadRequest, warn(adminActor, """{"targetUserId":"$plainUser","actionType":"advertencia","reason":"$tooLong"}""").status)
        assertEquals(HttpStatusCode.Created, warn(adminActor, """{"targetUserId":"$plainUser","actionType":"advertencia","reason":"${"a".repeat(1_000)}"}""").status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.warn(actor: String, json: String): HttpResponse =
        client.post("/api/v1/admin/moderacion/actions") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    @Test
    fun theActorTravelsIntoTheIoPoolAndIsNeverFabricatedOutsideARequest() {
        val stub = Stub()
        val gateway = AdminV1Gateway.forTesting("https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true }, V1StubHttpClient({ stub.sent += it }, responder(stub)))
        runBlocking {
            withContext(AdminAuditActor.contextElement(superActor)) {
                withUpstreamIo { gateway.registrarAuditoria("prueba", "examenes", plainUser) }
            }
        }
        gateway.registrarAuditoria("sin_actor", "examenes", plainUser)
        val rows = stub.auditBodies()
        assertEquals(superActor, rows[0]["usuario_id"]!!.jsonPrimitive.content)
        assertNull(rows[1]["usuario_id"], "fuera de una peticion no hay actor: ${rows[1]}")
        assertNull(AdminAuditActor.current())
    }
}
