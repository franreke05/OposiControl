package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticCheck
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticReport
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
            ok = true,
            detail = "$baseUrl${if (baseUrl.isLocalDevelopmentUrl()) " (desarrollo local)" else ""}",
        )
        val sessionToken = sessionLocalDataSource.currentSession()?.accessToken?.ifBlank { null }
        checks += ConnectionDiagnosticCheck(
            name = "Auth token legacy/admin",
            ok = true,
            detail = when {
                sessionToken != null -> "sesion"
                appConfig.backendToken.isNotBlank() -> "backend configurado"
                else -> "pendiente: no requerido para /v1 contenido"
            },
        )
        checks += runCheck("GET ${BackendRoutes.HEALTH}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.HEALTH)).diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.V1_ESPECIALIDADES}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.V1_ESPECIALIDADES)).diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.V1_EXAMENES}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.V1_EXAMENES)).diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.ADMIN_V1_ESPECIALIDADES}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.ADMIN_V1_ESPECIALIDADES)) {
                backendHeaders()
            }.diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.ADMIN_V1_EXAMENES}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.ADMIN_V1_EXAMENES)) {
                backendHeaders()
            }.diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.ADMIN_V1_FEEDBACK_PREGUNTAS}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.ADMIN_V1_FEEDBACK_PREGUNTAS)) {
                backendHeaders()
            }.diagnosticDetail()
        }
        checks += runCheck("GET ${BackendRoutes.ADMIN_V1_AUDITORIA}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.ADMIN_V1_AUDITORIA)) {
                backendHeaders()
            }.diagnosticDetail()
        }
        checks += ConnectionDiagnosticCheck(
            name = "GET /tags auth",
            ok = true,
            detail = "pendiente: endpoint no migrado a /v1",
        )
        checks += ConnectionDiagnosticCheck(
            name = "GET /search auth",
            ok = true,
            detail = "pendiente: endpoint no migrado a /v1",
        )
        ConnectionDiagnosticReport(checks)
    }

    override suspend fun requestBackendRestart() = adapter.safeCall {
        val baseUrl = appConfig.baseUrl.trimEnd('/')
        val response = client.post("$baseUrl/admin/backend/restart") {
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error(response.status.toDiagnosticDetail(response.bodyAsText().toBodyPreview()))
        }
    }

    override suspend fun requestConnectionRestart() = adapter.safeCall {
        val baseUrl = appConfig.baseUrl.trimEnd('/')
        val response = client.post("$baseUrl/admin/dev/restart-connection") {
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error(response.status.toDiagnosticDetail(response.bodyAsText().toBodyPreview()))
        }
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
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }

    private suspend fun HttpResponse.diagnosticDetail(): String {
        val text = bodyAsText()
        val preview = text.toBodyPreview()
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

    private fun String.toBodyPreview(): String {
        return replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .take(520)
            .ifBlank { "sin cuerpo" }
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
                baseUrl.backendUnavailableDetail(raw)
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                baseUrl.backendUnavailableDetail(raw)
            else -> raw
        }
    }

    private fun String.backendUnavailableDetail(raw: String): String {
        return if (contains("ngrok", ignoreCase = true)) {
            "Backend no disponible en $this. Activa ngrok o cambia BASE_URL a IP local/10.0.2.2. $raw"
        } else {
            "Backend no disponible en $this. Revisa Ktor, firewall, red, IP local o 10.0.2.2. $raw"
        }
    }

    private fun String.isLocalDevelopmentUrl(): Boolean {
        return contains("localhost", ignoreCase = true) ||
            contains("127.0.0.1") ||
            contains("10.0.2.2")
    }
}
