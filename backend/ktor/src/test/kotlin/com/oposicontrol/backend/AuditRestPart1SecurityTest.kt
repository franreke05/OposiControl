package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun auditConfiguration(
    trustProxyHeaders: Boolean = false,
    trustedProxyHosts: Set<String> = emptySet(),
    appEnvironment: String = "test",
) = testCanonicalConfiguration().copy(
    trustProxyHeaders = trustProxyHeaders,
    trustedProxyHosts = trustedProxyHosts,
    appEnvironment = appEnvironment,
)

/** MASTER-SEC-001/002/006/013 (KTOR_REST part 1). */
class AuditRestPart1SecurityTest {

    // ---------------- SEC-001: el peer es la direccion literal, nunca el nombre DNS ----------------

    @Test
    fun theClientIpUsesTheSocketAddressAndHonoursForwardedForOnlyFromATrustedLiteralPeer() = testApplication {
        // Sondeo del peer real del motor de pruebas: lo que devuelve origin.remoteAddress.
        var peer = ""
        application {
            routing {
                get("/peer") {
                    peer = call.request.origin.remoteAddress
                    call.respondText(peer)
                }
                get("/trusted") { call.respondText(call.trustedClientIp(auditConfiguration(true, setOf(peer)))) }
                get("/untrusted") { call.respondText(call.trustedClientIp(auditConfiguration(true, setOf("192.0.2.77")))) }
                get("/no-trust") { call.respondText(call.trustedClientIp(auditConfiguration(false, setOf(peer)))) }
                get("/hostname-entry") { call.respondText(call.trustedClientIp(auditConfiguration(true, setOf("proxy.example.com")))) }
            }
        }
        client.get("/peer")
        assertTrue(peer.isNotBlank())

        val xff = "203.0.113.9"
        assertEquals(xff, client.get("/trusted") { header("X-Forwarded-For", xff) }.bodyAsText(), "peer de confianza: se usa el XFF")
        assertEquals(normalizePeerAddress(peer), client.get("/untrusted") { header("X-Forwarded-For", xff) }.bodyAsText(), "peer no confiable: XFF ignorado")
        assertEquals(normalizePeerAddress(peer), client.get("/no-trust") { header("X-Forwarded-For", xff) }.bodyAsText(), "sin TRUST_PROXY_HEADERS: XFF ignorado")
        // Un nombre de host en la lista nunca coincide con un peer literal.
        assertEquals(normalizePeerAddress(peer), client.get("/hostname-entry") { header("X-Forwarded-For", xff) }.bodyAsText())
    }

    @Test
    fun peerAddressesAreNormalisedAndOnlyIpLiteralsAreAcceptedInTheTrustedList() {
        assertEquals("fe80::1", normalizePeerAddress("[FE80::1%eth0]"))
        assertEquals("127.0.0.1", normalizePeerAddress(" 127.0.0.1 "))
        assertTrue(isIpLiteral("10.0.0.10") && isIpLiteral("::1") && isIpLiteral("2001:db8::7"))
        assertFalse(isIpLiteral("localhost"))
        assertFalse(isIpLiteral("proxy.internal.example.com"))
        val configuration = auditConfiguration(true, setOf("FE80::1"))
        // El peer con zone id y mayusculas casa con la entrada normalizada.
        assertEquals("198.51.100.20", resolveTrustedClientIp(configuration, "[fe80::1%eth0]", "192.0.2.1, 198.51.100.20"))
    }

    // ---------------- SEC-002: no-store en las respuestas de /v1/auth ----------------

    @Test
    fun everyAuthResponseIsNoStoreSuccessAndError() = testApplication {
        val env = CanonicalTestEnv()
        application { env.mount(this) }
        // Error (401): tambien no-store.
        val unauthorized = client.get("/v1/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals("no-store", unauthorized.headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", unauthorized.headers["Pragma"])
        // Validacion (400) de un endpoint que devuelve tokens.
        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","password":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, login.status)
        assertEquals("no-store", login.headers[HttpHeaders.CacheControl])
    }

    // ---------------- SEC-006: APP_ENV falla cerrado ----------------

    @Test
    fun blankOrUnknownAppEnvironmentIsNeverDevelopment() {
        for (raw in listOf(null, "", "   ", "prod", "production", "staging", "devel", "unknown")) {
            assertFalse(AppEnvironment.isDevelopment(raw), "raw=$raw")
        }
        for (raw in listOf("dev", "DEV", " development ", "local", "test")) {
            assertTrue(AppEnvironment.isDevelopment(raw), "raw=$raw")
        }
        assertEquals("production", AppEnvironment.resolve(null))
        assertEquals("production", AppEnvironment.resolve("  "))
        assertEquals("staging", AppEnvironment.resolve(" Staging "))
    }

    @Test
    fun aBlankEnvironmentIsNotDevelopmentSoCaptchaHttpsAndTheMemoryLimiterAreEnforced() {
        val blank = auditConfiguration(appEnvironment = AppEnvironment.resolve(""))
        assertFalse(blank.development)
        assertTrue(auditConfiguration(appEnvironment = "dev").development)
        // Las tres relajaciones de fromEnvironment cuelgan de `development`.
        assertFalse("http://insecure".startsWith("https://") || blank.development, "HTTP rechazado")
        assertFalse(blank.rateLimitMode == "valkey" || (blank.rateLimitMode == "memory" && blank.development), "memory rechazado")
        assertTrue(!blank.development, "captchaRequired por defecto")
    }

    @Test
    fun theDevBypassIsOffForABlankEnvironment() {
        assertFalse(allowsDevBypass(AppEnvironment.resolve(""), enabled = true))
        assertTrue(allowsDevBypass(AppEnvironment.resolve("dev"), enabled = true))
    }

    // ---------------- SEC-013: comparacion en tiempo constante ----------------

    @Test
    fun theStaticGatewayTokenIsComparedInConstantTimeAndStillAcceptsOnlyTheExactValue() {
        runBlocking {
            assertTrue(constantTimeEquals("s3cret-token", "s3cret-token"))
            assertFalse(constantTimeEquals("s3cret-token", "s3cret-tokeN"))
            assertFalse(constantTimeEquals("s3cret", "s3cret-token"))
            assertFalse(constantTimeEquals("", "x"))
            val verify: (String) -> String = { error("no es un JWT") }
            assertNotNull(gatewayAuthPrincipal("s3cret-token", "production", verify, expectedStaticToken = "s3cret-token", allowDevToken = false))
            assertNull(gatewayAuthPrincipal("s3cret-tokeN", "production", verify, expectedStaticToken = "s3cret-token", allowDevToken = false))
            assertNull(gatewayAuthPrincipal("s3cret-token", "production", verify, expectedStaticToken = "", allowDevToken = false))
            assertNull(gatewayAuthPrincipal("dev-local", "production", verify, expectedStaticToken = "s3cret-token", allowDevToken = false))
            assertNotNull(gatewayAuthPrincipal("dev-local", "dev", verify, expectedStaticToken = "s3cret-token", allowDevToken = true))
        }
    }
}
