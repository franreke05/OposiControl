package com.oposicontrol.backend

import io.ktor.client.request.get
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
 * EXAM-009: la frontera entre lo publicado y lo que aun es un borrador.
 *
 * `GET /v1/examenes/{id}/preguntas` es PUBLICA por diseno —la consume OposiBot
 * sin sesion— y debe seguir siendolo. Lo que estaba mal no era donde se
 * registra la ruta, sino que el gateway no aplicaba el mismo filtro de
 * publicacion que su funcion vecina. Estos casos fijan esa frontera desde
 * fuera, que es el unico sitio desde el que se puede comprobar.
 */
class SupabaseV1GatewayExamAccessTest {

    private val examenId = "11111111-1111-4111-8111-111111111111"

    /**
     * El caso del defecto. Un anonimo con el UUID de un examen rafaga gratuito
     * **en borrador** recibia 200 con enunciados y opciones, mientras
     * `GET /v1/examenes/{id}` devolvia 404 para ese mismo examen.
     *
     * Dos aserciones y las dos hacen falta:
     *
     * 1. **Comportamiento**: el resultado es `NotFound`, no `Forbidden`. Un 403
     *    confirmaria a un anonimo que ese UUID nombra un examen real, que es
     *    justo el hecho que un borrador no debe revelar.
     * 2. **La query**, que es la garantia de verdad. Sin esta segunda asercion
     *    el test pasaria por accidente si alguien filtrara en memoria, y filtrar
     *    en memoria no es lo que se esta fijando: se fija que **el borrador no
     *    sale de la base**.
     */
    @Test fun draftExamServesNoQuestionsToAnonymous() {
        val uris = mutableListOf<String>()
        val gateway = gatewayWith(record = { uris += it.uri().toString() }) { uri ->
            when {
                // El doble solo entrega el borrador si la query NO filtra
                // publicacion: asi el test falla si el filtro desaparece.
                uri.contains("/rest/v1/examenes") && !uri.contains("publicado_en=not.is.null") ->
                    V1StubResponse(200, "[${examenRow(publicadoEn = null)}]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, "[]")
            }
        }

        val result = gateway.getPreguntasExamenRafaga(examenId)

        assertEquals(V1PreguntasExamenResult.NotFound, result)
        assertTrue(
            uris.first().contains("publicado_en=not.is.null"),
            "la consulta del examen debe descartar borradores en la base, no en memoria: ${uris.first()}",
        )
        assertEquals(1, uris.size, "un borrador no debe provocar ni una peticion mas de contenido")
    }

    /**
     * E2-P03: un examen PROGRAMADO (`publicado_en` futuro) no sale por las rutas publicas /v1 antes de su hora. El doble
     * solo lo entrega si la query no filtra `publicado_en=lte.<ahora>`: lista, detalle y preguntas filtran en la base.
     */
    @Test fun scheduledExamIsNotServedBeforeItsPublicationTime() {
        val uris = mutableListOf<String>()
        val gateway = gatewayWith(record = { uris += java.net.URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }) { uri ->
            when {
                uri.contains("/rest/v1/examenes") && !uri.contains("publicado_en=lte.") ->
                    V1StubResponse(200, "[${examenRow(publicadoEn = "\"2999-01-01T00:00:00Z\"")}]")
                else -> V1StubResponse(200, "[]")
            }
        }

