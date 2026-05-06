package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticCheck
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticReport
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class KtorConnectionDiagnosticsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ConnectionDiagnosticsRepository {
    override suspend fun runChecks() = adapter.safeCall {
        val checks = mutableListOf<ConnectionDiagnosticCheck>()
        val baseUrl = appConfig.baseUrl.trimEnd('/')
        checks += ConnectionDiagnosticCheck(
            name = "URL",
            ok = !baseUrl.contains("localhost") && !baseUrl.contains("127.0.0.1"),
            detail = baseUrl,
        )
        val sessionToken = sessionLocalDataSource.currentSession()?.accessToken?.ifBlank { null }
        checks += ConnectionDiagnosticCheck(
            name = "Auth token",
            ok = sessionToken != null || appConfig.backendToken.isNotBlank(),
            detail = when {
                sessionToken != null -> "sesion"
                appConfig.backendToken.isNotBlank() -> "backend configurado"
                else -> "sin token configurado"
            },
        )
        checks += runCheck("GET /health") {
            client.get("$baseUrl/health").diagnosticDetail()
        }
        checks += runCheck("GET /tags auth") {
            client.get("$baseUrl/tags") {
                backendHeaders()
                parameter("q", "")
            }.diagnosticDetail()
        }
        checks += runCheck("GET /search auth") {
            client.get("$baseUrl/search") {
                backendHeaders()
                parameter("q", "tcae")
            }.diagnosticDetail()
        }
        checks += runCheck("GET /content/especialidades") {
            client.get("$baseUrl/content/especialidades") {
                backendHeaders()
            }.diagnosticDetail()
        }
        ConnectionDiagnosticReport(checks)
    }

    private suspend fun runCheck(
        name: String,
        block: suspend () -> String,
    ): ConnectionDiagnosticCheck {
        return try {
            ConnectionDiagnosticCheck(name = name, ok = true, detail = block().ifBlank { "OK" })
        } catch (throwable: Throwable) {
            ConnectionDiagnosticCheck(
                name = name,
                ok = false,
                detail = throwable.toDiagnosticDetail(appConfig.baseUrl),
            )
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() }
            ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpResponse.diagnosticDetail(): String {
        val text = bodyAsText()
        val preview = text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)
            .ifBlank { "sin cuerpo" }
        if (!status.isSuccess()) {
            error(status.toDiagnosticDetail(preview))
        }
        return "${status.value} ${status.description}: $preview"
    }

    private fun io.ktor.http.HttpStatusCode.toDiagnosticDetail(bodyPreview: String): String {
        return when (value) {
            401 -> "401 Unauthorized: token rechazado o no enviado. Body: $bodyPreview"
            403 -> "403 Forbidden: token valido sin permisos suficientes. Body: $bodyPreview"
            404 -> "404 Not Found: endpoint no registrado en Ktor. Body: $bodyPreview"
            in 500..599 -> "$value ${description}: backend/Supabase fallo. Body: $bodyPreview"
            else -> "$value ${description}: $bodyPreview"
        }
    }

    private fun Throwable.toDiagnosticDetail(baseUrl: String): String {
        val raw = message ?: this::class.simpleName.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("cleartext") ->
                "Cleartext HTTP bloqueado: revisa usesCleartextTraffic en Android debug. $raw"
            lower.contains("connection refused") || lower.contains("failed to connect") && lower.contains("refused") ->
                "Connection refused: IP alcanzable, pero puerto 8080 cerrado o Ktor no arrancado. $raw"
            lower.contains("connect timeout") || lower.contains("timeout") ->
                "Timeout de red: el movil no recibe respuesta de $baseUrl. Revisa firewall Windows, red Publica/Privada, IP del PC o aislamiento WiFi. $raw"
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "Host/IP incorrecto o sin DNS: revisa BASE_URL. $raw"
            else -> raw
        }
    }
}
