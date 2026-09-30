package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER2-AUTH-003: en refresh GoTrue YA ha rotado el refresh token cuando se lee la puerta de estado. Si esa lectura
 * falla de forma transitoria, el cliente nunca recibe el token rotado y, fuera de la ventana de reutilizacion, su
 * reintento dispara la deteccion de reuso (cierre forzado). La lectura se reintenta ahora, SOLO en refresh, con un
 * backoff corto y acotado; nunca se devuelve la sesion sin la puerta (fail-closed).
 */
class OposiBotsAuthRefreshGateRetryTest {
    private val context = OposiBotsAuthContext("request-refresh", "127.0.0.1")

    private class Harness(val requests: MutableList<HttpRequestData>, val failuresLeft: AtomicInteger) {
        lateinit var gateway: SupabaseOposiBotsAuthGateway
        fun profileReads() = requests.count { it.url.encodedPath == "/rest/v1/perfiles_usuario" }
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
        fun tokenCalls() = requests.count { it.url.encodedPath == "/auth/v1/token" }
    }

    private fun harness(profileFailures: Int, profile: String = profileRow("activa")): Harness {
        val h = Harness(CopyOnWriteArrayList(), AtomicInteger(profileFailures))
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            h.requests += request
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(ByteReadChannel(SESSION), HttpStatusCode.OK, json)
                "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                "/rest/v1/perfiles_usuario" ->
                    if (h.failuresLeft.getAndDecrement() > 0) {
                        respond(ByteReadChannel("""{"message":"down"}"""), HttpStatusCode.ServiceUnavailable, json)
                    } else {
                        respond(ByteReadChannel(profile), HttpStatusCode.OK, json)
                    }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val configuration = OposiBotsAuthConfiguration(
            supabaseUrl = "https://example.supabase.co",
            publishableKey = "client-publishable",
            secretKey = "server-secret",
            appEnvironment = "test",
            passwordRecoveryRedirectUrl = null,
            appleClientId = null,
            appleClientSecret = null,
            captchaRequired = false,
            trustProxyHeaders = false,
            trustedProxyHosts = emptySet(),
            forwardClientIpToSupabase = false,
            rateLimitMode = "memory",
            rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
            valkeyUrl = null,
        )
        h.gateway = SupabaseOposiBotsAuthGateway(configuration, Json { ignoreUnknownKeys = true }, client)
        return h
    }

    @Test
    fun aTransientGateReadThatRecoversDuringRefreshReturnsTheRotatedSession() = runTest {
        val h = harness(profileFailures = 1)
        val session = h.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context)
        assertEquals("rotated-access", session.accessToken)
        assertEquals("rotated-refresh", session.refreshToken, "el cliente recibe el token que GoTrue ya roto")
        assertEquals(2, h.profileReads())
        assertEquals(1, h.tokenCalls(), "nunca se vuelve a gastar el refresh token")
        assertTrue(h.logouts().isEmpty())
    }

    @Test
    fun theRetryIsBoundedAndStillFailsClosedWhenTheGateStaysUnreadable() = runTest {
        val h = harness(profileFailures = 99)
        val failure = assertFailsWith<OposiBotsAuthProblem> { h.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
        assertEquals(1 + REFRESH_GATE_RETRY_DELAYS_MS.size, h.profileReads(), "reintentos acotados")
        assertTrue(currentTime <= REFRESH_GATE_RETRY_DELAYS_MS.sum(), "espera total acotada: $currentTime ms")
        assertTrue(REFRESH_GATE_RETRY_DELAYS_MS.sum() <= 3_000, "muy por debajo del timeout y de la ventana de reuso")
        assertTrue(h.logouts().isEmpty(), "refresh no revoca por un fallo transitorio (MASTER-AUTH-010)")
    }

    @Test
    fun aDefinitiveDenialIsNeverRetried() = runTest {
        val h = harness(profileFailures = 0, profile = profileRow("bloqueada"))
        val denied = assertFailsWith<BackendDomainException> { h.gateway.refresh(OposiBotsRefreshRequest("r"), context) }
        assertEquals("ACCOUNT_BLOCKED", denied.errorCode)
        assertEquals(1, h.profileReads())
        assertEquals(listOf("scope=global"), h.logouts().map { it.url.encodedQuery })
    }

    @Test
    fun loginDoesNotRetryTheGateItRevokesTheMintedSessionLocallyAsBefore() = runTest {
        val h = harness(profileFailures = 1)
        assertFailsWith<OposiBotsAuthProblem> {
            h.gateway.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context)
        }
        assertEquals(1, h.profileReads())
        assertEquals(listOf("scope=local"), h.logouts().map { it.url.encodedQuery })
    }

    private companion object {
        const val UID = "33333333-3333-4333-8333-333333333333"
        const val SESSION =
            """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600,"user":{"id":"$UID","email":"person@example.com"}}"""

        fun profileRow(estado: String) = """[{"usuario_id":"$UID","estado_cuenta":"$estado","onboarding_completado":true}]"""
    }
}
