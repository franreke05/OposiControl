package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER2-AUTH-004: con la cache del JWKS caducada y el JWKS COLGADO, las peticiones con un token valido cuya clave
 * esta en cache no se encolan detras del refresco (antes: `@Synchronized` + ventana anti-tormenta desde el INICIO del
 * intento => cada hilo en espera repetia una descarga de ~20 s en serie).
 */
class SupabaseJwtVerifierHangingJwksTest {
    private val uid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Volatile private var hang = false
    @Volatile private var body = "{}"
    private val hits = AtomicInteger()
    private val release = CountDownLatch(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            hits.incrementAndGet()
            if (hang) release.await(30, TimeUnit.SECONDS)
            val bytes = body.toByteArray()
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }
    private val baseUrl = "http://${server.address.address.hostAddress}:${server.address.port}"

    @Volatile private var now = 1_000_000L
    private val fetchTimeout = Duration.ofMillis(1_500)
    private val verifier = SupabaseJwtVerifier(
        baseUrl,
        Json { ignoreUnknownKeys = true },
        HttpClient.newHttpClient(),
        clock = { now },
        fetchTimeout = fetchTimeout,
    )

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun concurrentValidTokensAreServedFromCacheWhileAStaleRefreshHangs() {
        val key = newKey()
        val token = tokenFor("k1", key)
        body = jwks("k1" to key)
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(1, hits.get())

        hang = true
        now += JWKS_MAX_AGE_MS + 1
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..8).map {
                pool.submit<Long> {
                    start.await()
                    val t0 = System.nanoTime()
                    assertEquals(uid, verifier.subFrom(token))
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                }
            }
            start.countDown()
            val durations = futures.map { it.get(20, TimeUnit.SECONDS) }.sorted()
            // Como mucho UN hilo paga el refresco colgado (acotado por el timeout corto); el resto sale de la cache.
            assertTrue(durations.last() < fetchTimeout.toMillis() + 2_000, "refresco acotado por el timeout: $durations")
            assertTrue(durations.dropLast(1).all { it < 500 }, "nadie mas espera al refresco: $durations")
            assertEquals(2, hits.get(), "una sola descarga para todas las peticiones concurrentes")
        } finally {
            pool.shutdownNow()
        }

        // Tras el fallo lento, la ventana anti-tormenta cuenta desde el FINAL del intento: sin nueva descarga.
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(2, hits.get())

        // Camino bloqueante (kid desconocido) dentro de la ventana: 503 inmediato por la cache negativa, sin red.
        val t0 = System.nanoTime()
        assertFailsWith<java.io.IOException> { verifier.subFrom(tokenFor("otro", newKey())) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 500)
        assertEquals(2, hits.get())
    }

    @Test
    fun theJwksFetchUsesItsOwnShortTimeout() {
        assertTrue(JWKS_FETCH_TIMEOUT <= Duration.ofSeconds(5), "timeout del JWKS corto: $JWKS_FETCH_TIMEOUT")
        assertTrue(JWKS_FETCH_TIMEOUT < UPSTREAM_REQUEST_TIMEOUT)
    }

    private fun newKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun b64(v: BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val padded = ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }

    private fun jwks(vararg keys: Pair<String, KeyPair>): String =
        keys.joinToString(prefix = """{"keys":[""", postfix = "]}") { (kid, kp) ->
            val w = (kp.public as ECPublicKey).w
            """{"kty":"EC","crv":"P-256","kid":"$kid","x":"${b64(w.affineX)}","y":"${b64(w.affineY)}"}"""
        }

    private fun tokenFor(kid: String, kp: KeyPair): String =
        JWT.create().withKeyId(kid).withIssuer("$baseUrl/auth/v1").withSubject(uid).withAudience("authenticated").withExpiresAt(java.util.Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.ECDSA256(kp.public as ECPublicKey, kp.private as ECPrivateKey))
}
