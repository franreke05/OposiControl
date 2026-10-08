package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RC-10 (MASTERF1-API-002): los escritores legacy `POST/PUT/DELETE /admin/users*` y `POST/PUT /admin/roles*` no tenian
 * llamador vivo y escribian `public.usuario`/`app_role` sin la guarda MASTER-AUTH-001 ni auditoria (DELETE borraba
 * cualquier fila, administrativa incluida). Ahora responden 410 ADMIN_LEGACY_ROUTE_DISABLED ANTES de leer el cuerpo o de
 * cualquier I/O upstream, y siguen montados y contados (`legacyRoute`, cabecera Deprecation) hasta tener cero trafico.
 * Control negativo: con el AdminGateway.kt de HEAD el DELETE llega a `DELETE /rest/v1/usuario` (204) y este test falla.
 */
class LegacyAdminWritersRetiredTest {
    private val sent = CopyOnWriteArrayList<String>()

    private fun gateway() = SupabaseAdminGateway(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "test-service-role-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ request -> sent += "${request.method()} ${request.uri()}" }) {
            V1StubResponse(200, """[{"id":7,"email":"admin@example.test","nombre":"Admin","name":"admin"}]""")
        },
    )

    /** RC-01: el gateway canonico que usa el adaptador EP-144; registra en el mismo [sent] (debe quedar vacio). */
    private fun moderationGateway() = AdminV1Gateway.forTesting(
        "https://stub.supabase.test", "test-service-role-key", Json { ignoreUnknownKeys = true },
        V1StubHttpClient({ request -> sent += "${request.method()} ${request.uri()}" }) { V1StubResponse(201, "") },
    )

    private fun Application.legacyModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminRoutes(gateway(), moderationGateway())
            }
        }
    }

    private val retired = listOf(
        HttpMethod.Post to "/admin/users",
        HttpMethod.Post to "/admin/users/7",
        HttpMethod.Put to "/admin/users/7",
        HttpMethod.Delete to "/admin/users/7",
        HttpMethod.Post to "/admin/roles",
        HttpMethod.Post to "/admin/roles/3",
        HttpMethod.Put to "/admin/roles/3",
    )

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, token: String, body: String?): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun assertRetired(response: HttpResponse, label: String) {
        assertEquals(HttpStatusCode.Gone, response.status, label)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ADMIN_LEGACY_ROUTE_DISABLED", payload["code"]?.jsonPrimitive?.content, label)
        assertTrue(response.headers[LegacyHeaders.DEPRECATION] != null, "$label: la ruta sigue contada (legacyRoute)")
        assertTrue(sent.isEmpty(), "$label: ninguna llamada upstream antes del rechazo: $sent")
    }

    @Test
    fun everyCallerlessLegacyWriterAnswers410WithoutAnyUpstreamCall() = testApplication {
        application { legacyModule() }
        val body = """{"id":"7","email":"victima@example.test","fullName":"X","roleIds":["1"],"name":"superadmin","permissions":[]}"""
        for ((method, path) in retired) {
            assertRetired(send(method, path, "7a7a7a7a-0000-4000-8000-000000000001", body), "$method $path")
        }
    }

    @Test
    fun aMalformedOrMissingBodyStillGets410BecauseTheBodyIsNeverRead() = testApplication {
        application { legacyModule() }
        for ((method, path) in retired) {
            assertRetired(send(method, path, "7a7a7a7a-0000-4000-8000-000000000001", """{"email": """), "$method $path malformed")
            assertRetired(send(method, path, "oposicontrol-admin", null), "$method $path no body, static token")
        }
    }

    @Test
    fun theLegacyReadersStayServed() = testApplication {
        application { legacyModule() }
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/admin/roles", "oposicontrol-admin", null).status)
        assertTrue(sent.any { it.startsWith("GET ") }, "control positivo: el arnes si llega a upstream en una lectura: $sent")
    }

    @Test
    fun theRetiredWritersAreRegisteredLegacyEndpointsWithoutAnInventedSuccessor() {
        val ids = LegacyEndpoints.RETIRED_ADMIN_WRITERS.map { "${it.method} ${it.legacyPath}" }.toSet()
        assertEquals(retired.map { (m, p) -> "${m.value} ${p.replace("/7", "/{userId}").replace("/3", "/{roleId}")}" }.toSet(), ids)
        assertTrue(LegacyEndpoints.RETIRED_ADMIN_WRITERS.all { it.successorPath == null })
    }
}
