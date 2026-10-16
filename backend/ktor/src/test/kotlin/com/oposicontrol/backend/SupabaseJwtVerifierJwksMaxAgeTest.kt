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

/**
 * MASTER-SEC-004: la cache del JWKS tiene edad maxima. Una clave retirada del JWKS deja de verificar tokens tras
 * [JWKS_MAX_AGE_MS] (antes, hasta reiniciar); un JWKS caido al caducar la cache no rompe tokens validos (fail-soft).
 */
class SupabaseJwtVerifierJwksMaxAgeTest {
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
    fun aKeyRemovedFromTheJwksStopsVerifyingOnceTheCacheExpires() {
        val retired = newKey()
        val current = newKey()
        val token = tokenFor("retired", retired)
        serve(200, jwks("retired" to retired, "current" to current))
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(1, hits.get())

        // Supabase retira la clave. Dentro de la edad maxima se sigue usando la cache (sin red).
        serve(200, jwks("current" to current))
        now += JWKS_MAX_AGE_MS - 1
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(1, hits.get())

        // Caducada la cache: se descarga de nuevo y la clave retirada ya no verifica.
        now += 2
        assertFailsWith<IllegalStateException> { verifier.subFrom(token) }
        assertEquals(2, hits.get())
        assertEquals(uid, verifier.subFrom(tokenFor("current", current)))
    }

    @Test
    fun anUnavailableJwksWhenTheCacheExpiresKeepsTheCachedKeys() {
        val key = newKey()
        val token = tokenFor("k1", key)
        serve(200, jwks("k1" to key))
        assertEquals(uid, verifier.subFrom(token))

        serve(503, "{}")
        now += JWKS_MAX_AGE_MS + 1
        assertEquals(uid, verifier.subFrom(token), "fail-soft: la clave en cache sigue valiendo")
        assertEquals(2, hits.get())
        // Anti-tormenta: dentro de la ventana minima no se vuelve a pedir.
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(2, hits.get())
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
}