        assertTrue(gateway.listExamenes(V1ExamenesFilter()).isEmpty())
        assertEquals(null, gateway.getExamen(examenId))
        assertEquals(V1PreguntasExamenResult.NotFound, gateway.getPreguntasExamenRafaga(examenId))
        assertEquals(3, uris.size)
        uris.forEach { assertTrue(Regex("publicado_en=not\\.is\\.null&publicado_en=lte\\.\\d{4}-").containsMatchIn(it), it) }
    }

    /**
     * No-regresion. Sin este caso, "devolver NotFound siempre" pasaria el test
     * principal y el endpoint quedaria roto para su uso legitimo.
     */
    @Test fun publishedFreeBurstExamStillServesQuestions() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("/rest/v1/examenes") ->
                    V1StubResponse(200, "[${examenRow(publicadoEn = "\"2026-01-01T00:00:00Z\"")}]")
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"examen_id":"$examenId","pregunta_id":"P1","posicion":1}]""",
                )
                uri.contains("/rest/v1/preguntas?") -> V1StubResponse(
                    200,
                    """[{"id":"P1","codigo_pregunta":"P-1","titulo":"¿Cuál?","tipo_pregunta":"rafaga","activa":true}]""",
                )
                uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(
                    200,
                    """[{"id":"O1","pregunta_id":"P1","letra":"A","texto_opcion":"Primera","posicion":1,"es_correcta":true},""" +
                        """{"id":"O2","pregunta_id":"P1","letra":"B","texto_opcion":"Segunda","posicion":2,"es_correcta":false}]""",
                )
                else -> V1StubResponse(200, "[]")
            }
        }

        val result = assertIs<V1PreguntasExamenResult.Success>(gateway.getPreguntasExamenRafaga(examenId))

        assertEquals(1, result.data.preguntas.size)
        assertEquals("P1", result.data.preguntas.single().id)
        assertEquals(listOf("A", "B"), result.data.preguntas.single().opciones.map { it.letra })
    }

    /**
     * Una pregunta anulada en ESTE examen ya no se sirve como si fuera normal: el DTO expone
     * `anulada` y `motivoAnulacion` (aditivo; sin puntuación aquí).
     */
    @Test fun annulledQuestionsAreExposedAsAnnulledWithTheirReason() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("/rest/v1/examenes") ->
                    V1StubResponse(200, "[${examenRow(publicadoEn = "\"2026-01-01T00:00:00Z\"")}]")
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"examen_id":"$examenId","pregunta_id":"P1","posicion":1,"anulada":true,"motivo_anulacion":"Error del tribunal"},""" +
                        """{"examen_id":"$examenId","pregunta_id":"P2","posicion":2,"anulada":false,"motivo_anulacion":null},""" +
                        """{"examen_id":"$examenId","pregunta_id":"P3","posicion":3}]""",
                )
                uri.contains("/rest/v1/preguntas?") -> V1StubResponse(
                    200,
                    """[{"id":"P1","titulo":"Uno","tipo_pregunta":"rafaga","activa":true},""" +
                        """{"id":"P2","titulo":"Dos","tipo_pregunta":"rafaga","activa":true},""" +
                        """{"id":"P3","titulo":"Tres","tipo_pregunta":"rafaga","activa":true}]""",
                )
                else -> V1StubResponse(200, "[]")
            }
        }

        val result = assertIs<V1PreguntasExamenResult.Success>(gateway.getPreguntasExamenRafaga(examenId))

        val (p1, p2, p3) = result.data.preguntas
        assertTrue(p1.anulada)
        assertEquals("Error del tribunal", p1.motivoAnulacion)
        assertEquals(false, p2.anulada)
        assertEquals(null, p2.motivoAnulacion)
        assertEquals(false, p3.anulada, "sin columna, default false")
    }

    /**
     * No-regresion sobre la otra mitad: el arreglo no puede colapsar las dos
     * respuestas en una. Un examen **publicado** que no es gratuito sigue siendo
     * `Forbidden`, porque ahi su existencia ya es publica por
     * `GET /v1/examenes/{id}` y no se revela nada nuevo.
     */
    @Test fun publishedPremiumExamStaysForbidden() {
        val gateway = gatewayWith { uri ->
            if (uri.contains("/rest/v1/examenes")) {
                V1StubResponse(
                    200,
                    "[${examenRow(publicadoEn = "\"2026-01-01T00:00:00Z\"", tipoAcceso = "premium")}]",
                )
            } else {
                V1StubResponse(200, "[]")
            }
        }

        assertEquals(V1PreguntasExamenResult.Forbidden, gateway.getPreguntasExamenRafaga(examenId))
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
