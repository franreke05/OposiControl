package com.oposicontrol.backend

import com.oposicontrol.backend.inventory.CombinedRouteFixture
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comportamiento de los perfiles `bot` y `control` con el cableado REAL de produccion ([configureServerProfile]):
 * salud/readiness por perfil y statelessness del perfil `bot` (dos instancias independientes, mismo upstream falso).
 */
class ServerProfileRuntimeTest {

    // ------------------------------------------------------------------ CAND-03: trabajos periodicos del Bot por perfil

    @Test
    fun botJobsRunOnlyWhereTheBotRoutesAreMounted() {
        assertEquals(
            setOf(ServerProfile.BOT, ServerProfile.COMBINED),
            ServerProfile.entries.filter { it.runsBotJobs }.toSet(),
            "the daily challenge job must not start in consumer or control",
        )
        for (profile in ServerProfile.entries) assertEquals(profile.mountsBot, profile.runsBotJobs, "$profile")
    }

    // ------------------------------------------------------------------ /health/live y /health/ready por perfil

    @Test
    fun liveAndReadyAreServedByBotAndControlAndReadyReflectsTheCriticalDependency() {
        for (profile in listOf(ServerProfile.BOT, ServerProfile.CONTROL, ServerProfile.COMBINED)) {
            var supabase = "UP"
            testApplication {
                application { configureServerProfile(profile, testJson, CombinedRouteFixture.stubDeps(profile) { supabase }) }

                assertEquals(HttpStatusCode.OK, client.get("/health/live").status, "$profile live")
                val ready = client.get("/health/ready")
                assertEquals(HttpStatusCode.OK, ready.status, "$profile ready")
                assertEquals("UP", Json.parseToJsonElement(ready.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

                supabase = "DOWN"
                val down = client.get("/health/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, down.status, "$profile ready must not be always-200")
                assertEquals("DOWN", Json.parseToJsonElement(down.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
                // La liveness no depende de la dependencia externa.
                assertEquals(HttpStatusCode.OK, client.get("/health/live").status, "$profile live while supabase is DOWN")

                supabase = "UNKNOWN"
                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status, "$profile ready with UNKNOWN")
            }
        }
    }

    @Test
    fun theBotProfileAnswers404ForAdminSurfaceAndTheControlProfileForConsumerAuth() = testApplication {
        application { configureServerProfile(ServerProfile.BOT, testJson, CombinedRouteFixture.stubDeps(ServerProfile.BOT)) }
        // En bot ninguna ruta admin existe: 404 (no 401/403, que revelarian que esta montada).
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/admin/dashboard/overview").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/tags").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/content/noticias").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/admin/backend/restart").status)
    }

    @Test
    fun theControlProfileKeepsAdminGuardedAndDoesNotMountConsumerAuth() = testApplication {
        application { configureServerProfile(ServerProfile.CONTROL, testJson, CombinedRouteFixture.stubDeps(ServerProfile.CONTROL)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/dashboard/overview").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/auth/login").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/app/me").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/app/home").status)
    }

    // ------------------------------------------------------------------ statelessness del perfil bot

    private class Instance(val app: TestApplication, val env: CanonicalTestEnv) {
        suspend fun stop() = app.stop()
    }

    /** Una instancia `bot` completa e independiente (propio runtime, resolver, limiter y gateways) sobre el MISMO upstream. */
    private fun botInstance(sharedProfileRow: () -> String, gatewayLogins: AtomicInteger): Instance {
        val env = CanonicalTestEnv(gateway = LoginOnlyGateway(gatewayLogins))
        env.upstream.onGet = { path -> if ("perfiles_usuario" in path) sharedProfileRow() else "[]" }
        val deps = CombinedRouteFixture.stubDeps(ServerProfile.BOT)
        val wired = CombinedRoutingDeps(
            contentGateway = null, newsGateway = null, tagSearchGateway = null, adminGateway = null,
            adminAnalyticsGateway = null, adminRankingsGateway = null, adminDashboardOverviewGateway = null,
            v1Gateway = deps.v1Gateway, adminV1Gateway = null, oposiBotsUserAdminGateway = null,
            appV1Gateway = deps.appV1Gateway, jwtVerifier = deps.jwtVerifier, allowDevUser = false, appEnv = "production",
            authRuntime = env.runtime(), identityResolver = null,
        )
        val app = TestApplication { application { configureServerProfile(ServerProfile.BOT, testJson, wired) } }
        runBlocking { app.start() }
        return Instance(app, env)
    }

    @Test
    fun aSessionIssuedByOneBotInstanceIsAcceptedByAnotherAndSurvivesTheFirstOneStopping() = runBlocking {
        val logins = AtomicInteger()
        val sharedRow = { profileRowJson(onboarding = true) }
        val a = botInstance(sharedRow, logins)
        val b = botInstance(sharedRow, logins)
        try {
            // 1) login en la instancia A: emite el token.
            val login = a.app.client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"identifier":"person@example.com","password":"safe-password"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val token = Json.parseToJsonElement(login.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
            assertEquals(1, logins.get())

            // 2) el mismo token funciona en la instancia B (que nunca lo vio): no hay sesion en memoria de proceso.
            val meOnB = b.app.client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, meOnB.status)
            val meOnA = a.app.client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, meOnA.status)
            // Misma respuesta de negocio en ambas (salvo el X-Request-Id de cada peticion, que va en cabecera).
            assertEquals(meOnA.bodyAsText(), meOnB.bodyAsText())

            // 3) sin token, ambas rechazan igual (el 401 no depende de estado local).
            assertEquals(HttpStatusCode.Unauthorized, a.app.client.get("/api/v1/app/me").status)
            assertEquals(HttpStatusCode.Unauthorized, b.app.client.get("/api/v1/app/me").status)

            // 4) la instancia A se para (rolling deploy): B sigue sirviendo la misma sesion sin perdida de correccion.
            a.stop()
            val after = b.app.client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, after.status)
            assertTrue(after.bodyAsText().contains("ada_l"))
        } finally {
            runCatching { a.stop() }
            runCatching { b.stop() }
        }
    }

    /** Gateway de auth que solo implementa `login` (devuelve el token que el resolver de prueba acepta). */
    private class LoginOnlyGateway(private val logins: AtomicInteger) : OposiBotsAuthGateway by NoAuthGateway {
        override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto {
            logins.incrementAndGet()
            return OposiBotsAuthSessionDto(
                accessToken = TEST_LONG_TOKEN,
                refreshToken = "refresh",
                expiresAtEpochSeconds = 2_000_000_000,
                user = OposiBotsAuthUserDto(TEST_UID, "person@example.com", true),
            )
        }
    }
}
