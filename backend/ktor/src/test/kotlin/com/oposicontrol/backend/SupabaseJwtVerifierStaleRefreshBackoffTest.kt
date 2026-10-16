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
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER3-AUTH-006: con el JWKS caido y la cache caducada, el refresco por caducidad (fail-soft) se repetia en el hilo
 * de una peticion de usuario cada JWKS_MIN_REFETCH_MS (5 s), pagando hasta fetchTimeout cada vez. Ahora, tras un
 * refresco por caducidad fallido, no se vuelve a intentar en el camino de las peticiones hasta
 * [JWKS_STALE_REFRESH_BACKOFF_MS]. El refresco por `kid` desconocido (sin clave que servir) no queda afectado.
 */
class SupabaseJwtVerifierStaleRefreshBackoffTest {
    private val uid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Volatile private var status = 200
    @Volatile private var body = "{}"
    private val hits = AtomicInteger()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val baseUrl = "http://${server.address.address.hostAddress}:${server.address.port}"
    private var now = 1_000_000L
    private val verifier = SupabaseJwtVerifier(baseUrl, Json { ignoreUnknownKeys = true }, HttpClient.newHttpClient()) { now }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun afterAFailedStaleRefreshRequestsStopPayingForItUntilTheBackoffPasses() {
        val key = newKey()
        val token = tokenFor("k1", key)
        serve(200, jwks("k1" to key))
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(1, hits.get())

        serve(503, "{}")
        now += JWKS_MAX_AGE_MS + 1
        assertEquals(uid, verifier.subFrom(token), "fail-soft")
        assertEquals(2, hits.get())

        // Pasada la ventana anti-tormenta (5 s) pero dentro del backoff: ninguna peticion vuelve a tocar la red.
        now += JWKS_MIN_REFETCH_WINDOW_FOR_TEST + 1
        repeat(3) { assertEquals(uid, verifier.subFrom(token)) }
        now += JWKS_STALE_REFRESH_BACKOFF_MS / 2
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(2, hits.get(), "backoff: sin descargas en el camino de la peticion")

        // Vencido el backoff se reintenta UNA vez; si el JWKS sigue caido, otro backoff completo.
        now += JWKS_STALE_REFRESH_BACKOFF_MS
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(3, hits.get())
        now += JWKS_MIN_REFETCH_WINDOW_FOR_TEST + 1
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(3, hits.get())

        // El JWKS vuelve: tras el backoff, el refresco rota las claves (la retirada deja de verificar).
        val next = newKey()
        serve(200, jwks("k2" to next))
        now += JWKS_STALE_REFRESH_BACKOFF_MS + 1
        assertFailsWith<IllegalStateException> { verifier.subFrom(token) }
        assertEquals(4, hits.get())
        assertEquals(uid, verifier.subFrom(tokenFor("k2", next)))
    }

    @Test
    fun anUnknownKidStillRefreshesDuringTheStaleBackoff() {
        val key = newKey()
        serve(200, jwks("k1" to key))
        assertEquals(uid, verifier.subFrom(tokenFor("k1", key)))

        serve(503, "{}")
        now += JWKS_MAX_AGE_MS + 1
        assertEquals(uid, verifier.subFrom(tokenFor("k1", key)))
        assertEquals(2, hits.get())

        // Rotacion de claves durante el backoff: el camino de `kid` desconocido no espera al backoff.
        val rotated = newKey()
        serve(200, jwks("k1" to key, "k2" to rotated))
        now += JWKS_MIN_REFETCH_WINDOW_FOR_TEST + 1
        assertEquals(uid, verifier.subFrom(tokenFor("k2", rotated)))
        assertEquals(3, hits.get())
    }

    @Test
    fun theBackoffIsLongerThanTheAntiStormWindow() {
        assertTrue(JWKS_STALE_REFRESH_BACKOFF_MS >= 6 * JWKS_MIN_REFETCH_WINDOW_FOR_TEST, "$JWKS_STALE_REFRESH_BACKOFF_MS")
        assertTrue(JWKS_STALE_REFRESH_BACKOFF_MS < JWKS_MAX_AGE_MS)
    }

    private fun serve(code: Int, text: String) {
        status = code
        body = text
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

    private companion object {
        /** JWKS_MIN_REFETCH_MS es privado de produccion; su valor documentado (anti-tormenta) es 5 s. */
        const val JWKS_MIN_REFETCH_WINDOW_FOR_TEST = 5_000L
    }
}
