package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 0 · compatibilidad legacy (`specs.legacy`). */
class LegacyRouteTest {

    private val endpoint = LegacyEndpoint(
        endpointId = "EP-999",
        method = "GET",
        legacyPath = "/legacy/cosas",
        successorPath = "/api/v1/app/cosas",
        deprecatedAt = Instant.parse("2026-09-28T00:00:00Z"),
    )

    @Test
    fun aLegacyRouteCarriesDeprecationAndSuccessorLinkButNoSunsetUntilApproved() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/legacy/cosas") { call.respond(listOf("a")) }.legacyRoute(endpoint, registry)
                get("/legacy/otra") { call.respond(listOf("b")) }
            }
        }

        val response = client.get("/legacy/cosas")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("@1790553600", response.headers[LegacyHeaders.DEPRECATION])
        assertEquals("</api/v1/app/cosas>; rel=\"successor-version\"", response.headers[HttpHeaders.Link])
        assertNull(response.headers[LegacyHeaders.SUNSET])

        val sibling = client.get("/legacy/otra")
        assertNull(sibling.headers[LegacyHeaders.DEPRECATION], "el plugin es de ruta: no marca rutas hermanas")

        client.get("/legacy/cosas")
        val scrape = registry.scrape()
        assertTrue(scrape.contains("legacy_route_requests_total{endpoint=\"EP-999\"} 2.0"), scrape)
    }

    @Test
    fun anApprovedSunsetIsPublishedAsImfFixdate() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/legacy/cosas") { call.respond(listOf("a")) }
                    .legacyRoute(endpoint.copy(sunsetAt = Instant.parse("2027-03-01T00:00:00Z")), registry)
            }
        }
        assertEquals("Mon, 1 Mar 2027 00:00:00 GMT", client.get("/legacy/cosas").headers[LegacyHeaders.SUNSET])
    }

    @Test
    fun legacyErrorsUseTheSameCanonicalEnvelopeAndStillCarryDeprecation() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/legacy/cosas") { throw IllegalStateException("Supabase GET /rest/v1/cosas -> 500: x-leak") }
                    .legacyRoute(endpoint, registry)
            }
        }
        val response = client.get("/legacy/cosas") { header("X-Request-Id", "legacy-error-0001") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("@1790553600", response.headers[LegacyHeaders.DEPRECATION])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UPSTREAM_ERROR", body["code"]!!.jsonPrimitive.content)
        assertEquals("upstream_error", body["error"]!!.jsonPrimitive.content)
        assertEquals("legacy-error-0001", body["requestId"]!!.jsonPrimitive.content)
    }

    // --- adaptador -> servicio canonico ---

    @Serializable
    data class CanonicalCosa(val id: String, val nombre: String)

    @Serializable
    data class LegacyCosa(val codigo: String, val titulo: String)

    /** Un "servicio canonico" de juguete: la unica sede de la regla de negocio. */
    private class CosasService {
        var calls = 0
        suspend fun get(id: String): CanonicalCosa {
            calls++
            if (id == "nope") throw CanonicalErrorCode.NOT_FOUND.exception("La cosa no existe.")
            return CanonicalCosa(id, "Cosa $id")
        }
    }

    @Test
    fun theLegacyAdapterCallsTheSameCanonicalServiceAndOnlyTranslates() = testApplication {
        val service = CosasService()
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val adapter = LegacyAdapter<String, String, CanonicalCosa, LegacyCosa>(
            endpoint = endpoint,
            toCanonical = { legacyCode -> legacyCode.removePrefix("L-") },
            canonical = service::get,
            toLegacy = { LegacyCosa(codigo = "L-${it.id}", titulo = it.nombre) },
        )
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/api/v1/app/cosas/{id}") { call.respond(service.get(call.parameters["id"]!!)) }
                get("/legacy/cosas/{codigo}") { call.respond(adapter(call.parameters["codigo"]!!)) }
                    .legacyRoute(endpoint, registry)
            }
        }

        val canonical = Json.parseToJsonElement(client.get("/api/v1/app/cosas/7").bodyAsText()).jsonObject
        val legacy = Json.parseToJsonElement(client.get("/legacy/cosas/L-7").bodyAsText()).jsonObject
        assertEquals("Cosa 7", canonical["nombre"]!!.jsonPrimitive.content)
        assertEquals("Cosa 7", legacy["titulo"]!!.jsonPrimitive.content)
        assertEquals("L-7", legacy["codigo"]!!.jsonPrimitive.content)
        assertEquals(2, service.calls, "las dos rutas pasan por el MISMO servicio")

        val missing = client.get("/legacy/cosas/L-nope")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NOT_FOUND", Json.parseToJsonElement(missing.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    // --- las 9 rutas de SLICE 0 ---

    @Test
    fun sliceZeroDeclaresExactlyTheNineCompatEndpointsWithoutSunset() {
        assertEquals(
            setOf("EP-046", "EP-015", "EP-025", "EP-111", "EP-145", "EP-199", "EP-200", "EP-229", "EP-144"),
            LegacyEndpoints.SLICE_0.map { it.endpointId }.toSet(),
        )
        assertTrue(LegacyEndpoints.SLICE_0.all { it.sunsetAt == null })
        assertTrue(LegacyEndpoints.SLICE_0.all { it.successorPath.startsWith("/api/v1/") })
        assertTrue(LegacyEndpoints.SLICE_0.none { it.endpointId in setOf("EP-124", "EP-218", "EP-192") })
    }

    /** Cableado real: `/content/noticias` (EP-111/199/229/015) sale deprecada; `GET /{id}` no. */
    @Test
    fun theRealNewsContentRoutesAreMarkedPerLeaf() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            install(Authentication) {
                bearer("gateway-auth") { authenticate { UserIdPrincipal("admin") } }
            }
            routing {
                authenticate("gateway-auth") {
                    route("/content") { newsContentRoutes(InMemoryNewsGateway(), onIndexed = {}, onRemoved = {}) }
                }
            }
        }
        val auth = "Bearer test"
        val list = client.get("/content/noticias") { header(HttpHeaders.Authorization, auth) }
        assertEquals("</api/v1/admin/noticias>; rel=\"successor-version\"", list.headers[HttpHeaders.Link])
        val one = client.get("/content/noticias/x") { header(HttpHeaders.Authorization, auth) }
        assertNull(one.headers[LegacyHeaders.DEPRECATION])
        val removed = client.delete("/content/noticias/x") { header(HttpHeaders.Authorization, auth) }
        assertEquals("</api/v1/admin/noticias/{noticiaId}>; rel=\"successor-version\"", removed.headers[HttpHeaders.Link])
        val created = client.post("/content/noticias") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, created.status)
        assertEquals("@${LegacyEndpoints.SLICE_0_DEPRECATED_AT.epochSecond}", created.headers[LegacyHeaders.DEPRECATION])
    }

    /** SLICE 1: EP-124 y EP-218 reciben la deprecacion en su corte, aparte del lote de SLICE 0, con su sucesor canonico y sin Sunset. */
    @Test
    fun slice1DeprecatesEp124AndEp218SeparatelyWithTheirCanonicalSuccessors() {
        assertEquals(setOf("EP-124", "EP-218"), LegacyEndpoints.SLICE_1.map { it.endpointId }.toSet())
        assertEquals("/api/v1/app/me", LegacyEndpoints.EP_124.successorPath)
        assertEquals("/v1/auth/me", LegacyEndpoints.EP_124.legacyPath)
        assertEquals("/api/v1/app/me/onboarding", LegacyEndpoints.EP_218.successorPath)
        assertEquals("/v1/auth/register/profile", LegacyEndpoints.EP_218.legacyPath)
        assertTrue(LegacyEndpoints.SLICE_1.all { it.sunsetAt == null })
        assertTrue(LegacyEndpoints.SLICE_1.all { it.successorPath.startsWith("/api/v1/") })
        assertTrue(LegacyEndpoints.SLICE_0.map { it.endpointId }.intersect(LegacyEndpoints.SLICE_1.map { it.endpointId }.toSet()).isEmpty())
    }
}
