package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseOposiBotsAuthGatewayTest {
    @Test
    fun registerUsesSignupNeverAdminCreateAndForwardsSanitizedClientIp() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("""{"user":{"id":"user"}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(forwardClientIp = true),
            Json { ignoreUnknownKeys = true },
            client,
        )

        gateway.register(
            OposiBotsRegisterRequest("person@example.com", "safe-password"),
            OposiBotsAuthContext("request-123", "203.0.113.10"),
        )

        assertEquals(1, requests.size)
        assertEquals("/auth/v1/signup", requests.single().url.encodedPath)
        assertFalse(requests.single().url.encodedPath.contains("admin/users"))
        assertEquals("server-secret", requests.single().headers["apikey"])
        assertEquals("203.0.113.10", requests.single().headers["Sb-Forwarded-For"])
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun loginCommandIsNeverRetriedAfterUpstreamFailure() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond(
                content = ByteReadChannel("""{"message":"unavailable"}"""),
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(),
            Json { ignoreUnknownKeys = true },
            client,
        )

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.login(
                OposiBotsLoginRequest("person@example.com", "safe-password"),
                OposiBotsAuthContext("request-123", "127.0.0.1"),
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun profileWriteUsesAuthUidRpcAndNeverSendsUserId() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/user" -> respond(
                    ByteReadChannel("""{"id":"auth-user","email":"person@example.com"}"""),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    ByteReadChannel(""),
                    HttpStatusCode.NoContent,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(),
            Json { ignoreUnknownKeys = true },
            client,
        )

        gateway.saveProfile(
            "a".repeat(80),
            OposiBotsProfileRequest("Ada", "Lovelace", "ada", "Sanidad"),
            OposiBotsAuthContext("request-123", "127.0.0.1"),
        )

        val rpc = requests.last()
        assertEquals("/rest/v1/rpc/oposibots_upsert_own_profile", rpc.url.encodedPath)
        val body = rpc.body.toString()
        assertFalse(body.contains("auth_user_id"))
        assertTrue(rpc.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true)
        assertEquals("client-publishable", rpc.headers["apikey"])
    }

    @Test
    fun oauthReplayClaimFailureRevokesMintedSession() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(
                    ByteReadChannel(
                        """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"auth-user","email":"person@example.com"}}""",
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(
                    ByteReadChannel("""{"code":"23505"}"""),
                    HttpStatusCode.Conflict,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/auth/v1/logout" -> respond(
                    ByteReadChannel(""),
                    HttpStatusCode.NoContent,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(),
            Json { ignoreUnknownKeys = true },
            client,
        )

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.google(
                OposiBotsGoogleRequest(testJwt()),
                OposiBotsAuthContext("request-123", "127.0.0.1"),
            )
        }

        assertEquals(
            listOf("/auth/v1/token", "/rest/v1/rpc/oposibots_claim_oauth_credential", "/auth/v1/logout"),
            requests.map { it.url.encodedPath },
        )
        assertEquals("Bearer minted-access", requests.last().headers[HttpHeaders.Authorization])
        assertEquals("scope=global", requests.last().url.encodedQuery)
    }
}

private fun testAuthConfiguration(forwardClientIp: Boolean = false) = OposiBotsAuthConfiguration(
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
    forwardClientIpToSupabase = forwardClientIp,
    rateLimitMode = "memory",
    rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
    valkeyUrl = null,
)

private fun testJwt(): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = encoder.encodeToString("""{"sub":"oauth-user","exp":4102444800}""".toByteArray())
    return "$header.$payload.signature"
}
