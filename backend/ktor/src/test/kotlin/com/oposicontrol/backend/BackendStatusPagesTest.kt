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
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DTO de prueba: un campo obligatorio que la respuesta "del upstream" no trae. */
@Serializable
internal data class StatusPagesProbe(val required: String)

/**
 * `installBackendStatusPages()`: la traducción de excepciones de PRODUCCIÓN, no una
 * copia. Cada caso lanza lo que lanzaría una ruta real y mira el cuerpo y las
 * cabeceras que saldrían al cliente.
 */
class BackendStatusPagesTest {

    private val requestId = "status-pages-probe-0001"

    /**
     * F-08. Una respuesta de Supabase que no casa con el DTO es un fallo del
     * CONTRATO con el servicio de datos: 502 con referencia. Antes caía en el
     * handler de `IllegalArgumentException` (del que hereda) y salía como 400
     * "petición inválida" con el mensaje de kotlinx: nombres de clase y de campos.
     */
    @Test
    fun anUpstreamPayloadThatDoesNotMatchItsDtoIsA502WithReferenceAndNoInternals() = statusPagesApp {
        val response = client.get("/contract") { header("X-Request-Id", requestId) }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"code\":\"UPSTREAM_CONTRACT_ERROR\""), body)
        assertTrue(body.contains("Referencia: $requestId"), body)
        listOf("StatusPagesProbe", "required", "Field", "kotlinx", "Exception", "x-probe-c").forEach { leak ->
            assertFalse(body.contains(leak), "el 502 filtra '$leak': $body")
        }
    }

    /** Y un cuerpo de PETICIÓN mal formado sigue siendo un 400 del cliente. */
    @Test
    fun aMalformedRequestBodyIsStillA400() = statusPagesApp {
        val response = client.post("/body") {
            contentType(ContentType.Application.Json)
            setBody("""{"no_es":"el_dto"""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"MALFORMED_BODY\""))
    }

    /** Un rechazo transitorio de la base (40P01/55P03/57014) es 503 con `Retry-After`. */
    @Test
    fun aTransientDatabaseRejectionIsA503WithRetryAfter() = statusPagesApp {
        val response = client.get("/transient")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("2", response.headers[HttpHeaders.RetryAfter])
        val body = response.bodyAsText()
        assertTrue(body.contains("database_lock_timeout"), body)
        assertFalse(body.contains("canceling statement"), body)
    }

    /** Un rechazo DEFINITIVO no lleva `Retry-After`: repetirlo daría lo mismo. */
    @Test
    fun aMalformedIdentifierIsA422WithoutRetryAfter() = statusPagesApp {
        val response: HttpResponse = client.get("/malformed")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertNull(response.headers[HttpHeaders.RetryAfter])
        assertFalse(response.bodyAsText().contains("x-probe-c"))
    }

    @Test
    fun anUpstreamFailureIsA502WithReferenceAndWithoutTheUpstreamBody() = statusPagesApp {
        val response = client.get("/upstream") { header("X-Request-Id", requestId) }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Referencia: $requestId"), body)
        assertFalse(body.contains("x-hint-c"), body)
    }

    // -----------------------------------------------------------------

    private fun statusPagesApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/contract") {
                    // Lo mismo que hace un `decodeList` sobre una respuesta de Supabase
                    // a la que le falta una columna.
                    call.respond(Json.decodeFromString<StatusPagesProbe>("""{"x-probe-c":1}"""))
                }
                post("/body") {
                    call.respond(call.receive<StatusPagesProbe>())
                }
                get("/transient") {
                    val body = """{"code":"55P03","details":null,"hint":null,"message":"canceling statement due to lock timeout"}"""
                    throw checkNotNull(mapSupabaseDomainError(503, body))
                }
                get("/malformed") {
                    val body = """{"code":"22P02","message":"invalid input syntax for type uuid: \"x-probe-c\""}"""
                    throw checkNotNull(mapSupabaseDomainError(400, body))
                }
                get("/upstream") {
                    throw IllegalStateException("""Supabase GET /rest/v1/examenes -> 400: {"hint":"x-hint-c"}""")
                }
            }
        }
        block()
    }
}
