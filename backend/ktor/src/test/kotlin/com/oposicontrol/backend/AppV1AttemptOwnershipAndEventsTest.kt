package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER-API-010: un intento ajeno o inexistente es 404 ATTEMPT_NOT_FOUND, nunca 401 (que cerraba la sesion).
 * MASTER-SEC-007: `POST /api/v1/app/eventos` comprueba que el intento referenciado es del llamador y acota tamaños.
 */
class AppV1AttemptOwnershipAndEventsTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val foreignAttempt = "22222222-2222-4222-8222-222222222222"
    private val ownAttempt = "33333333-3333-4333-8333-333333333333"
    private val exam = "44444444-4444-4444-8444-444444444444"
    private val question = "55555555-5555-4555-8555-555555555555"
    private val otherQuestion = "66666666-6666-4666-8666-666666666666"

    private fun responder(uri: String): V1StubResponse = when {
        "/rest/v1/intentos_examen" in uri && "id=eq.$ownAttempt" in uri && "usuario_id=eq.$user" in uri ->
            V1StubResponse(200, """[{"id":"$ownAttempt","examen_id":"$exam","estado":"en_progreso"}]""")
        "/rest/v1/intentos_examen" in uri -> V1StubResponse(200, "[]")
        "/rest/v1/preguntas_examen" in uri && "pregunta_id=eq.$question" in uri -> V1StubResponse(200, """[{"pregunta_id":"$question"}]""")
        "/rest/v1/preguntas_examen" in uri -> V1StubResponse(200, "[]")
        "/rest/v1/rpc/record_event" in uri -> V1StubResponse(200, """{"event_id":"ev-1"}""")
        else -> V1StubResponse(200, "[]")
    }

    private fun gateway(sent: MutableList<HttpRequest> = CopyOnWriteArrayList()) = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ sent += it }, ::responder),
    )

    // ---------------------------------------------------------------- MASTER-API-010

    @Test
    fun aForeignAttemptIsA404AttemptNotFoundOnEveryAttemptRouteNeverA401() = testApplication {
        val g = gateway()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { appV1Routes(g, SupabaseJwtVerifier("https://stub.supabase.test", Json), allowDevUser = true) }
        }
        val calls = listOf(
            "/api/v1/app/intentos/$foreignAttempt/responder" to """{"preguntaId":"$question","opcionSeleccionadaId":"$otherQuestion"}""",
            "/api/v1/app/intentos/$foreignAttempt/finalizar" to "{}",
            "/api/v1/app/feedback/pregunta" to """{"preguntaId":"$question","intentoId":"$foreignAttempt"}""",
            "/api/v1/app/eventos" to """{"nombreEvento":"x","intentoId":"$foreignAttempt","idempotencyKey":"k-1"}""",
        )
        calls.forEach { (path, body) ->
            val response = client.post(path) {
                header("X-Usuario-Id", user)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NotFound, response.status, "$path: ${response.bodyAsText()}")
            assertEquals("ATTEMPT_NOT_FOUND", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content, path)
            assertNull(response.headers[HttpHeaders.WWWAuthenticate], "$path: no es un fallo de sesion")
        }
    }

    // ---------------------------------------------------------------- MASTER-SEC-007

    @Test
    fun anEventReferencingTheCallersOwnAttemptIsRecorded() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val id = gateway(sent).registrarEvento(user, AppEventoRequest(nombreEvento = "respuesta", intentoId = ownAttempt, preguntaId = question, idempotencyKey = "k-2"))
        assertEquals("ev-1", id)
        assertTrue(sent.any { "/rest/v1/rpc/record_event" in it.uri().toString() })
    }

    @Test
    fun anEventWithAQuestionOutsideTheAttemptsExamIsRejectedBeforeTheRpc() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        assertFailsWith<IllegalArgumentException> {
            gateway(sent).registrarEvento(user, AppEventoRequest(nombreEvento = "respuesta", intentoId = ownAttempt, preguntaId = otherQuestion, idempotencyKey = "k-3"))
        }
        assertTrue(sent.none { "record_event" in it.uri().toString() })
    }

    @Test
    fun oversizedEventFieldsAreRejectedBeforeTheRpc() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val g = gateway(sent)
        val bigProps = JsonObject(mapOf("blob" to JsonPrimitive("x".repeat(EVENT_PROPERTIES_MAX_BYTES))))
        listOf(
            AppEventoRequest(nombreEvento = "x".repeat(EVENT_TEXT_MAX + 1), idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", pantalla = "p".repeat(EVENT_TEXT_MAX + 1), idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", idempotencyKey = "k".repeat(EVENT_IDEMPOTENCY_KEY_MAX + 1)),
            AppEventoRequest(nombreEvento = "ok", propiedades = bigProps, idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", preguntaId = "no-uuid", idempotencyKey = "k"),
        ).forEach { request -> assertFailsWith<IllegalArgumentException>(request.toString().take(80)) { g.registrarEvento(user, request) } }
        assertTrue(sent.isEmpty(), "nada sale hacia Supabase")
    }
}
