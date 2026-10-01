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
 * MASTER7-API-006: `POST /admin/support/conversations/{conversationId}/messages` (EP-150) atribuia la respuesta a un
 * `assignee` de texto libre / al primer usuario y no auditaba. Se retira con 410 SUPPORT_LEGACY_ROUTE_DISABLED, igual
 * que la cartera legacy: el cuerpo NO se lee y no hay ninguna llamada upstream.
 */
class LegacySupportReplyRetiredTest {
    private val sent = CopyOnWriteArrayList<String>()

    private fun gateway() = SupabaseAdminGateway(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "test-service-role-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ request -> sent += "${request.method()} ${request.uri()}" }) {
            V1StubResponse(200, """[{"id":1,"student_usuario_id":1,"estado":"abierta"}]""")
        },
    )

    private fun Application.legacyModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminRoutes(gateway())
            }
        }
    }

    private suspend fun ApplicationTestBuilder.post(token: String, body: String): HttpResponse =
        client.post("/admin/support/conversations/1/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun assertRetired(response: HttpResponse) {
        assertEquals(HttpStatusCode.Gone, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SUPPORT_LEGACY_ROUTE_DISABLED", payload["code"]?.jsonPrimitive?.content)
        assertTrue(sent.isEmpty(), "ninguna llamada upstream antes del rechazo: $sent")
    }

    @Test
    fun anAdminJwtGets410WithoutAnyUpstreamCall() = testApplication {
        application { legacyModule() }
        assertRetired(post("7a7a7a7a-0000-4000-8000-000000000001", """{"assignee":"victima","text":"hola","internal":false}"""))
    }

    @Test
    fun aMalformedBodyStillGets410BecauseTheBodyIsNeverRead() = testApplication {
        application { legacyModule() }
        assertRetired(post("7a7a7a7a-0000-4000-8000-000000000001", """{"assignee": """))
    }

    @Test
    fun theStaticTokenPrincipalAlsoGets410() = testApplication {
        application { legacyModule() }
        assertRetired(post("oposicontrol-admin", """{"assignee":"","text":"hola","internal":true}"""))
    }

    @Test
    fun theRejectionPointsToTheCanonicalAuditedRoute() {
        val failure = kotlin.test.assertFailsWith<BackendDomainException> { gateway().rejectLegacySupportReply("1") }
        assertEquals(HttpStatusCode.Gone, failure.status)
        assertTrue("/api/v1/admin/soporte/conversaciones/{id}/mensajes" in failure.message, failure.message)
    }
}
