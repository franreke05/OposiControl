package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DEBT-151: limite de mutaciones por administrador sobre el pipeline real (plugin + sobre canonico de StatusPages). */
class AdminMutationRateLimitTest {
    private fun app(limit: Int, limiter: OposiBotsAuthRateLimiter? = InMemoryOposiBotsAuthRateLimiter(), block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(kotlinx.serialization.json.Json) }
                installBackendStatusPages()
                install(Authentication) {
                    bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } }
                }
                routing {
                    authenticate("gateway-auth") {
                        install(AdminMutationRateLimitPlugin) {
                            this.limiter = limiter
                            this.limit = limit
                        }
                        get("/api/v1/admin/x") { call.respond(HttpStatusCode.OK) }
                        post("/api/v1/admin/x") { call.respond(HttpStatusCode.OK) }
                        delete("/api/v1/admin/x") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            block(client)
        }

    private suspend fun io.ktor.client.HttpClient.mutate(admin: String) =
        post("/api/v1/admin/x") { header(HttpHeaders.Authorization, "Bearer $admin") }

    @Test
    fun mutationsBeyondTheLimitGet429WithEnvelopeAndRetryAfter() = app(limit = 3) { client ->
        repeat(3) { assertEquals(HttpStatusCode.OK, client.mutate("admin-a").status) }
        val blocked = client.mutate("admin-a")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue((blocked.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0) >= 1)
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"))
        val deleted = client.delete("/api/v1/admin/x") { header(HttpHeaders.Authorization, "Bearer admin-a") }
        assertEquals(HttpStatusCode.TooManyRequests, deleted.status, "DELETE comparte el cupo")
    }

    @Test
    fun theLimitIsPerAdminAndReadsAreNeverLimited() = app(limit = 1) { client ->
        assertEquals(HttpStatusCode.OK, client.mutate("admin-a").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.mutate("admin-a").status)
        assertEquals(HttpStatusCode.OK, client.mutate("admin-b").status)
        repeat(10) {
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/x") { header(HttpHeaders.Authorization, "Bearer admin-a") }.status)
        }
    }

    @Test
    fun noLimiterMeansNoLimit() = app(limit = 1, limiter = null) { client ->
        repeat(5) { assertEquals(HttpStatusCode.OK, client.mutate("admin-a").status) }
    }

    @Test
    fun limitParsingFallsBackToTheSafeDefault() {
        assertEquals(120, adminMutationRateLimit(null))
        assertEquals(120, adminMutationRateLimit(""))
        assertEquals(120, adminMutationRateLimit("abc"))
        assertEquals(120, adminMutationRateLimit("0"))
        assertEquals(120, adminMutationRateLimit("-5"))
        assertEquals(30, adminMutationRateLimit(" 30 "))
    }

    // ------------------------------------------------------------------ SEC-V1-02: limitador propio del perfil control

    private val secret = "s".repeat(32)
    private val noValkey: (String, RateLimitKeyHasher) -> OposiBotsAuthRateLimiter = { _, _ -> error("no debe construirse") }

    private fun limiterFor(vararg env: Pair<String, String>, valkey: (String, RateLimitKeyHasher) -> OposiBotsAuthRateLimiter = noValkey) =
        adminMutationRateLimiterFromEnvironment(environment = mapOf(*env)::get, valkey = valkey)

    @Test
    fun outsideDevelopmentAMissingModeFailsStartupInsteadOfDisablingTheLimit() {
        assertFailsWith<IllegalStateException> { limiterFor() } // APP_ENV ausente = production (fail closed)
        assertFailsWith<IllegalStateException> { limiterFor("APP_ENV" to "production") }
        assertFailsWith<IllegalStateException> { limiterFor("APP_ENV" to "staging", "ADMIN_RATE_LIMIT_MODE" to " ") }
    }

    @Test
    fun outsideDevelopmentMemoryABadSecretOrNoValkeyUrlFailStartup() {
        assertFailsWith<IllegalStateException> {
            limiterFor("APP_ENV" to "production", "ADMIN_RATE_LIMIT_MODE" to "memory", "ADMIN_RATE_LIMIT_HMAC_SECRET" to secret)
        }
        assertFailsWith<IllegalStateException> {
            limiterFor("APP_ENV" to "production", "ADMIN_RATE_LIMIT_MODE" to "valkey", "ADMIN_RATE_LIMIT_HMAC_SECRET" to "corto", "ADMIN_RATE_LIMIT_VALKEY_URL" to "redis://v")
        }
        assertFailsWith<IllegalStateException> {
            limiterFor("APP_ENV" to "production", "ADMIN_RATE_LIMIT_MODE" to "valkey", "ADMIN_RATE_LIMIT_HMAC_SECRET" to secret)
        }
        assertFailsWith<IllegalStateException> {
            limiterFor("APP_ENV" to "production", "ADMIN_RATE_LIMIT_MODE" to "otro", "ADMIN_RATE_LIMIT_HMAC_SECRET" to secret)
        }
    }

    @Test
    fun productionWithValkeyBuildsTheValkeyLimiterWithTheConfiguredUrl() {
        val built = InMemoryOposiBotsAuthRateLimiter()
        var url: String? = null
        val limiter = limiterFor(
            "APP_ENV" to "production", "ADMIN_RATE_LIMIT_MODE" to "Valkey", "ADMIN_RATE_LIMIT_HMAC_SECRET" to secret,
            "ADMIN_RATE_LIMIT_VALKEY_URL" to " redis://:pw@valkey:6379 ",
            valkey = { u, _ -> url = u; built },
        )
        assertSame(built, limiter)
        assertEquals("redis://:pw@valkey:6379", url)
    }

    @Test
    fun developmentWithoutModeHasNoLimiterAndMemoryIsAllowed() {
        assertNull(limiterFor("APP_ENV" to "development"))
        val memory = limiterFor("APP_ENV" to "dev", "ADMIN_RATE_LIMIT_MODE" to "memory", "ADMIN_RATE_LIMIT_HMAC_SECRET" to secret)
        assertTrue(memory is InMemoryOposiBotsAuthRateLimiter)
    }
}
