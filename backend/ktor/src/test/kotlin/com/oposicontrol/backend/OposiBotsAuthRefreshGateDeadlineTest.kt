package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER3-AUTH-005: el reintento de la lectura de la puerta en refresh (MASTER2-AUTH-003) acotaba solo las esperas;
 * cada intento podia durar el timeout del cliente HTTP (15 s): ~46 s con un PostgREST lento, mas que el timeout del
 * cliente movil, que reintentaba con un refresh token ya rotado. Ahora hay limite por intento y plazo global.
 * Reloj REAL: la puerta corre en Dispatchers.IO (plazos de reloj real sea cual sea el dispatcher de quien llama).
 */
class OposiBotsAuthRefreshGateDeadlineTest {
    private val context = OposiBotsAuthContext("request-refresh-deadline", "127.0.0.1")

    private class Harness(val gateway: SupabaseOposiBotsAuthGateway, val requests: MutableList<HttpRequestData>, val reads: AtomicInteger) {
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
    }

    private fun harness(read: suspend (attempt: Int) -> CanonicalUserIdentity?): Harness {
        val requests = CopyOnWriteArrayList<HttpRequestData>()
        val reads = AtomicInteger()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(ByteReadChannel(SESSION), HttpStatusCode.OK, json)
                "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val directory = CanonicalProfileDirectory { read(reads.incrementAndGet()) }
        return Harness(SupabaseOposiBotsAuthGateway(configuration(), Json { ignoreUnknownKeys = true }, client, directory), requests, reads)
    }

    private fun active() = CanonicalUserIdentity(CanonicalUserId.fromJwtSubject(UID), AccountStatus.ACTIVA, true)

    @Test
    fun aGateThatHangsOnEveryAttemptFailsClosedWithinTheOverallDeadline() = runBlocking {
        val h = harness { awaitCancellation() }
        val started = System.nanoTime()
        val failure = assertFailsWith<OposiBotsAuthProblem> { h.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "plazo agotado = transitorio, nunca fail-open")
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in (REFRESH_GATE_DEADLINE_MS - 100)..(REFRESH_GATE_DEADLINE_MS + 2_000), "acotado por el plazo global: $elapsed ms")
        assertTrue(REFRESH_GATE_DEADLINE_MS < 10_000, "por debajo de la ventana de reutilizacion de GoTrue y del timeout del cliente")
        assertTrue(h.reads.get() in 2..(1 + REFRESH_GATE_RETRY_DELAYS_MS.size), "hubo reintentos y estan acotados: ${h.reads.get()}")
        assertTrue(h.logouts().isEmpty(), "refresh no revoca por un fallo transitorio (MASTER-AUTH-010)")
    }

    @Test
    fun aSlowFirstAttemptIsCutPerAttemptAndTheRetryReturnsTheRotatedSession() = runBlocking {
        val h = harness { attempt -> if (attempt == 1) delay(60_000) ; active() }
        val started = System.nanoTime()
        val session = h.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context)
        assertEquals("rotated-refresh", session.refreshToken)
        assertEquals(2, h.reads.get())
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val expected = REFRESH_GATE_ATTEMPT_TIMEOUT_MS + REFRESH_GATE_RETRY_DELAYS_MS.first()
        assertTrue(elapsed in (expected - 100)..(expected + 2_000), "el intento lento se corta a su limite: $elapsed ms")
    }

    @Test
    fun aProfileWithNoRowIsStillADefinitiveDenialNotATimeout() = runBlocking {
        val h = harness { null }
        val denied = assertFailsWith<BackendDomainException> { h.gateway.refresh(OposiBotsRefreshRequest("r"), context) }
        assertEquals("PROFILE_MISSING", denied.errorCode)
        assertEquals(1, h.reads.get())
    }

    @Test
    fun anExternalCancellationIsPropagatedAsCancellationNotConvertedInto503() = runBlocking {
        val h = harness { awaitCancellation() }
        val call = async { h.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context) }
        delay(500)
        call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(h.logouts().isEmpty())
    }

    private fun configuration() = OposiBotsAuthConfiguration(
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

    private companion object {
        const val UID = "33333333-3333-4333-8333-333333333333"
        const val SESSION =
            """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600,"user":{"id":"$UID","email":"person@example.com"}}"""
    }
}
