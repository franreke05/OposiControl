package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
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
import kotlin.test.assertFalse

/**
 * RB-1-06 / PF2-2-04: una respuesta del JWKS que no es un JWKS (HTTP no 2xx, HTML, JSON sin `keys`,
 * `keys` vacio) es JWKS no disponible -> [IOException] -> 503 UPSTREAM_UNAVAILABLE, nunca 401 (que
 * cerraria la sesion del cliente). Un `kid` desconocido con un JWKS sano sigue siendo 401.
 */
class SupabaseJwtVerifierJwksTest {
    private val uid = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var status = 200
    @Volatile private var body = "{}"
    @Volatile private var contentType = "application/json"
    private val hits = AtomicInteger()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val baseUrl = "http://${server.address.address.hostAddress}:${server.address.port}"
    private val issuer = "$baseUrl/auth/v1"

    private var now = 1_000_000L
    private val verifier = SupabaseJwtVerifier(baseUrl, json, HttpClient.newHttpClient()) { now }

    @AfterTest
    fun stop() = server.stop(0)

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
        JWT.create().withKeyId(kid).withIssuer(issuer).withSubject(uid)
            .sign(Algorithm.ECDSA256(kp.public as ECPublicKey, kp.private as ECPrivateKey))

    private fun serve(code: Int, text: String, type: String = "application/json") {
        status = code
        body = text
        contentType = type
    }

    @Test
    fun jwksResponsesThatAreNotAJwksAreIoFailuresNotInvalidTokens() {
        val kp = newKey()
        val token = tokenFor("k1", kp)
        listOf(
            Triple(503, "{}", "application/json"),
            Triple(429, """{"message":"rate limited"}""", "application/json"),
            Triple(502, "<html>bad gateway</html>", "text/html"),
            Triple(200, "<html>captive portal</html>", "text/html"),
            Triple(200, "{}", "application/json"),
            Triple(200, """{"error":"nope"}""", "application/json"),
            Triple(200, """{"keys":"x"}""", "application/json"),
            Triple(200, """{"keys":[]}""", "application/json"),
            Triple(200, """{"keys":[{"kty":"EC","kid":"k1"}]}""", "application/json"),
        ).forEach { (code, text, type) ->
            serve(code, text, type)
            now += 5_000 // PF3-3-04: cada caso es un intento nuevo, fuera de la ventana de cache negativa
            assertFailsWith<IOException>("$code $text") { verifier.subFrom(token) }
        }
    }

    @Test
    fun aJwksOutageThroughTheResolverIs503AndAHealthyJwksVerifies() = testApplication {
        val kp = newKey()
        val token = tokenFor("k1", kp)
        val resolver = CanonicalIdentityResolver(verifySubject = verifier::subFrom, directory = { null })
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { get("/probe") { call.respondText(resolver.userId(call).value) } }
        }
        suspend fun probe() = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
        fun code(text: String) = Json.parseToJsonElement(text).jsonObject["code"]!!.jsonPrimitive.content

