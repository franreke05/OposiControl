package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.logging.SafeLogField
import com.oposicontrol.admin.core.common.logging.currentOperationContextOrNull
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel as KtorLogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

const val HEADER_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_REQUEST_ID = "X-Request-ID"
const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
private const val HEADER_RETRY_ATTEMPT = "X-Retry-Attempt"

class ApiClientFactory(
    private val appConfig: AppConfig,
    private val logger: Logger,
) {
    fun create(): HttpClient {
        return HttpClient(platformHttpClientEngineFactory()) { configure() }
    }

    internal fun create(engine: HttpClientEngine): HttpClient {
        return HttpClient(engine) { configure() }
    }

    private fun HttpClientConfig<*>.configure() {
        install(DefaultRequest) {
            header("ngrok-skip-browser-warning", "true")
        }
        install(
            createClientPlugin("OposiControlOperationContext") {
                onRequest { request, _ ->
                    val operationContext = currentOperationContextOrNull()
                    val correlationId = request.headers[HEADER_CORRELATION_ID]
                        ?: operationContext?.correlationId
                        ?: OperationContext().correlationId
                    val requestId = request.headers[HEADER_REQUEST_ID]
                        ?: OperationContext().requestId
                    request.headers.remove(HEADER_CORRELATION_ID)
                    request.headers.remove(HEADER_REQUEST_ID)
                    request.headers.append(HEADER_CORRELATION_ID, correlationId)
                    request.headers.append(HEADER_REQUEST_ID, requestId)
                    logger.log(
                        LogEvent(
                            level = LogLevel.DEBUG,
                            tag = LogTag.NETWORK,
                            event = "request.dispatch",
                            layer = LogLayer.NETWORK,
                            correlationId = correlationId,
                            requestId = requestId,
                            fields = mapOf(
                                SafeLogField.HTTP_METHOD to request.method.value,
                                SafeLogField.ROUTE to request.url.build().encodedPath.normalizeRoute(),
                            ),
                        ),
                    )
                }
            },
        )
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                    explicitNulls = false
                    encodeDefaults = true
                },
            )
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { request, response ->
                request.isRetrySafe() && (
                    response.status == HttpStatusCode.RequestTimeout ||
                        response.status == HttpStatusCode.TooManyRequests ||
                        response.status.value in 500..599
                    )
            }
            retryOnExceptionIf { request, cause ->
                cause !is CancellationException && request.isRetrySafe()
            }
            exponentialDelay(
                base = 2.0,
                baseDelayMs = 250,
                maxDelayMs = 1_000,
                randomizationMs = 100,
            )
            modifyRequest { request ->
                request.headers.remove(HEADER_REQUEST_ID)
                request.headers.remove(HEADER_RETRY_ATTEMPT)
                request.headers.append(HEADER_REQUEST_ID, OperationContext().requestId)
                request.headers.append(HEADER_RETRY_ATTEMPT, retryCount.toString())
                logger.log(
                        LogEvent(
                            level = LogLevel.INFO,
                            tag = LogTag.NETWORK,
                            event = "request.retry",
                            layer = LogLayer.NETWORK,
                            correlationId = request.headers[HEADER_CORRELATION_ID],
                            requestId = request.headers[HEADER_REQUEST_ID],
                            fields = mapOf(
                            SafeLogField.ATTEMPT to retryCount.toString(),
                            SafeLogField.HTTP_METHOD to request.method.value,
                            SafeLogField.ROUTE to request.url.build().encodedPath.normalizeRoute(),
                        ),
                    ),
                )
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 6_000
            socketTimeoutMillis = 6_000
        }
        if (
            appConfig.environment == Environment.DEV &&
            appConfig.featureFlags.enableVerboseNetworkLogs
        ) {
            install(Logging) {
                level = KtorLogLevel.INFO
                sanitizeHeader { header ->
                    header == HttpHeaders.Authorization ||
                        header.equals("apikey", ignoreCase = true) ||
                        header == HttpHeaders.Cookie ||
                        header == HttpHeaders.SetCookie
                }
                logger = object : KtorLogger {
                    override fun log(message: String) {
                        this@ApiClientFactory.logger.debug("ktor $message")
                    }
                }
            }
        }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.isRetrySafe(): Boolean {
    return method in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options) ||
        headers.contains(HEADER_IDEMPOTENCY_KEY)
}

private fun io.ktor.client.request.HttpRequest.isRetrySafe(): Boolean {
    return method in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options) ||
        headers.contains(HEADER_IDEMPOTENCY_KEY)
}

private fun String.normalizeRoute(): String {
    return replace(Regex("/\\d+(?=/|$)"), "/{id}")
        .replace(
            Regex(
                "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                    "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}(?=/|$)",
            ),
            "/{id}",
        )
}
