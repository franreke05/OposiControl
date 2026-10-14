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

/**
 * MASTER-SEC-003: el limite por sujeto de `/v1/auth` no puede usarse para bloquear a un tercero cuyo email se conoce.
 * Limitador REAL en memoria (cupos de produccion), dos IPs distintas via X-Forwarded-For de un proxy de confianza.
 */
class OposiBotsAuthVictimLockoutTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun anAttackerExhaustingTheLoginQuotaOfAVictimEmailFromItsIpNeverLocksTheVictimOut() = testApplication {
        val gateway = CountingGateway()
        mountAuth(gateway)
        val limit = OposiBotsAuthOperation.LOGIN.subjectLimit

        repeat(limit) { assertEquals(HttpStatusCode.OK, login(ATTACKER_IP).status, "intento ${it + 1}") }
        assertEquals(HttpStatusCode.TooManyRequests, login(ATTACKER_IP).status, "el atacante si queda limitado")
        assertEquals(limit, gateway.logins)

        val victim = login(VICTIM_IP)
        assertEquals(HttpStatusCode.OK, victim.status, "el titular, desde su IP, sigue pudiendo entrar")
        assertEquals(limit + 1, gateway.logins)
    }

    @Test
    fun recoverRegisterAndResendPerEmailCapsAreSilentAndNeverAnswer429() = testApplication {
        val gateway = CountingGateway()
        mountAuth(gateway)
        val recoverLimit = OposiBotsAuthOperation.RECOVER.subjectLimit
        repeat(recoverLimit + 2) {
            val response = post("/v1/auth/recover", """{"email":"victim@example.com"}""", ATTACKER_IP)
            assertEquals(HttpStatusCode.NoContent, response.status, "recover ${it + 1}")
        }
        assertEquals(recoverLimit, gateway.recovers, "por encima del tope no se envian mas correos")
        // Y el titular recibe la misma respuesta (sin 429) aunque el tope del email este agotado.
        assertEquals(HttpStatusCode.NoContent, post("/v1/auth/recover", """{"email":"victim@example.com"}""", VICTIM_IP).status)

        repeat(OposiBotsAuthOperation.REGISTER.subjectLimit + 1) {
            val response = post("/v1/auth/register", """{"email":"victim@example.com","password":"safe-password-12"}""", ATTACKER_IP)
            assertEquals(HttpStatusCode.Accepted, response.status)
        }
        assertEquals(OposiBotsAuthOperation.REGISTER.subjectLimit, gateway.registers)

        repeat(OposiBotsAuthOperation.RESEND.subjectLimit + 1) {
            assertEquals(HttpStatusCode.NoContent, post("/v1/auth/email/resend", """{"email":"victim@example.com"}""", VICTIM_IP).status)
        }
        assertEquals(OposiBotsAuthOperation.RESEND.subjectLimit, gateway.resends)
    }

    @Test
    fun theLoginSubjectKeyBindsEmailAndClientIp() {
        assertEquals("a@b.es|203.0.113.1", loginSubjectKey("a@b.es", "203.0.113.1"))
    }

    private fun ApplicationTestBuilder.mountAuth(gateway: OposiBotsAuthGateway) {
        application {
            install(ContentNegotiation) { json(json) }
            installRequestCorrelation()
            installBackendStatusPages()
            val configuration = OposiBotsAuthConfiguration(
                supabaseUrl = "https://example.supabase.co",
                publishableKey = "publishable",
                secretKey = "secret",
                appEnvironment = "test",
                passwordRecoveryRedirectUrl = null,
                appleClientId = null,
                appleClientSecret = null,
                captchaRequired = false,
                trustProxyHeaders = true,
                // El motor de pruebas de Ktor expone su peer como "localhost" en remoteAddress; esta configuracion se construye
                // a mano (fromEnvironment, que exige literales IP en TRUSTED_PROXY_HOSTS, no interviene).
                trustedProxyHosts = setOf("localhost"),
                forwardClientIpToSupabase = false,
                rateLimitMode = "memory",
                rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
                valkeyUrl = null,
            )
            routing {
                oposiBotsAuthRoutes(OposiBotsAuthRuntime(configuration, gateway, InMemoryOposiBotsAuthRateLimiter(), json, CanonicalTestEnv().runtime().canonical, NoAccessRequestsMailGuard))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.login(ip: String) =
        post("/v1/auth/login", """{"email":"victim@example.com","password":"wrong-password-123"}""", ip)

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, ip: String) = client.post(path) {
        header("X-Forwarded-For", ip)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private class CountingGateway : OposiBotsAuthGateway {
        var logins = 0
        var recovers = 0
        var registers = 0
        var resends = 0

        override suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext) =
            OposiBotsRegisterResponse().also { registers++ }
        override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) { resends++ }
        override suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext) = session()
        override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext) = session().also { logins++ }
        override suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext) = session()
        override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) = Unit
        override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) { recovers++ }
        override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) = Unit
        override suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext) = OposiBotsOAuthResponse(session(), false)
        override suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) = OposiBotsOAuthResponse(session(), false)

        private fun session() = OposiBotsAuthSessionDto(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 2_000_000_000,
            user = OposiBotsAuthUserDto("user-id", "victim@example.com", false),
        )
    }

    private companion object {
        const val ATTACKER_IP = "198.51.100.66"
        const val VICTIM_IP = "203.0.113.7"
    }
}
