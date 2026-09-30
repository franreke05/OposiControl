package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MASTER-AUTH-002: el panel exige rol admin Y cuenta `activa` (deny-by-default).
 * MASTER-AUTH-001: la superficie legacy `/api/v1/admin/oposibots/usuarios` no borra la propia cuenta ni cuentas de
 * administración, audita altas y bajas y va marcada como deprecada (BOT-ADMIN-001/002).
 */
class AdminActiveAccountAndLegacyUserAdminTest {
    private val adminId = "11111111-1111-4111-8111-111111111111"

    private fun gateway(estado: String?, profileStatus: Int = 200, sent: MutableList<HttpRequest> = mutableListOf()) =
        AppV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ sent += it }) { uri ->
                when {
                    "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, """[{"rol_id":"r","roles_app":{"codigo":"admin"}}]""")
                    "/rest/v1/perfiles_usuario" in uri && profileStatus != 200 -> V1StubResponse(profileStatus, """{"message":"down"}""")
                    "/rest/v1/perfiles_usuario" in uri ->
                        V1StubResponse(200, if (estado == null) "[]" else """[{"estado_cuenta":"$estado"}]""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )

    // ---------------------------------------------------------------- MASTER-AUTH-002

    @Test
    fun anAdminRoleIsNotEnoughTheAccountMustBeExplicitlyActive() {
        assertTrue(gateway("activa").hasActiveAdminRole(adminId))
        listOf("bloqueada", "eliminacion_pendiente", "otro", null).forEach { estado ->
            val g = gateway(estado)
            assertTrue(g.hasAdminRole(adminId), "el rol sigue ahi")
            assertFalse(g.hasActiveAdminRole(adminId), "estado $estado")
        }
    }

    @Test
    fun anUnreadableAccountStatusIsAFailureNotADenial() {
        assertFailsWith<IllegalStateException> { gateway("activa", profileStatus = 500).hasActiveAdminRole(adminId) }
    }

    @Test
    fun theStatusDecisionIsCachedLikeTheRoleOne() {
        val sent = mutableListOf<HttpRequest>()
        val g = gateway("activa", sent = sent)
        repeat(3) { assertTrue(g.hasActiveAdminRole(adminId)) }
        assertEquals(1, sent.count { "/rest/v1/perfiles_usuario" in it.uri().toString() })
    }

    @Test
    fun aBlockedOrDeletionPendingAdminGets403ThroughTheRealAuthorizationPlugin() {
        mapOf("bloqueada" to HttpStatusCode.Forbidden, "eliminacion_pendiente" to HttpStatusCode.Forbidden, "activa" to HttpStatusCode.OK)
            .forEach { (estado, expected) ->
                testApplication {
                    val g = gateway(estado)
                    application {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                        installRequestCorrelation()
                        installBackendStatusPages()
                        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
                        routing {
                            authenticate("gateway-auth") {
                                install(AdminAuthorizationPlugin) {
                                    this.appEnv = "prod"
                                    this.hasAdminRole = { g.hasActiveAdminRole(it) }
                                }
                                get("/api/v1/admin/probe") { call.respond(HttpStatusCode.OK) }
                            }
                        }
                    }
                    val response = client.get("/api/v1/admin/probe") { header(HttpHeaders.Authorization, "Bearer $adminId") }
                    assertEquals(expected, response.status, "$estado: ${response.bodyAsText()}")
                }
            }
    }

    // ---------------------------------------------------------------- MASTER-AUTH-001

    private val adminAuth = "22222222-2222-4222-8222-222222222222"
    private val plainAuth = "33333333-3333-4333-8333-333333333333"

    private class LegacyFake(val authIds: Map<Int, String?>) : OposiBotsUserAdminGateway {
        val deleted = CopyOnWriteArrayList<Int>()
        private fun dto(id: Int) = OposiBotsUserAdminDto(id, authIds[id], "x@example.com", "N", "A", "u$id", "Celador", true, null, "t", "t")
        override suspend fun listUsers(limit: Int, offset: Int, search: String?) = authIds.keys.map(::dto)
        override suspend fun getUser(id: Int) = if (id in authIds && id !in deleted) dto(id) else null
        override suspend fun createUser(request: OposiBotsUserAdminCreateRequest) = dto(99)
        override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest) = getUser(id)
        override suspend fun deleteUser(id: Int): Boolean = if (getUser(id) != null) { deleted += id; true } else false
    }

    private fun Application.legacyModule(fake: LegacyFake, audits: MutableList<Pair<String, String?>>, auditFails: Boolean = false) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                oposiBotsUserAdminRoutes(
                    fake,
                    OposiBotsUserAdminPolicy(
                        isAdministrative = { it == adminAuth },
                        audit = { accion, authUserId, _: JsonObject ->
                            if (auditFails) error("audit down")
                            audits += accion to authUserId
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun theLegacySurfaceNeverDeletesTheCallersOwnAccountNorAnAdministrativeAccount() = testApplication {
        val fake = LegacyFake(mapOf(1 to adminAuth, 2 to plainAuth, 3 to null))
        val audits = CopyOnWriteArrayList<Pair<String, String?>>()
        application { legacyModule(fake, audits) }

        val self = client.delete("/api/v1/admin/oposibots/usuarios/2") { header(HttpHeaders.Authorization, "Bearer $plainAuth") }
        assertEquals(HttpStatusCode.Forbidden, self.status)
        val admin = client.delete("/api/v1/admin/oposibots/usuarios/1") { header(HttpHeaders.Authorization, "Bearer $plainAuth") }
        assertEquals(HttpStatusCode.Forbidden, admin.status)
        assertTrue(fake.deleted.isEmpty(), "nada se borro")
        assertTrue(audits.isEmpty())

        val ok = client.delete("/api/v1/admin/oposibots/usuarios/2") { header(HttpHeaders.Authorization, "Bearer $adminAuth") }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertEquals(listOf(2), fake.deleted.toList())
        assertEquals(listOf("oposibots_usuario_eliminado" to plainAuth), audits.toList())
    }

    @Test
    fun creationsAreAuditedAndAFailedAuditFailsTheRequest() = testApplication {
        val fake = LegacyFake(mapOf(1 to adminAuth))
        val audits = CopyOnWriteArrayList<Pair<String, String?>>()
        application { legacyModule(fake, audits) }
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            header(HttpHeaders.Authorization, "Bearer $adminAuth")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"n@example.com","nombre":"N","apellidos":"A","especialidadRegistro":"Celador"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("oposibots_usuario_creado", audits.single().first)
    }

    @Test
    fun aDeletionWhoseAuditFailsIsNotReportedAsASilentSuccess() = testApplication {
        val fake = LegacyFake(mapOf(2 to plainAuth))
        application { legacyModule(fake, mutableListOf(), auditFails = true) }
        val response = client.delete("/api/v1/admin/oposibots/usuarios/2") { header(HttpHeaders.Authorization, "Bearer $adminAuth") }
        assertFalse(response.status.value in 200..299, "sin auditoria no hay 2xx: ${response.status}")
    }

    @Test
    fun theLegacySurfaceIsMarkedDeprecatedWithItsCanonicalSuccessor() = testApplication {
        val fake = LegacyFake(mapOf(2 to plainAuth))
        application { legacyModule(fake, mutableListOf()) }
        val list = client.get("/api/v1/admin/oposibots/usuarios") { header(HttpHeaders.Authorization, "Bearer $adminAuth") }
        assertEquals("@${LegacyEndpoints.SLICE_1_DEPRECATED_AT.epochSecond}", list.headers[LegacyHeaders.DEPRECATION])
        assertEquals("</api/v1/admin/usuarios>; rel=\"successor-version\"", list.headers[HttpHeaders.Link])
        val one = client.get("/api/v1/admin/oposibots/usuarios/2") { header(HttpHeaders.Authorization, "Bearer $adminAuth") }
        assertEquals("</api/v1/admin/usuarios>; rel=\"successor-version\"", one.headers[HttpHeaders.Link])
    }
}