        serve(503, "{}")
        probe().let {
            assertEquals(HttpStatusCode.ServiceUnavailable, it.status)
            assertEquals("UPSTREAM_UNAVAILABLE", code(it.bodyAsText()))
        }
        now += 5_000
        serve(200, "{}")
        probe().let {
            assertEquals(HttpStatusCode.ServiceUnavailable, it.status)
            assertEquals("UPSTREAM_UNAVAILABLE", code(it.bodyAsText()))
        }
        now += 5_000
        serve(200, jwks("k1" to kp))
        assertEquals(uid, probe().bodyAsText())
    }

    /** PF3-3-04: con el JWKS caido, N llamadas dentro de la ventana hacen UN solo intento upstream. */
    @Test
    fun aFailedJwksFetchIsNegativelyCachedWithinTheWindow() {
        val token = tokenFor("k1", newKey())
        serve(503, "{}")
        repeat(20) { i ->
            val failure = assertFailsWith<IOException>("llamada $i") { verifier.subFrom(token) }
            assertEquals("JWKS HTTP 503", failure.message)
            now += 100 // 20 x 100 ms = 2 s < 5 s
        }
        assertEquals(1, hits.get(), "un solo intento upstream dentro de la ventana")

        // Pasada la ventana se reintenta (un segundo intento upstream) y sigue fallando.
        now += 5_000
        assertFailsWith<IOException> { verifier.subFrom(token) }
        assertEquals(2, hits.get())
        repeat(5) { assertFailsWith<IOException> { verifier.subFrom(token) } }
        assertEquals(2, hits.get())
    }

    /** Un JWKS que responde con basura tambien se cachea en negativo (no solo el HTTP no 2xx). */
    @Test
    fun aMalformedJwksIsAlsoNegativelyCached() {
        val token = tokenFor("k1", newKey())
        serve(200, "<html>captive portal</html>", "text/html")
        repeat(5) { assertFailsWith<IOException> { verifier.subFrom(token) } }
        assertEquals(1, hits.get())
    }

    /** Un exito tras un fallo limpia el fallo cacheado: la ventana ya no relanza el IOException viejo. */
    @Test
    fun aSuccessAfterAFailureClearsTheCachedFailure() {
        val kp = newKey()
        val token = tokenFor("k1", kp)
        serve(503, "{}")
        assertFailsWith<IOException> { verifier.subFrom(token) }
        assertEquals(1, hits.get())

        now += 5_000
        serve(200, jwks("k1" to kp))
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(2, hits.get())

        // Kid desconocido dentro de la ventana con un JWKS sano: 401 (no IOException), sin nueva descarga.
        val stranger = tokenFor("nope", newKey())
        val unknown = assertFailsWith<Exception> { verifier.subFrom(stranger) }
        assertFalse(unknown is IOException, "el fallo anterior debe haberse limpiado")
        assertEquals(2, hits.get())

        // Y ya con la cache buena, un kid conocido no descarga nada.
        assertEquals(uid, verifier.subFrom(token))
        assertEquals(2, hits.get())
    }

    /** Peticiones concurrentes durante la caida: un solo intento upstream, todas IOException (-> 503). */
    @Test
    fun concurrentRequestsDuringAnOutageMakeASingleUpstreamAttempt() {
        val token = tokenFor("k1", newKey())
        serve(503, "{}")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { pool.submit<Throwable?> { runCatching { verifier.subFrom(token) }.exceptionOrNull() } }
                .map { it.get() }
            assertEquals(32, results.count { it is IOException })
            assertEquals(1, hits.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun anUnknownKidWithAHealthyJwksIsStill401() = testApplication {
        val known = newKey()
        val stranger = newKey()
        serve(200, jwks("k1" to known))
        val resolver = CanonicalIdentityResolver(verifySubject = verifier::subFrom, directory = { null })
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { get("/probe") { call.respondText(resolver.userId(call).value) } }
        }
        val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer ${tokenFor("unknown", stranger)}") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHENTICATED", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        // Un kid conocido pero firmado con otra clave tampoco es un fallo de JWKS.
        assertFailsWith<Exception> { verifier.subFrom(tokenFor("k1", stranger)) }.also { assertFalse(it is IOException) }
    }

    /** Rotacion: un kid nuevo se acepta en cuanto pasa la ventana corta, no 60 s despues. */
    @Test
    fun aRotatedKeyIsPickedUpAfterTheShortRefetchWindow() {
        val old = newKey()
        val rotated = newKey()
        serve(200, jwks("old" to old))
        assertEquals(uid, verifier.subFrom(tokenFor("old", old)))

        serve(200, jwks("old" to old, "new" to rotated))
        val fresh = tokenFor("new", rotated)
        // Dentro de la ventana anti-tormenta: 401 (kid desconocido, JWKS sano), no IOException.
        now += 1_000
        assertFalse(runCatching { verifier.subFrom(fresh) }.exceptionOrNull() is IOException)
        assertFailsWith<Exception> { verifier.subFrom(fresh) }
        // Pasada la ventana (5 s): se refresca y verifica.
        now += 5_000
        assertEquals(uid, verifier.subFrom(fresh))
    }

    @Test
    fun aFailedRefreshKeepsThePreviouslyCachedKeysUsable() {
        val kp = newKey()
        serve(200, jwks("k1" to kp))
        assertEquals(uid, verifier.subFrom(tokenFor("k1", kp)))
        serve(HttpURLConnection.HTTP_UNAVAILABLE, "{}")
        now += 60_000
        assertEquals(uid, verifier.subFrom(tokenFor("k1", kp)))
    }
}
