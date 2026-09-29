package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SLICE 1 (obligacion de corte de SLICE 0): las rutas legacy de `/api/v1/app` con JWT resuelven la identidad con
 * `activeIdentity` (deny-by-default). Para una cuenta activa nada cambia; el resto recibe 403 y no toca la base.
 */
class AppV1LegacyIdentityTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var status: String? = "activa"
    private var profileExists = true

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> if (profileExists) CanonicalUserIdentity(id, AccountStatus.fromWire(status), true) else null },
    )

    private fun Application.legacyModule(sent: MutableList<HttpRequest>) {
        install(ContentNegotiation) { json(json) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AppV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = json,
            client = V1StubHttpClient({ sent += it }) { V1StubResponse(200, "[]") },
        )
        routing {
            appV1Routes(gateway, SupabaseJwtVerifier("https://stub.supabase.test", json), allowDevUser = false, identityResolver = resolver)
        }
    }

    @Test
    fun anActiveAccountKeepsWorkingUnchanged() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { legacyModule(sent) }
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(sent.isNotEmpty(), "la ruta llega a su gateway")
    }

    @Test
    fun everyNonActiveOrUnknownStateIsDeniedWith403AndTheGatewayIsNeverReached() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { legacyModule(sent) }
        val expected = mapOf(
            "bloqueada" to "ACCOUNT_BLOCKED",
            "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING",
            "DISABLED" to "FORBIDDEN",
            "deleted" to "FORBIDDEN",
            "ANONYMIZED" to "FORBIDDEN",
            "" to "FORBIDDEN",
        )
        listOf("/api/v1/app/reto-diario", "/api/v1/app/home", "/api/v1/app/estadisticas", "/api/v1/app/commercial/purchases").forEach { path ->
            expected.forEach { (state, code) ->
                status = state
                val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
                assertEquals(HttpStatusCode.Forbidden, response.status, "$path $state")
                assertEquals(code, Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content, "$path $state")
            }
            status = null
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.status, "$path NULL")
            status = "activa"
            profileExists = false
            val missing = client.get(path) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals("PROFILE_MISSING", Json.parseToJsonElement(missing.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content, path)
            profileExists = true
        }
        assertTrue(sent.isEmpty(), "una identidad denegada no consulta la base: ${sent.map { it.uri() }}")
    }

    @Test
    fun anInvalidTokenIs401AndNoDevShortcutExistsWithoutTheFlag() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { legacyModule(sent) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer x.y.z") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/app/reto-diario") { header("X-Usuario-Id", TEST_UID) }.status)
        assertTrue(sent.isEmpty())
    }
}
