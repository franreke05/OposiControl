package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER2-AUTH-002: el login limitaba solo por (email, IP) y por IP: rotando IPs (o direcciones dentro de un /64
 * IPv6) un atacante tenia intentos ilimitados contra una cuenta. Ahora hay un tope alto POR CUENTA, la IPv6 cuenta por
 * su /64 y, fuera de desarrollo, el arranque exige confiar en el proxy (Caddy). Limitador REAL en memoria.
 */
class OposiBotsLoginAccountCeilingTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun theSameEmailFromManyIpsHitsAPerAccountCeiling() = testApplication {
        val gateway = CountingLoginGateway()
        mount(gateway)
        val perIp = OposiBotsAuthOperation.LOGIN.subjectLimit
        val ceiling = OposiBotsAuthOperation.LOGIN_ACCOUNT.subjectLimit
        var attempts = 0
        var ip = 0
        while (attempts < ceiling) {
            ip++
            repeat(minOf(perIp, ceiling - attempts)) {
                assertEquals(HttpStatusCode.OK, login("victim@example.com", "198.51.100.$ip").status, "intento ${attempts + 1}")
                attempts++
            }
        }
        assertTrue(ip > 1, "hacen falta varias IPs para llegar al tope por cuenta")
        val blocked = login("victim@example.com", "198.51.100.${ip + 1}")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, "IP nueva, misma cuenta: tope por cuenta")
        assertEquals(ceiling, gateway.logins, "el intento bloqueado no llega a GoTrue")
        // Otra cuenta no se ve afectada.
        assertEquals(HttpStatusCode.OK, login("other@example.com", "198.51.100.${ip + 1}").status)
    }

    @Test
    fun aSingleIpCanNeverExhaustTheAccountCeilingOnItsOwn() {
        val perIpPerWindow = OposiBotsAuthOperation.LOGIN.subjectLimit *
            (OposiBotsAuthOperation.LOGIN_ACCOUNT.windowSeconds / OposiBotsAuthOperation.LOGIN.windowSeconds)
        assertTrue(
            perIpPerWindow < OposiBotsAuthOperation.LOGIN_ACCOUNT.subjectLimit,
            "desde una sola IP (${perIpPerWindow}) no se bloquea al titular (${OposiBotsAuthOperation.LOGIN_ACCOUNT.subjectLimit})",
        )
    }

    @Test
    fun rotatingAddressesInsideOneIpv6Slash64SharesTheSameBucket() = testApplication {
        val gateway = CountingLoginGateway()
        mount(gateway)
        val perIp = OposiBotsAuthOperation.LOGIN.subjectLimit
        repeat(perIp) { assertEquals(HttpStatusCode.OK, login("victim@example.com", "2001:db8:1:2::${it + 1}").status) }
        assertEquals(HttpStatusCode.TooManyRequests, login("victim@example.com", "2001:db8:1:2:ffff:ffff:ffff:ffff").status)
        // Otro /64: cubo propio.
        assertEquals(HttpStatusCode.OK, login("victim@example.com", "2001:db8:1:3::1").status)
    }

    @Test
    fun theRateLimitBucketOfAnAddress() {
        assertEquals("203.0.113.9", rateLimitClientBucket("203.0.113.9"))
        assertEquals("2001:db8:1:2::/64", rateLimitClientBucket("2001:db8:1:2::1"))
        assertEquals("2001:db8:1:2::/64", rateLimitClientBucket("[2001:0DB8:0001:0002:aaaa:bbbb:cccc:dddd]"))
        assertEquals("2001:db8:1:2::/64", rateLimitClientBucket("2001:db8:1:2:1:2:3:4%eth0"))
        assertEquals("0:0:0:0::/64", rateLimitClientBucket("::1"))
        assertEquals("203.0.113.9", rateLimitClientBucket("::ffff:203.0.113.9"), "IPv4 mapeada = su IPv4")
        assertEquals("64:ff9b:0:0::/64", rateLimitClientBucket("64:ff9b::192.0.2.1"))
        // Lo que no es un literal valido se usa tal cual (sin DNS).
        assertEquals("1:2:3", rateLimitClientBucket("1:2:3"))
        assertEquals("localhost", rateLimitClientBucket("localhost"))
        assertEquals("a@b.es|2001:db8:1:2::/64", loginSubjectKey("a@b.es", "2001:db8:1:2::77"))
    }

    @Test
    fun outsideDevelopmentTheProxyHeadersMustBeTrusted() {
        with(OposiBotsAuthConfiguration.Companion) {
            val behindCaddyUntrusted = configuration(appEnvironment = "production", trustProxyHeaders = false, trustedProxyHosts = emptySet())
            val failure = assertFailsWith<IllegalArgumentException> { behindCaddyUntrusted.requireDeployable() }
            assertTrue("TRUST_PROXY_HEADERS=true" in failure.message.orEmpty(), failure.message)
            assertFailsWith<IllegalArgumentException> {
                configuration(appEnvironment = "staging", trustProxyHeaders = false, trustedProxyHosts = emptySet()).requireDeployable()
            }
            configuration(appEnvironment = "production", trustProxyHeaders = true, trustedProxyHosts = setOf("172.18.0.2")).requireDeployable()
            configuration(appEnvironment = "dev", trustProxyHeaders = false, trustedProxyHosts = emptySet(), url = "http://127.0.0.1:54321", mode = "memory")
                .requireDeployable()
        }
    }

    private fun configuration(
        appEnvironment: String,
        trustProxyHeaders: Boolean,
        trustedProxyHosts: Set<String>,
        url: String = "https://example.supabase.co",
        mode: String = "valkey",
    ) = OposiBotsAuthConfiguration(
        supabaseUrl = url,
        publishableKey = "publishable",
        secretKey = "secret",
        appEnvironment = appEnvironment,
        passwordRecoveryRedirectUrl = null,
        appleClientId = null,
        appleClientSecret = null,
        captchaRequired = true,
        trustProxyHeaders = trustProxyHeaders,
        trustedProxyHosts = trustedProxyHosts,
        forwardClientIpToSupabase = false,
        rateLimitMode = mode,
        rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
        valkeyUrl = if (mode == "valkey") "redis://127.0.0.1:6379" else null,
    )

    private fun ApplicationTestBuilder.mount(gateway: OposiBotsAuthGateway) {
        application {
            install(ContentNegotiation) { json(json) }
            installRequestCorrelation()
            installBackendStatusPages()
            val configuration = configuration(appEnvironment = "test", trustProxyHeaders = true, trustedProxyHosts = setOf("localhost"), mode = "memory")
                .copy(captchaRequired = false)
            routing {
                oposiBotsAuthRoutes(OposiBotsAuthRuntime(configuration, gateway, InMemoryOposiBotsAuthRateLimiter(), json, CanonicalTestEnv().runtime().canonical))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.login(email: String, ip: String) = client.post("/v1/auth/login") {
        header("X-Forwarded-For", ip)
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","password":"wrong-password-123"}""")
    }

    private class CountingLoginGateway : OposiBotsAuthGateway {
        var logins = 0
        private fun unused(): Nothing = error("no interviene")
        override suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext) = OposiBotsAuthSessionDto(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 2_000_000_000,
            user = OposiBotsAuthUserDto("user-id", "victim@example.com", false),
        ).also { logins++ }
        override suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) = unused()
        override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext) = unused()
        override suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) = unused()
    }
}
