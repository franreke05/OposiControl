package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/admin/sesion`: quién usa el panel y con qué roles, según el SERVIDOR.
 *
 * La pestaña Estado enseña «esta sesión». Los roles que el panel guarda en local son los del inicio de sesión y los
 * escribe el propio cliente, así que lo que aquí se protege es que la respuesta salga del `sub` del JWT y de las tablas
 * de roles, y que quien no es una persona (el token estático) no reciba una sesión inventada.
 */
class AdminSystemStatusSessionTest {
    private val superActor = "5e5e5e5e-0000-4000-8000-000000000001"
    private val adminActor = "5e5e5e5e-0000-4000-8000-000000000002"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val superToken = "super-token"
    private val adminToken = "admin-token"
    private val staticToken = "static-token"
    private val path = "/api/v1/admin/sesion"

    private fun gateway(sent: MutableList<HttpRequest>, profileStatus: Int = 200): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(sent::add) { uri ->
            when {
                "/rest/v1/roles_app" in uri -> V1StubResponse(
                    200,
                    // Mayúsculas y espacios a propósito: el código se normaliza en el servidor, no en el panel.
                    """[{"id":"$roleSuper","codigo":" SuperAdmin ","nombre":"Superadmin"},{"id":"$roleAdmin","codigo":"admin","nombre":"Admin"}]""",
                )
                "/rest/v1/roles_usuario" in uri -> when (uri.substringAfter("usuario_id=eq.").substringBefore('&')) {
                    superActor -> V1StubResponse(200, """[{"rol_id":"$roleSuper"},{"rol_id":"$roleAdmin"}]""")
                    adminActor -> V1StubResponse(200, """[{"rol_id":"$roleAdmin"}]""")
                    else -> V1StubResponse(200, "[]")
                }
                "/rest/v1/perfiles_usuario" in uri -> when {
                    profileStatus != 200 -> V1StubResponse(profileStatus, """{"message":"boom"}""")
                    superActor in uri -> V1StubResponse(200, """[{"usuario_id":"$superActor","nombre_mostrar":"  Fran Superadmin "}]""")
                    else -> V1StubResponse(200, "[]")
                }
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    @Test
    fun aSuperadminGetsTheirRealRolesAndBothFlags() {
        val session = gateway(CopyOnWriteArrayList()).getAdminSession(superActor)

        assertEquals(superActor, session.usuarioId)
        assertEquals("Fran Superadmin", session.nombre)
        assertEquals(listOf("admin", "superadmin"), session.roles, "códigos canónicos, en minúsculas y ordenados")
        assertTrue(session.esSuperadmin)
        assertTrue(session.esAdmin, "un superadministrador también es administrador")
    }

    @Test
    fun anAdminIsAdminButNotSuperadminAndAMissingNameStaysMissing() {
        val session = gateway(CopyOnWriteArrayList()).getAdminSession(adminActor)

        assertEquals(listOf("admin"), session.roles)
        assertFalse(session.esSuperadmin)
        assertTrue(session.esAdmin)
        assertNull(session.nombre, "sin nombre en el perfil no se inventa uno")
    }

    @Test
    fun withoutAnActorThereIsNoSessionAndNothingIsRead() {
        val sent = CopyOnWriteArrayList<HttpRequest>()

        val error = assertFailsWith<BackendDomainException> { gateway(sent).getAdminSession(null) }

        assertEquals(HttpStatusCode.Forbidden, error.status)
        assertTrue(sent.isEmpty(), "sin actor no se consulta ninguna tabla")
    }

    @Test
    fun anUnreadableProfileDoesNotHideTheRoles() {
        val session = gateway(CopyOnWriteArrayList(), profileStatus = 500).getAdminSession(superActor)

        assertNull(session.nombre)
        assertEquals(listOf("admin", "superadmin"), session.roles)
        assertTrue(session.esSuperadmin)
    }

    private fun Application.module(sent: MutableList<HttpRequest>) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") {
                authenticate { credentials ->
                    when (credentials.token) {
                        superToken -> UserIdPrincipal(superActor)
                        adminToken -> UserIdPrincipal(adminActor)
                        // El token estático autentica como un literal que no es un uuid: no es una persona.
                        staticToken -> UserIdPrincipal("oposicontrol-admin")
                        else -> null
                    }
                }
            }
        }
        routing {
            authenticate("gateway-auth") {
                adminV1Routes(gateway(sent))
            }
        }
    }

    @Test
    fun theRouteAnswersWithTheServerSideSessionAndIsNeverCacheable() = testApplication {
        application { module(CopyOnWriteArrayList()) }

        val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $superToken") }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val session = Json.decodeFromString(AdminSessionDto.serializer(), body)
        assertEquals(superActor, session.usuarioId)
        assertEquals(listOf("admin", "superadmin"), session.roles)
        assertTrue(session.esSuperadmin && session.esAdmin)

        val admin = client.get(path) { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val adminSession = Json.decodeFromString(AdminSessionDto.serializer(), admin.bodyAsText())
        assertEquals(listOf("admin"), adminSession.roles)
        assertFalse(adminSession.esSuperadmin)
        assertTrue(adminSession.esAdmin)
    }

    @Test
    fun theStaticTokenGets403AndNoSession() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { module(sent) }

        val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $staticToken") }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "tampoco el 403 se puede cachear")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun withoutCredentialsItIs401() = testApplication {
        application { module(CopyOnWriteArrayList()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
    }
}
