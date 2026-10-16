package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * D5-15 `POST /api/v1/app/feedback/pregunta` (E3 Reportar): una repeticion tras un resultado desconocido no crea un segundo reporte.
 * Misma `Idempotency-Key` + mismo cuerpo = el mismo resultado; misma clave + otro cuerpo = 422; sin clave, como siempre.
 */
class AppFeedbackIdempotencyTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val question = "55555555-5555-4555-8555-555555555555"
    private val attempt = "44444444-4444-4444-8444-444444444444"
    private var questionInExam = true
    private var limiter: OposiBotsAuthRateLimiter? = null
    private val keyA = "0a0a0a0a-0000-4000-8000-00000000000a"
    private val keyB = "0b0b0b0b-0000-4000-8000-00000000000b"

    private val writes = AtomicInteger()
    private val sent = CopyOnWriteArrayList<HttpRequest>()

    private fun gateway() = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ sent += it }) { uri ->
            when {
                "/rest/v1/feedback_pregunta" in uri -> V1StubResponse(201, """[{"id":"fb-${writes.incrementAndGet()}"}]""")
                "/rest/v1/intentos_examen" in uri -> V1StubResponse(200, """[{"examen_id":"99999999-9999-4999-8999-999999999999"}]""")
                "/rest/v1/preguntas_examen" in uri -> V1StubResponse(200, if (questionInExam) """[{"pregunta_id":"$question"}]""" else "[]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private suspend fun HttpClient.report(body: String, key: String? = null): HttpResponse = post("/api/v1/app/feedback/pregunta") {
        header("X-Usuario-Id", user)
        key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun body(type: String = "mal_redactada", text: String = "falta una palabra") = """{"preguntaId":"$question","intentoId":"$attempt","tipoFeedback":"$type","descripcion":"$text"}"""

    private fun io.ktor.server.testing.ApplicationTestBuilder.module() = application {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { appV1Routes(gateway(), SupabaseJwtVerifier("https://stub.supabase.test", Json), allowDevUser = true, idempotency = InMemoryIdempotencyStore(), limiter = limiter) }
    }

    private fun id(r: HttpResponse, text: String) = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content

    @Test
    fun theSameKeyAndTheSameBodyIsTheSameReportAndWritesOnce() = testApplication {
        module()
        val first = client.report(body(), keyA)
        val firstText = first.bodyAsText()
        val retry = client.report(body(), keyA)
        assertEquals(HttpStatusCode.Created, first.status, firstText)
        assertEquals(HttpStatusCode.Created, retry.status)
        assertEquals("true", retry.headers["Idempotent-Replayed"])
        assertEquals(id(first, firstText), id(retry, retry.bodyAsText()), "el mismo id")
        assertEquals(1, writes.get(), "un solo reporte pendiente")
    }

    @Test
    fun theSameKeyWithAnotherBodyIs422AndWritesNothingMore() = testApplication {
        module()
        client.report(body(), keyA)
        val other = client.report(body(text = "otra cosa"), keyA)
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
        assertTrue(other.bodyAsText().contains("IDEMPOTENCY_KEY_REUSED"))
        assertEquals(1, writes.get())
    }

    @Test
    fun differentKeysAreDifferentReportsAndNoKeyKeepsTheOldBehaviour() = testApplication {
        module()
        val a = client.report(body(), keyA)
        val b = client.report(body(), keyB)
        assertNotEquals(id(a, a.bodyAsText()), id(b, b.bodyAsText()))
        client.report(body())
        client.report(body())
        assertEquals(4, writes.get(), "dos claves distintas y dos llamadas sin clave: cuatro reportes")
    }

    @Test
    fun aMalformedKeyIs400AndAFailedAttemptReleasesTheKey() = testApplication {
        module()
        assertEquals(HttpStatusCode.BadRequest, client.report(body(), "no-es-uuid").status)
        assertEquals(0, writes.get())
        // un cuerpo invalido (tipo fuera del CHECK) falla sin escribir y libera la clave: el reintento corregido con la MISMA clave funciona
        val bad = client.report(body(type = "inventado"), keyB)
        assertTrue(bad.status.value in 400..499, "tipo fuera del vocabulario: ${bad.status}")
        val good = client.report(body(), keyB)
        assertEquals(HttpStatusCode.Created, good.status, good.bodyAsText())
        assertEquals(1, writes.get())
    }

    // ---- E2-V1-R02: sin acceso comprobado no se reporta

    @Test
    fun intentoIdIsRequiredAndTheQuestionMustBelongToThatAttemptsExam() = testApplication {
        module()
        val noAttempt = client.report("""{"preguntaId":"$question","tipoFeedback":"otro"}""", keyA)
        assertEquals(HttpStatusCode.BadRequest, noAttempt.status, noAttempt.bodyAsText())
        assertTrue(noAttempt.bodyAsText().contains("VALIDATION"))
        questionInExam = false
        val foreignQuestion = client.report(body(), keyB)
        assertEquals(HttpStatusCode.UnprocessableEntity, foreignQuestion.status, foreignQuestion.bodyAsText())
        assertTrue(foreignQuestion.bodyAsText().contains("QUESTION_NOT_IN_ATTEMPT"), "una pregunta desconocida o ajena al intento es un 422 limpio, nunca un 5xx")
        assertEquals(0, writes.get(), "ninguna de las dos escribe nada")
    }

    // ---- E2-V1-R01: limite por usuario (10 por minuto) y tope diario

    private class CountingLimiter(private val perMinute: Int, private val perDay: Int) : OposiBotsAuthRateLimiter {
        private val counts = HashMap<OposiBotsAuthOperation, Int>()
        override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) = OposiBotsRateLimitDecision(true)
        override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String): OposiBotsRateLimitDecision {
            val n = (counts[operation] ?: 0) + 1
            counts[operation] = n
            val cap = when (operation) {
                OposiBotsAuthOperation.FEEDBACK_REPORT -> perMinute
                OposiBotsAuthOperation.FEEDBACK_REPORT_DAILY -> perDay
                else -> Int.MAX_VALUE
            }
            return if (n <= cap) OposiBotsRateLimitDecision(true) else OposiBotsRateLimitDecision(false, 17)
        }
    }

    @Test
    fun theTenthReportInAMinuteGoesThroughAndTheEleventhIs429WithRetryAfter() = testApplication {
        limiter = CountingLimiter(perMinute = 10, perDay = 50)
        module()
        repeat(10) { assertEquals(HttpStatusCode.Created, client.report(body(), java.util.UUID.randomUUID().toString()).status) }
        val eleventh = client.report(body(), java.util.UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
        assertEquals("17", eleventh.headers["Retry-After"])
        assertEquals(10, writes.get(), "el rechazado no escribe")
    }

    @Test
    fun theDailyCapAlsoStopsTheFlood() = testApplication {
        limiter = CountingLimiter(perMinute = 1000, perDay = 3)
        module()
        repeat(3) { assertEquals(HttpStatusCode.Created, client.report(body(), java.util.UUID.randomUUID().toString()).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.report(body(), java.util.UUID.randomUUID().toString()).status)
        assertEquals(3, writes.get())
    }
}
