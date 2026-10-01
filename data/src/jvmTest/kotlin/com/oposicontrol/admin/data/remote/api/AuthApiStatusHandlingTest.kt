package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.remote.source.RealAuthDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * MASTER6-AUTH-002: logout comprueba el estado de la respuesta.
 * MASTER6-AUTH-003: un refresh fallido solo es "rechazo definitivo" (AppError.Authentication) con un 4xx de rechazo.
 */
class AuthApiStatusHandlingTest {
    private val config = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun source(status: HttpStatusCode, requests: MutableList<HttpRequestData> = mutableListOf()): RealAuthDataSource {
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond("""{"error":"x","error_description":"y"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(AdminApiJson) } }
        return RealAuthDataSource(AuthApi(client, config), NetworkResultAdapter(NoOpLogger))
    }

    @Test
    fun logoutTreatsAnExpiredTokenAsAnAuthenticationFailureNotAsSuccess() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val result = source(status).logout("expired")
            assertIs<AppResult.Failure>(result, "logout $status")
            assertIs<AppError.Authentication>(result.error)
        }
    }

    @Test
    fun logoutServerFailureIsReportedAsNotRevoked() = runTest {
        val result = source(HttpStatusCode.ServiceUnavailable).logout("t")
        assertIs<AppResult.Failure>(result)
        assertEquals(AppError.Network("Server response failed", 503), result.error)
    }

    @Test
    fun logoutSucceedsOn204AndRevokesOnlyThisDevicesSession() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        assertEquals(AppResult.Success(Unit), source(HttpStatusCode.NoContent, requests).logout("t"))
        assertEquals("local", requests.single().url.parameters["scope"])
    }

    @Test
    fun refreshTransientFailuresAreNetworkErrorsNotAuthentication() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.RequestTimeout, HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway)) {
            val result = source(status).refreshSession("r")
            assertIs<AppResult.Failure>(result, "refresh $status")
            assertIs<AppError.Network>(result.error, "refresh $status no debe borrar la sesion")
            assertEquals(status.value, (result.error as AppError.Network).code)
        }
    }

    @Test
    fun refreshRejectionsAreDefinitive() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val result = source(status).refreshSession("r")
            assertIs<AppResult.Failure>(result, "refresh $status")
            assertIs<AppError.Authentication>(result.error, "refresh $status")
        }
    }
}
