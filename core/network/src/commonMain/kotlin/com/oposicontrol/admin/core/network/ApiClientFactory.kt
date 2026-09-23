package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
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
            // F-04: presupuesto por defecto de 30 s. Era de 6 s, y el servidor admite
            // hasta 20 s por CADA llamada a Supabase y operaciones que encadenan
            // varias: el cliente declaraba fallo mientras Ktor seguía escribiendo, y
            // el reintento natural duplicaba lotes o preguntas. Las operaciones que
            // de verdad tardan más (PDF, publicar un lote, portada, panel de Inicio)
            // amplían el suyo por petición con `timeout { }`. El connect sigue corto:
            // si no conecta en 4 s, no va a conectar. Sin reintentos automáticos: un
            // POST repetido por el cliente es exactamente el duplicado a evitar.
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
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

    companion object {
        /** Presupuesto por defecto de petición y de socket (F-04). */
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 30_000
    }
}
