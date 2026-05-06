package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiClientFactory(
    private val appConfig: AppConfig,
    private val logger: Logger,
) {
    fun create(): HttpClient {
        return HttpClient(platformHttpClientEngineFactory()) {
            install(DefaultRequest) {
                header("ngrok-skip-browser-warning", "true")
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        prettyPrint = false
                        explicitNulls = false
                    },
                )
            }
            install(Auth)
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 6_000
                socketTimeoutMillis = 6_000
            }
            install(Logging) {
                level = LogLevel.HEADERS
                sanitizeHeader { header ->
                    header == HttpHeaders.Authorization ||
                        header.equals("apikey", ignoreCase = true) ||
                        header == HttpHeaders.Cookie ||
                        header == HttpHeaders.SetCookie
                }
                logger = object : KtorLogger {
                    override fun log(message: String) {
                        this@ApiClientFactory.logger.debug("ktor: $message")
                    }
                }
            }
        }
    }
}
