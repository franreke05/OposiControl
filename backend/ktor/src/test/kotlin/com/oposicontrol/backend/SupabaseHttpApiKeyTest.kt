package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SupabaseHttpApiKeyTest {
    @Test
    fun `opaque secret key is sent only as apikey`() = runTest {
        val headers = captureHeaders(
            secretKey = "sb_secret_backend_test",
            request = { http ->
                http.internalRequest(HttpMethod.Get, "/rest/v1/usuario?limit=1")
            },
        )

        assertEquals("sb_secret_backend_test", headers.apiKey)
        assertEquals(null, headers.authorization)
    }

    @Test
    fun `legacy service-role JWT is also sent as Bearer`() = runTest {
        val legacyKey = "header.payload.signature"
        val headers = captureHeaders(
            secretKey = legacyKey,
            request = { http ->
                http.internalRequest(HttpMethod.Get, "/rest/v1/usuario?limit=1")
            },
        )

        assertEquals(legacyKey, headers.apiKey)
        assertEquals("Bearer $legacyKey", headers.authorization)
    }

    @Test
    fun `user JWT remains the Bearer credential with an opaque server key`() = runTest {
        val headers = captureHeaders(
            secretKey = "sb_secret_backend_test",
            request = { http ->
                http.actorRequest(
                    method = HttpMethod.Get,
                    path = "/rest/v1/usuario?limit=1",
                    actorAccessToken = "user.header.payload",
                )
            },
        )

        assertEquals("sb_secret_backend_test", headers.apiKey)
        assertEquals("Bearer user.header.payload", headers.authorization)
    }

    private suspend fun captureHeaders(
        secretKey: String,
        request: suspend (SupabaseHttp) -> Unit,
    ): CapturedHeaders {
        var captured = CapturedHeaders(null, null)
        val client = HttpClient(
            MockEngine { requestData ->
                captured = CapturedHeaders(
                    apiKey = requestData.headers["apikey"],
                    authorization = requestData.headers[HttpHeaders.Authorization],
                )
                respond(
                    content = ByteReadChannel("[]"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json.toString(),
                    ),
                )
            },
        ) {
            expectSuccess = false
        }
        val http = SupabaseHttp(
            configuration = testBackendConfiguration(secretKey),
            json = Json { ignoreUnknownKeys = true },
            client = client,
        )

        request(http)
        client.close()
        return captured
    }
}

private data class CapturedHeaders(
    val apiKey: String?,
    val authorization: String?,
)

private fun testBackendConfiguration(secretKey: String) = BackendConfiguration(
    supabaseUrl = "https://example.supabase.co",
    supabasePublishableKey = "sb_publishable_backend_test",
    supabaseSecretKey = secretKey,
    jwtAudience = "authenticated",
    appEnvironment = "test",
    serviceVersion = "test",
    bootstrapAdminEmail = null,
    developmentAdminUserId = 1,
    oposibotsPasswordRecoveryRedirectUrl = null,
    appleClientId = null,
    appleClientSecret = null,
    trustProxyHeaders = false,
)
