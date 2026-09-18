package com.oposicontrol.backend

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminV1FeedbackTest {

    private val feedbackId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    // -----------------------------------------------------------------
    // Resolver una incidencia: un cuerpo inválido NO la resuelve
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de C-4.**
     *
     * `POST /feedback/preguntas/{id}/resolver` recibía el cuerpo con
     * `runCatching { … }.getOrDefault(ResolverFeedbackRequest())`, y
     * `ResolverFeedbackRequest.estado` vale `"resuelto"` por defecto. Es decir:
     * un cuerpo malformado —o ausente, o cortado a mitad de envío— **resolvía la
     * incidencia**, escribía en `feedback_pregunta`, registraba
     * `feedback_resuelto` en auditoría y emitía `FEEDBACK_RESOLVED`.
     *
     * Fail-open sobre una escritura ya es malo; lo que lo hace peor es la
     * auditoría: quedaba un rastro afirmando que un administrador resolvió algo
     * que nunca pidió resolver. Una pista de auditoría que puede mentir no sirve
     * para lo único que sirve una pista de auditoría.
     *
     * Se afirma CERO peticiones al upstream, no el código de estado: es lo único
     * que distingue «se rechazó» de «se escribió y luego se contestó feo».
     */
    @Test
    fun aMalformedBodyDoesNotResolveTheIncident() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { feedbackModule(upstream) }

        val response = client.post("/api/v1/admin/feedback/preguntas/$feedbackId/resolver") {
            contentType(ContentType.Application.Json)
            setBody("""{"estado": """)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNoWriteEscaped(upstream)
    }

    /**
     * Y un cuerpo vacío tampoco.
     *
     * Aquí se afirma «no fue un 2xx» en vez de un código concreto: si el cliente
     * no negocia bien el contenido, Ktor puede contestar 415 en vez de 400, y esa
     * diferencia no es el contrato. El contrato es que **no se escribió nada**.
     */
    @Test
    fun anEmptyBodyDoesNotResolveTheIncidentEither() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { feedbackModule(upstream) }

        val response = client.post("/api/v1/admin/feedback/preguntas/$feedbackId/resolver") {
            contentType(ContentType.Application.Json)
            setBody("")
        }

        assertFalse(
            response.status.isSuccess(),
            "un cuerpo vacío no puede resolver una incidencia: ${response.status}",
        )
        assertNoWriteEscaped(upstream)
    }

    /**
     * Control obligatorio: sin él, «rechazarlo todo» pasaría los dos casos de
     * arriba. Un cuerpo válido sigue resolviendo, y la escritura sale con su
     * estado.
     */
    @Test
    fun aValidBodyStillResolvesTheIncident() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { feedbackModule(upstream) }

        val response = client.post("/api/v1/admin/feedback/preguntas/$feedbackId/resolver") {
            contentType(ContentType.Application.Json)
            setBody("""{"estado":"descartado","nota":""}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            upstream.any { it.method() == "PATCH" && it.uri().rawPath.contains("feedback_pregunta") },
            "no salió la escritura: ${upstream.map { "${it.method()} ${it.uri()}" }}",
        )
    }

    /** El `id` acaba en `?id=eq.$id`: mismo vector de truncado, misma red. */
    @Test
    fun aFragmentInTheFeedbackIdNeverReachesSupabase() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { feedbackModule(upstream) }

        val response = client.post("/api/v1/admin/feedback/preguntas/$feedbackId%23/resolver") {
            contentType(ContentType.Application.Json)
            setBody("""{"estado":"resuelto","nota":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNoWriteEscaped(upstream)
    }

    private fun assertNoWriteEscaped(upstream: List<HttpRequest>) {
        val writes = upstream.filter { it.method() != "GET" }
        assertTrue(
            writes.isEmpty(),
            "salió una escritura hacia el upstream pese al rechazo: " +
                writes.joinToString { "${it.method()} ${it.uri()}" },
        )
    }

    private fun Application.feedbackModule(upstream: MutableList<HttpRequest>) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            // Igual que en `Application.kt`: `BadRequestException` NO extiende
            // `IllegalArgumentException`, así que necesita su propio handler o
            // cae en el catch-all como un 500.
            exception<BadRequestException> { call, _ ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = "El cuerpo de la petición no es válido."),
                )
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing {
            adminV1Routes(
                AdminV1Gateway.forTesting(
                    supabaseUrl = "https://stub.supabase.test",
                    serviceRoleKey = "stub-key",
                    json = Json { ignoreUnknownKeys = true },
                    client = V1StubHttpClient({ upstream += it }) { uri ->
                        when {
                            uri.contains("/rest/v1/feedback_pregunta") -> V1StubResponse(
                                200,
                                """[{"id":"$feedbackId","pregunta_id":"p-1","tipo_feedback":"pregunta_ambigua",""" +
                                    """"descripcion":"","estado":"descartado","creado_en":"2026-08-23T10:00:00Z"}]""",
                            )
                            else -> V1StubResponse(200, "[]")
                        }
                    },
                ),
            )
        }
    }
    @Test
    fun feedbackCursor_roundTripsCreadoEnAndId() {
        val creadoEn = "2026-08-23T10:00:00Z"
        val id = "123e4567-e89b-12d3-a456-426614174000"

        val cursor = encodeFeedbackCursor(creadoEn, id)
        val (decodedCreadoEn, decodedId) = decodeFeedbackCursor(cursor)

        assertEquals(creadoEn, decodedCreadoEn)
        assertEquals(id, decodedId)
    }

    @Test
    fun decodeFeedbackCursor_rejectsGarbageInsteadOfGuessingAPage() {
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor("not-valid-base64!!") }
    }

    @Test
    fun decodeFeedbackCursor_rejectsAPayloadWithNoSeparator() {
        val noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-23T10:00:00Z".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(noSeparator) }
    }

    @Test
    fun decodeFeedbackCursor_rejectsANonIsoCreadoEn() {
        val bogusDate = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-a-date|123e4567-e89b-12d3-a456-426614174000".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(bogusDate) }
    }

    @Test
    fun decodeFeedbackCursor_rejectsANonUuidId() {
        val bogusId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-23T10:00:00Z|not-a-uuid".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(bogusId) }
    }
}
