package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseOposiBotsAuthGatewayTest {
    @Test
    fun `login returns access token only after checking canonical account status`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = mockClient { request ->
            requests += RecordedRequest(
                method = request.method.value,
                url = request.url.toString(),
                apiKey = request.headers["apikey"],
                authorization = request.headers[HttpHeaders.Authorization],
            )
            when (requests.size) {
                1 -> jsonResponse(
                    status = HttpStatusCode.OK,
                    body = """
                        {
                          "access_token": "consumer-access-token",
                          "user": {
                            "id": "00000000-0000-0000-0000-000000000123",
                            "email": "person@example.com"
                          }
                        }
                    """.trimIndent(),
                )

                2 -> jsonResponse(
                    status = HttpStatusCode.OK,
                    body = """[{"activo":true,"deleted_at":null,"perfil_completo":false}]""",
                )

                else -> error("Unexpected request ${request.url}")
            }
        }
        val gateway = gateway(client)

        val result = gateway.login(
            request = OposiBotsLoginRequest(
                identifier = "person@example.com",
                password = "secret-password",
                keepSignedIn = true,
            ),
            context = testContext(),
        )

        assertEquals(OposiBotsLoginResponse("consumer-access-token"), result)
        assertEquals(2, requests.size)
        assertTrue(requests[0].url.endsWith("/auth/v1/token?grant_type=password"))
        assertEquals(TEST_PUBLISHABLE_KEY, requests[0].apiKey)
        assertEquals(null, requests[0].authorization)
        assertTrue(requests[1].url.contains("/rest/v1/usuario?"))
        assertTrue(
            requests[1].url.contains(
                "auth_user_id=eq.00000000-0000-0000-0000-000000000123",
            ),
        )
        assertEquals(TEST_SECRET_KEY, requests[1].apiKey)
        assertEquals(null, requests[1].authorization)
    }

    @Test
    fun `invalid credentials become a safe AuthenticationRequired error`() = runTest {
        var requestCount = 0
        val client = mockClient {
            requestCount += 1
            jsonResponse(
                status = HttpStatusCode.BadRequest,
                body = """
                    {
                      "error_code": "invalid_credentials",
                      "msg": "No account exists for person@example.com using secret-password"
                    }
                """.trimIndent(),
            )
        }
        val gateway = gateway(client)

        val problem = assertFailsWith<OposiBotsAuthProblem> {
            gateway.login(
                request = OposiBotsLoginRequest(
                    identifier = "person@example.com",
                    password = "secret-password",
                    keepSignedIn = false,
                ),
                context = testContext(),
            )
        }

        assertEquals(1, requestCount)
        assertEquals(HttpStatusCode.Unauthorized, problem.status)
        assertEquals(
            OposiBotsDomainErrorCode.AUTHENTICATION_REQUIRED,
            problem.domainError,
        )
        assertFalse(problem.message.orEmpty().contains("person@example.com"))
        assertFalse(problem.message.orEmpty().contains("secret-password"))
        assertEquals(
            OposiBotsDomainErrorResponse(
                error = OposiBotsDomainErrorCode.AUTHENTICATION_REQUIRED,
            ),
            problem.response(),
        )
    }

    @Test
    fun `recover collapses upstream not-found into success`() = runTest {
        var requestCount = 0
        val client = mockClient { request ->
            requestCount += 1
            assertTrue(request.url.toString().contains("/auth/v1/recover"))
            jsonResponse(
                status = HttpStatusCode.NotFound,
                body = """{"error_code":"user_not_found","msg":"User does not exist"}""",
            )
        }
        val gateway = gateway(client)

        gateway.recoverAccess(
            request = OposiBotsRecoverAccessRequest("missing@example.com"),
            context = testContext(),
        )

        assertEquals(1, requestCount)
    }

    @Test
    fun `non-idempotent register POST is never retried after upstream 5xx`() = runTest {
        var requestCount = 0
        val client = mockClient { request ->
            requestCount += 1
            assertTrue(request.url.toString().endsWith("/auth/v1/admin/users"))
            jsonResponse(
                status = HttpStatusCode.ServiceUnavailable,
                body = """{"message":"temporary upstream failure"}""",
            )
        }
        val gateway = gateway(client)

        val problem = assertFailsWith<OposiBotsAuthProblem> {
            gateway.register(
                request = OposiBotsRegisterRequest(
                    identifier = "person@example.com",
                    password = "secret-password",
                ),
                context = testContext(),
            )
        }

        assertEquals(1, requestCount)
        assertEquals(HttpStatusCode.ServiceUnavailable, problem.status)
        assertEquals(
            OposiBotsDomainErrorCode.TEMPORARILY_UNAVAILABLE,
            problem.domainError,
        )
    }

    @Test
    fun `legacy service-role JWT remains a Bearer credential`() = runTest {
        val legacyServiceRoleKey = "header.payload.signature"
        var authorization: String? = null
        val client = mockClient { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            jsonResponse(
                status = HttpStatusCode.ServiceUnavailable,
                body = """{"message":"temporary upstream failure"}""",
            )
        }
        val gateway = gateway(client, legacyServiceRoleKey)

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.register(
                request = OposiBotsRegisterRequest(
                    identifier = "person@example.com",
                    password = "secret-password",
                ),
                context = testContext(),
            )
        }

        assertEquals("Bearer $legacyServiceRoleKey", authorization)
    }

    private fun gateway(
        client: HttpClient,
        secretKey: String = TEST_SECRET_KEY,
    ): SupabaseOposiBotsAuthGateway =
        SupabaseOposiBotsAuthGateway(
            configuration = gatewayConfiguration(secretKey),
            json = Json { ignoreUnknownKeys = true },
            client = client,
        )

    private fun testContext() =
        OposiBotsAuthOperationContext(requestId = "gateway-test-request")
}

private data class RecordedRequest(
    val method: String,
    val url: String,
    val apiKey: String?,
    val authorization: String?,
)

private fun mockClient(
    handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
        io.ktor.client.request.HttpRequestData,
    ) -> io.ktor.client.request.HttpResponseData,
): HttpClient = HttpClient(MockEngine(handler)) {
    expectSuccess = false
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(
    status: HttpStatusCode,
    body: String,
) = respond(
    content = ByteReadChannel(body),
    status = status,
    headers = headersOf(
        HttpHeaders.ContentType,
        ContentType.Application.Json.toString(),
    ),
)

private fun gatewayConfiguration(secretKey: String) = BackendConfiguration(
    supabaseUrl = "https://example.supabase.co",
    supabasePublishableKey = TEST_PUBLISHABLE_KEY,
    supabaseSecretKey = secretKey,
    jwtAudience = "authenticated",
    appEnvironment = "test",
    serviceVersion = "test",
    bootstrapAdminEmail = null,
    developmentAdminUserId = 1,
    oposibotsPasswordRecoveryRedirectUrl = "oposibots://auth/reset-password",
    appleClientId = null,
    appleClientSecret = null,
    trustProxyHeaders = false,
)

private const val TEST_PUBLISHABLE_KEY = "sb_publishable_gateway_test"
private const val TEST_SECRET_KEY = "sb_secret_gateway_test"
