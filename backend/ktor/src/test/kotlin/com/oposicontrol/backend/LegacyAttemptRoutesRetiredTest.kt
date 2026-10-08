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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATE SLICE 3 (F-01 / F-02): `POST /api/v1/app/examenes/{id}/iniciar` abria un intento comprobando solo activo/publicado
 * (nunca EntitlementPolicy, D7-25), y `POST /api/v1/app/intentos/{id}/responder` devolvia `esCorrecta`/`opcionCorrectaId`
 * al momento contra el examen vivo. Las dos responden ahora 410 LEGACY_ATTEMPT_ROUTE_RETIRED ANTES de resolver la
 * identidad, leer el cuerpo o tocar upstream, y siguen montadas y contadas (`legacyRoute`, cabecera Deprecation).
 *
 * Control negativo: con el AppV1Routes.kt de HEAD (dade55f) `iniciar` responde 201 tras un `POST /rest/v1/intentos_examen`
 * y `responder` llega a `GET /rest/v1/intentos_examen`, asi que `sent` no esta vacio y estos tests fallan.
 */
class LegacyAttemptRoutesRetiredTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sent = CopyOnWriteArrayList<String>()
    private val identityLookups = AtomicInteger()

    private val exam = "e3000000-0000-4000-8000-000000000001"
    private val attempt = "33333333-3333-4333-8333-333333333333"
    private val question = "f3000000-0000-4000-8000-000000000001"
    private val option = "0a300000-0000-4000-8000-000000000001"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id ->
            identityLookups.incrementAndGet()
            CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true)
        },
    )

    /** Un upstream que SI dejaria empezar y responder: si la ruta lo alcanzara, el intento se abriria. */
    private fun gateway() = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = json,
        client = V1StubHttpClient({ request -> sent += "${request.method()} ${request.uri()}" }) {
            V1StubResponse(200, """[{"id":"$attempt","examen_id":"$exam","estado":"en_progreso","pregunta_id":"$question"}]""")
        },
    )

    private fun Application.legacyModule(allowDevUser: Boolean = false) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            appV1Routes(gateway(), SupabaseJwtVerifier("https://stub.supabase.test", json), allowDevUser = allowDevUser, identityResolver = resolver)
        }
    }

    private val retired = listOf(
        "/api/v1/app/examenes/$exam/iniciar" to "{}",
        "/api/v1/app/intentos/$attempt/responder" to """{"preguntaId":"$question","opcionSeleccionadaId":"$option"}""",
    )

    private suspend fun ApplicationTestBuilder.send(path: String, body: String?, auth: Pair<String, String>?): HttpResponse =
        client.post(path) {
            auth?.let { (name, value) -> header(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun assertRetired(response: HttpResponse, label: String) {
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.Gone, response.status, "$label: $text")
        val payload = Json.parseToJsonElement(text).jsonObject
        assertEquals("LEGACY_ATTEMPT_ROUTE_RETIRED", payload["code"]?.jsonPrimitive?.content, label)
        assertTrue(response.headers[LegacyHeaders.DEPRECATION] != null, "$label: la ruta sigue contada (legacyRoute)")
        assertTrue("esCorrecta" !in text && "opcionCorrectaId" !in text, "$label: nunca sale la correccion: $text")
        assertTrue(sent.isEmpty(), "$label: ninguna llamada upstream antes del rechazo: $sent")
        assertEquals(0, identityLookups.get(), "$label: ni siquiera se resuelve la identidad contra el directorio")
    }

    @Test
    fun anAuthenticatedActiveUserGets410OnBothRoutesWithoutAnyUpstreamCall() = testApplication {
        application { legacyModule() }
        for ((path, body) in retired) {
            assertRetired(send(path, body, HttpHeaders.Authorization to "Bearer $TEST_TOKEN"), "POST $path")
        }
    }

    @Test
    fun aMalformedOrMissingBodyStillGets410BecauseTheBodyIsNeverRead() = testApplication {
        application { legacyModule() }
        for ((path, _) in retired) {
            assertRetired(send(path, """{"preguntaId": """, HttpHeaders.Authorization to "Bearer $TEST_TOKEN"), "POST $path malformed")
            assertRetired(send(path, null, HttpHeaders.Authorization to "Bearer $TEST_TOKEN"), "POST $path no body")
        }
    }

    @Test
    fun theDevUserHeaderAndAnAnonymousCallerGetTheSame410() = testApplication {
        application { legacyModule(allowDevUser = true) }
        for ((path, body) in retired) {
            assertRetired(send(path, body, "X-Usuario-Id" to TEST_UID), "POST $path dev header")
            assertRetired(send(path, body, null), "POST $path anonymous: el 410 no revela nada y no hace I/O")
        }
    }

    /** Control positivo del arnes: una ruta legacy NO retirada del mismo montaje si resuelve la identidad y llega a upstream. */
    @Test
    fun aNonRetiredLegacyRouteOfTheSameModuleStillReachesIdentityAndUpstream() = testApplication {
        application { legacyModule() }
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(sent.isNotEmpty() && identityLookups.get() > 0, "el arnes cuenta upstream e identidad: $sent / ${identityLookups.get()}")
    }

    @Test
    fun theRetiredRoutesRecordTheirRoutedCanonicalSuccessorsAndOnlyAResolvableOneIsAdvertised() = testApplication {
        application { legacyModule() }
        assertEquals(listOf(LegacyEndpoints.APP_INICIAR, LegacyEndpoints.APP_RESPONDER), LegacyEndpoints.RETIRED_ATTEMPT_ROUTES)
        assertEquals("/api/v1/app/examenes/{id}/intentos", LegacyEndpoints.APP_INICIAR.successorPath, "D2-03")
        assertEquals("/api/v1/app/intentos/{id}/respuestas/{preguntaId}", LegacyEndpoints.APP_RESPONDER.successorPath, "D2-09")
        // Las sucesoras estan ruteadas de verdad (los literales de AttemptRoutes.kt).
        val routes = java.io.File("src/main/kotlin/com/oposicontrol/backend/AttemptRoutes.kt").readText()
        assertTrue("post(\"/examenes/{examId}/intentos\")" in routes && "put(\"/respuestas/{preguntaId}\")" in routes)

        val start = send(retired[0].first, "{}", HttpHeaders.Authorization to "Bearer $TEST_TOKEN")
        assertEquals("</api/v1/app/examenes/$exam/intentos>; rel=\"successor-version\"", start.headers[HttpHeaders.Link])
        // D2-09 lleva la pregunta en la URL; la ruta legacy la llevaba en el cuerpo (que no se lee): sin URI resoluble no hay Link.
        val answer = send(retired[1].first, retired[1].second, HttpHeaders.Authorization to "Bearer $TEST_TOKEN")
        assertNull(answer.headers[HttpHeaders.Link])
        val failure = kotlin.test.assertFailsWith<BackendDomainException> { rejectLegacyAttemptRoute() }
        assertEquals(HttpStatusCode.Gone, failure.status)
        assertTrue("/api/v1/app/examenes/{examId}/intentos" in failure.message && "/respuestas/{preguntaId}" in failure.message, failure.message)
    }
}
