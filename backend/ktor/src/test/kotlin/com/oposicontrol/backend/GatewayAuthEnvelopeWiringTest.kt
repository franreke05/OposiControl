package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER-API-018: el cableado REAL de `gateway-auth` ([gatewayBearerAuth]) con el StatusPages de produccion
 * ([installBackendStatusPages]). Sin token o con un token que no verifica -> 401 con el sobre UNAUTHENTICATED,
 * `requestId` = cabecera `X-Request-Id` y `WWW-Authenticate: Bearer`. JWKS caido -> 503 UPSTREAM_UNAVAILABLE con
 * `Retry-After` (antes: 401 desnudo, sin cuerpo ni traza, que ademas cerraba la sesion del cliente).
 */
class GatewayAuthEnvelopeWiringTest {
    private val userId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun aMissingBearerIsTheCanonicalUnauthenticatedEnvelope() = withGatewayApp({ userId }) { get ->
        assertUnauthenticatedEnvelope(get(null))
    }

    @Test
    fun aTokenThatDoesNotVerifyIsTheCanonicalUnauthenticatedEnvelope() = withGatewayApp({ error("firma invalida") }) { get ->
        assertUnauthenticatedEnvelope(get("header.payload.signature"))
    }

    @Test
    fun anUnavailableJwksIsA503UpstreamUnavailableWithRetryAfterNotA401() =
        withGatewayApp({ throw java.io.IOException("JWKS HTTP 503") }) { get ->
            val response = get("header.payload.signature")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(JWKS_UNAVAILABLE_RETRY_AFTER_SECONDS.toString(), response.headers[HttpHeaders.RetryAfter])
            val body = bodyOf(response)
            assertEquals("UPSTREAM_UNAVAILABLE", body["code"]!!.jsonPrimitive.content)
            assertEquals(response.headers["X-Request-Id"], body["requestId"]!!.jsonPrimitive.content)
        }

    @Test
    fun aVerifiedTokenStillReachesTheHandler() = withGatewayApp({ userId }) { get ->
        assertEquals(HttpStatusCode.OK, get("header.payload.signature").status)
    }

    @Test
    fun aRouteThatAnswersItsOwn401BodyIsNotRewritten() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/own-401") {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto(error = "custom", message = "propio", code = "INVALID_CREDENTIALS"))
                }
            }
        }
        val response = client.get("/own-401")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("INVALID_CREDENTIALS", bodyOf(response)["code"]!!.jsonPrimitive.content)
    }

    private suspend fun assertUnauthenticatedEnvelope(response: HttpResponse) {
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Bearer"), response.headers.toString())
        val body = bodyOf(response)
        assertEquals("UNAUTHENTICATED", body["code"]!!.jsonPrimitive.content)
        val requestId = response.headers["X-Request-Id"]
        assertTrue(!requestId.isNullOrBlank())
        assertEquals(requestId, body["requestId"]!!.jsonPrimitive.content)
    }

    private suspend fun bodyOf(response: HttpResponse): JsonObject = run {
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun withGatewayApp(
        verify: (String) -> String,
        block: suspend (suspend (String?) -> HttpResponse) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            install(Authentication) { gatewayBearerAuth("prod", verify, expectedStaticToken = { "" }) }
            routing {
                authenticate("gateway-auth") {
                    get("/api/v1/admin/probe") { call.respond(HttpStatusCode.OK) }
                }
            }
        }
        block { token ->
            client.get("/api/v1/admin/probe") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        }
    }
}
