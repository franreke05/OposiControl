package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiClientPolicyTest {
    @Test
    fun getRetriesTransientStatusesWithStableCorrelationAndFreshRequestIds() = runTest {
        var attempts = 0
        val correlations = mutableListOf<String?>()
        val requestIds = mutableListOf<String?>()
        val engine = MockEngine { request ->
            attempts += 1
            correlations += request.headers[HEADER_CORRELATION_ID]
            requestIds += request.headers[HEADER_REQUEST_ID]
            respond(
                content = if (attempts == 3) "ok" else "retry",
                status = when (attempts) {
                    1 -> HttpStatusCode.InternalServerError
                    2 -> HttpStatusCode.TooManyRequests
                    else -> HttpStatusCode.OK
                },
            )
        }
        val client = factory().create(engine)

        val response = client.get("https://backend.test/resource")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, attempts)
        assertEquals(1, correlations.filterNotNull().distinct().size)
        assertEquals(3, requestIds.filterNotNull().distinct().size)
        client.close()
    }

    @Test
    fun postWithoutIdempotencyKeyIsNeverRetried() = runTest {
        var attempts = 0
        val client = factory().create(
            MockEngine {
                attempts += 1
                respond("failed", HttpStatusCode.InternalServerError)
            },
        )

        val response = client.post("https://backend.test/command")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(1, attempts)
        client.close()
    }

    @Test
    fun idempotentPostCanRetry() = runTest {
        var attempts = 0
        val client = factory().create(
            MockEngine {
                attempts += 1
                respond(
                    content = if (attempts == 1) "retry" else "ok",
                    status = if (attempts == 1) {
                        HttpStatusCode.ServiceUnavailable
                    } else {
                        HttpStatusCode.OK
                    },
                )
            },
        )

        val response = client.post("https://backend.test/command") {
            header(HEADER_IDEMPOTENCY_KEY, "operation-123")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts)
        client.close()
    }

    @Test
    fun resultAdapterPreservesStructuredCancellation() = runTest {
        val adapter = NetworkResultAdapter(NoOpLogger)

        val thrown = assertFailsWith<CancellationException> {
            adapter.safeCall<Unit>(operation = "test.cancel") {
                throw CancellationException("cancelled")
            }
        }

        assertEquals("cancelled", thrown.message)
    }

    @Test
    fun conflictStatusMapsToOptimisticConcurrencyError() = runTest {
        val result = NetworkResultAdapter(NoOpLogger).safeCall<Unit>(operation = "test.conflict") {
            throw BackendHttpException(HttpStatusCode.Conflict.value)
        }

        val error = (result as AppResult.Failure).error
        assertEquals(true, error is AppError.Conflict)
    }

    @Test
    fun operationCorrelationFlowsIntoHttpHeaders() = runTest {
        var receivedCorrelation: String? = null
        val client = factory().create(
            MockEngine { request ->
                receivedCorrelation = request.headers[HEADER_CORRELATION_ID]
                respond("ok", HttpStatusCode.OK)
            },
        )

        val result = NetworkResultAdapter(NoOpLogger).safeCall(
            operation = "test.correlation",
            context = OperationContext(correlationId = "corr-test", requestId = "operation-only"),
        ) {
            client.get("https://backend.test/resource")
        }

        assertEquals(true, result is AppResult.Success)
        assertEquals("corr-test", receivedCorrelation)
        client.close()
    }

    private fun factory() = ApiClientFactory(
        appConfig = AppConfig(
            environment = Environment.STAGING,
            baseUrl = "https://backend.test",
            supabaseUrl = "https://supabase.test",
            supabasePublishableKey = "sb_publishable_test",
            supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(
                useFakeData = false,
                enableLogs = true,
                enableAnalytics = false,
                enableVerboseNetworkLogs = false,
                fakeLatencyMs = 0,
                fakeErrorRate = 0.0,
            ),
            appName = "OposiControl",
            appVersion = "test",
        ),
        logger = NoOpLogger,
    )
}
