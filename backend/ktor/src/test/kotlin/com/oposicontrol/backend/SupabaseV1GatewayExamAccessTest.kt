package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CERT-EXAMS-2026-10-21 B-07: `GET /v1/examenes/{id}/preguntas` (EP-128) esta RETIRADA.
 *
 * Era ANONIMA y servia los enunciados y opciones de cualquier rafaga gratuita publicada: sin el limite diario de preguntas
 * (C-L1), sin la puerta de especialidad ni de capacidad del intento y con las preguntas de RESERVA que el intento oculta (C-O1).
 * No tiene llamador vivo (ni OposiBot ni el panel). Ahora responde 410 ANTES de tocar upstream, igual para cualquier id (no
 * confirma que un examen exista), con o sin sesion, y sigue contada como ruta legacy hasta tener telemetria a cero.
 */
class SupabaseV1GatewayExamAccessTest {

    private val examenId = "11111111-1111-4111-8111-111111111111"

    private fun io.ktor.server.application.Application.v1Module(uris: MutableList<String>) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        install(StatusPages) {
            exception<BackendDomainException> { call, cause ->
                call.respond(cause.status, ErrorDto(error = cause.errorCode.lowercase(), message = cause.message))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorDto(error = "bad_request", message = cause.message ?: ""))
            }
        }
        // El doble serviria un examen rafaga gratuito PUBLICADO con preguntas y opciones si alguien volviera a leerlas.
        routing {
            v1Routes(
                gatewayWith(record = { uris += it.uri().toString() }) { uri ->
                    when {
                        uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRow(publicadoEn = "\"2026-01-01T00:00:00Z\"")}]")
                        uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, """[{"pregunta_id":"p1","posicion":1,"es_reserva":true}]""")
                        uri.contains("/rest/v1/preguntas") -> V1StubResponse(200, """[{"id":"p1","titulo":"E2B enunciado","tipo_pregunta":"rafaga","activa":true}]""")
                        uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, """[{"id":"o1","pregunta_id":"p1","letra":"A","texto_opcion":"Opcion"}]""")
                        else -> V1StubResponse(200, "[]")
                    }
                },
            )
        }
    }

    @Test fun thePublicQuestionsRouteIsGoneForAPublishedFreeBurstExamAndReadsNothing() = testApplication {
        val uris = java.util.concurrent.CopyOnWriteArrayList<String>()
        application { v1Module(uris) }

        val response = client.get("/v1/examenes/$examenId/preguntas")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Gone, response.status, body)
        assertTrue(body.contains("public_exam_questions_retired"), body)
        assertTrue(!body.contains("E2B enunciado") && !body.contains("Opcion"), "no content travels: $body")
        assertTrue(uris.isEmpty(), "a retired route reads nothing upstream: $uris")
        assertTrue(response.headers["Deprecation"] != null, "still marked and counted as a legacy route (EP-128)")
    }

    @Test fun theSameGoneAnswerForAnyIdWithOrWithoutASessionSoNoExistenceOracle() = testApplication {
        val uris = java.util.concurrent.CopyOnWriteArrayList<String>()
        application { v1Module(uris) }

        val known = client.get("/v1/examenes/$examenId/preguntas")
        val unknown = client.get("/v1/examenes/22222222-2222-4222-8222-222222222222/preguntas")
        val notUuid = client.get("/v1/examenes/not-a-uuid/preguntas")
        val withSession = client.get("/v1/examenes/$examenId/preguntas") { header(io.ktor.http.HttpHeaders.Authorization, "Bearer some.jwt.token") }

        listOf(known, unknown, notUuid, withSession).forEach { assertEquals(HttpStatusCode.Gone, it.status) }
        assertEquals(known.bodyAsText(), unknown.bodyAsText(), "the body never depends on the exam")
        assertEquals(known.bodyAsText(), notUuid.bodyAsText())
        assertEquals(known.bodyAsText(), withSession.bodyAsText(), "a session does not reopen the route")
        assertTrue(uris.isEmpty(), "$uris")
    }

    @Test fun theNeighbourPublicReadsStillWork() = testApplication {
        val uris = java.util.concurrent.CopyOnWriteArrayList<String>()
        application { v1Module(uris) }

        assertEquals(HttpStatusCode.OK, client.get("/v1/examenes/$examenId").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/examenes").status)
        assertTrue(uris.none { it.contains("preguntas") }, "the exam reads never touch questions: $uris")
    }

    @Test fun theRetiredRouteIsRegisteredAsLegacyWithoutASuccessorLink() {
        val ep = LegacyEndpoints.EP_128
        assertEquals("GET /v1/examenes/{examenId}/preguntas", "${ep.method} ${ep.legacyPath}")
        assertEquals(null, ep.successorPath, "D2-08 needs an attempt id the legacy URL does not carry")
        assertTrue(ep in LegacyEndpoints.RETIRED_PUBLIC_READS)
    }


    /**
     * El gateway esta migrado a [SupabaseRequestTransport], asi que hereda la
     * politica de credenciales. Con una clave que no tiene forma de JWT —una
     * `sb_secret_...`— la credencial viaja SOLO como `apikey`.
     */
    @Test fun theMigratedGatewayAppliesTheCredentialPolicy() {
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(
            secretKey = "sb_secret_test_fixture",
            record = { requests += it },
        ) { V1StubResponse(200, "[]") }

        gateway.listEspecialidades()

        val sent = requests.single()
        assertEquals("sb_secret_test_fixture", sent.headers().firstValue("apikey").orElseThrow())
        assertTrue(
            sent.headers().firstValue("Authorization").isEmpty,
            "una clave sin forma de JWT no puede viajar como Bearer",
        )
    }

    /**
     * K0-7 (EX-16): `razonado` es un filtro público válido. El catálogo público
     * sirve exámenes razonados, pero la lista blanca del parámetro sólo conocía
     * `rafaga` e `inmersivo`, así que pedirlos por tipo respondía 400.
     *
     * Se afirma también el control: un tipo inventado sigue siendo 400 y no sale
     * ninguna consulta.
     */
    @Test fun theReasonedTypeIsAValidPublicFilterAndAnUnknownOneIsStillRejected() = testApplication {
        val uris = java.util.concurrent.CopyOnWriteArrayList<String>()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, ErrorDto(error = "bad_request", message = cause.message ?: ""))
                }
            }
            routing { v1Routes(gatewayWith(record = { uris += it.uri().toString() }) { V1StubResponse(200, "[]") }) }
        }

        val razonado = client.get("/v1/examenes?tipoExamen=razonado")

        assertEquals(HttpStatusCode.OK, razonado.status, razonado.bodyAsText())
        assertTrue(uris.any { it.contains("tipo_examen=eq.razonado") }, "no se filtró por razonado: $uris")

        uris.clear()
        val inventado = client.get("/v1/examenes?tipoExamen=marciano")
        assertEquals(HttpStatusCode.BadRequest, inventado.status)
        assertTrue(uris.isEmpty(), "un tipo inválido no puede llegar a consultar: $uris")
    }

    // -----------------------------------------------------------------

    private fun examenRow(
        publicadoEn: String?,
        tipoExamen: String = "rafaga",
        tipoAcceso: String = "gratuito",
    ): String =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"ESP","titulo":"Examen",""" +
            """"descripcion":"","tipo_examen":"$tipoExamen","tipo_acceso":"$tipoAcceso",""" +
            """"activo":true,"es_oficial":false,"total_preguntas":1,"publicado_en":${publicadoEn ?: "null"}}"""

    private fun gatewayWith(
        secretKey: String = "stub-key",
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): SupabaseV1Gateway = SupabaseV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = secretKey,
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )
}

internal data class V1StubResponse(val status: Int, val body: String, val contentRange: String? = null)

/**
 * `HttpClient` minimo de la JDK, mismo patron que el de
 * `AdminDashboardOverviewTest`. A mano y no con un doble de libreria porque lo
 * que hay que observar es exactamente lo que sale por el cable: la URL con sus
 * filtros y las cabeceras de credencial.
 */
internal class V1StubHttpClient(
    private val record: (HttpRequest) -> Unit,
    private val responder: (String) -> V1StubResponse,
) : HttpClient() {
    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
    override fun connectTimeout(): Optional<Duration> = Optional.empty()
    override fun followRedirects(): Redirect = Redirect.NEVER
    override fun proxy(): Optional<ProxySelector> = Optional.empty()
    override fun sslContext(): SSLContext = SSLContext.getDefault()
    override fun sslParameters(): SSLParameters = SSLParameters()
    override fun authenticator(): Optional<Authenticator> = Optional.empty()
    override fun version(): Version = Version.HTTP_1_1
    override fun executor(): Optional<Executor> = Optional.empty()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        record(request)
        return V1StubHttpResponse(request, responder(request.uri().toString())) as HttpResponse<T>
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
}

internal class V1StubHttpResponse(
    private val request: HttpRequest,
    private val stub: V1StubResponse,
) : HttpResponse<String> {
    override fun statusCode(): Int = stub.status
    override fun request(): HttpRequest = request
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders {
        val headers = stub.contentRange?.let { mapOf("content-range" to listOf(it)) } ?: emptyMap()
        return HttpHeaders.of(headers) { _, _ -> true }
    }
    override fun body(): String = stub.body
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = request.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
