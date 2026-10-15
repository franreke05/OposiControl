package com.oposicontrol.backend

import com.oposicontrol.backend.inventory.CombinedRouteFixture
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Auditoria de controles (E1): los escritores legacy `/admin/{audit,settings,exam-access,news-types}` y `/content/...` (salvo
 * noticias) sin llamador vivo responden 410 ADMIN_LEGACY_ROUTE_DISABLED sobre el arbol REAL ([installCombinedRoutes]),
 * autenticados como admin activo, ANTES de leer el cuerpo (uno mal formado tambien es 410) y siguen contados (`Deprecation`).
 * Control negativo: con el Application.kt anterior `POST /content/etiquetas` con este cuerpo era 400/500, no 410.
 */
class LegacyControlsWritersRetiredTest {
    private val adminId = "11111111-1111-4111-8111-111111111111"
    private val upstream = CopyOnWriteArrayList<HttpRequest>()

    private fun deps(): CombinedRoutingDeps {
        val base = CombinedRouteFixture.stubDeps()
        val adminAuth = AppV1Gateway.forTesting(
            "https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true },
            V1StubHttpClient({ upstream += it }) { uri ->
                when {
                    "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, """[{"rol_id":"r","roles_app":{"codigo":"admin"}}]""")
                    "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, """[{"estado_cuenta":"activa"}]""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        return CombinedRoutingDeps(
            contentGateway = base.contentGateway, newsGateway = base.newsGateway, tagSearchGateway = base.tagSearchGateway,
            adminGateway = base.adminGateway, adminAnalyticsGateway = base.adminAnalyticsGateway,
            adminRankingsGateway = base.adminRankingsGateway, adminDashboardOverviewGateway = base.adminDashboardOverviewGateway,
            v1Gateway = base.v1Gateway, adminV1Gateway = base.adminV1Gateway, oposiBotsUserAdminGateway = base.oposiBotsUserAdminGateway,
            appV1Gateway = adminAuth, jwtVerifier = base.jwtVerifier, allowDevUser = false, appEnv = "production",
            authRuntime = base.authRuntime, identityResolver = null, readinessCheck = { "UP" },
        )
    }

    private fun ApplicationTestBuilder.mount() = application {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { gatewayBearerAuth("production", { it }) }
        routing { installCombinedRoutes(deps()) }
    }

    private fun concrete(path: String) = path.replace(Regex("\\{[^}]+}"), "22222222-2222-4222-8222-222222222222")

    private val retired: List<LegacyEndpoint> = LegacyEndpoints.RETIRED_LEGACY_WRITERS + LegacyEndpoints.EP_145

    @Test
    fun everyRetiredWriterAnswers410OnTheRealTreeWithoutReadingTheBody() = testApplication {
        mount()
        for (ep in retired) {
            val label = "${ep.endpointId} ${ep.method} ${ep.legacyPath}"
            val response = client.request(concrete(ep.legacyPath)) {
                method = HttpMethod.parse(ep.method)
                header(HttpHeaders.Authorization, "Bearer $adminId")
                contentType(ContentType.Application.Json)
                setBody("""{"nombre": """)
            }
            assertEquals(HttpStatusCode.Gone, response.status, "$label: ${response.bodyAsText()}")
            val code = Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
            assertEquals("ADMIN_LEGACY_ROUTE_DISABLED", code, label)
            assertTrue(response.headers[LegacyHeaders.DEPRECATION] != null, "$label: sigue contada (legacyRoute)")
        }
        // Solo la autorizacion (rol + estado de cuenta) toca upstream: ninguna escritura llega a Supabase.
        assertTrue(upstream.all { "/rest/v1/roles_usuario" in it.uri().toString() || "/rest/v1/perfiles_usuario" in it.uri().toString() }, "$upstream")
    }

    @Test
    fun theRetiredWritersAreUniqueRegisteredAndKeepNewsAndReadersOut() {
        val keys = LegacyEndpoints.RETIRED_LEGACY_WRITERS.map { "${it.method} ${it.legacyPath}" }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(37, keys.size)
        assertTrue(keys.none { "/content/noticias" in it }, "FA11: noticias legacy siguen vivas")
        assertTrue(LegacyEndpoints.RETIRED_LEGACY_WRITERS.none { it.method == "GET" })
        assertTrue(LegacyEndpoints.RETIRED_LEGACY_WRITERS.all { it.successorPath == null })
        val ids = (LegacyEndpoints.RETIRED_LEGACY_WRITERS + LegacyEndpoints.SLICE_0 + LegacyEndpoints.RETIRED_ADMIN_WRITERS).map { it.endpointId }
        assertEquals(ids.size, ids.toSet().size, "un id de endpoint por ruta")
    }

    @Test
    fun theLegacyReadersAndNewsWritersAreStillServed() = testApplication {
        mount()
        for (path in listOf("/content/especialidades", "/content/etiquetas", "/admin/news-types", "/admin/settings")) {
            val status = client.request(path) { header(HttpHeaders.Authorization, "Bearer $adminId") }.status
            assertNotEquals(HttpStatusCode.Gone, status, path)
            assertNotEquals(HttpStatusCode.NotFound, status, path)
        }
        val news = client.request("/content/noticias") {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $adminId")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status
        assertNotEquals(HttpStatusCode.Gone, news)
    }
}
