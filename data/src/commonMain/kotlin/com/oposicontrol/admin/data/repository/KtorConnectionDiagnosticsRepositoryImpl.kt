package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticCheck
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticReport
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
        checks += runCheck("GET ${BackendRoutes.HEALTH_LIVE}") {
            client.get(BackendRoutes.url(baseUrl, BackendRoutes.HEALTH_LIVE)).diagnosticDetail()
        }
        val sessionToken = sessionLocalDataSource.currentSession()?.accessToken?.takeIf(String::isNotBlank)
        checks += ConnectionDiagnosticCheck(
            name = "JWT Supabase",
            ok = sessionToken != null,
            detail = if (sessionToken != null) "sesion disponible" else "inicia sesion para comprobar rutas administrativas",
        )
        if (sessionToken != null) {
            checks += runCheck("GET ${BackendRoutes.AUTH_ME}") {
                client.get(BackendRoutes.url(baseUrl, BackendRoutes.AUTH_ME)) {
                    bearerToken(sessionToken)
                }.diagnosticDetail()
            }
            checks += runCheck("GET ${BackendRoutes.SPECIALTIES}") {
                authenticatedDiagnostic(
                    baseUrl = baseUrl,
                    canonicalPath = BackendRoutes.SPECIALTIES,
                    legacyPath = "/v1/especialidades",
                    token = sessionToken,
                )
            }
            checks += runCheck("GET ${BackendRoutes.EXAMS}") {
                authenticatedDiagnostic(
                    baseUrl = baseUrl,
                    canonicalPath = BackendRoutes.EXAMS,
                    legacyPath = "/v1/examenes",
                    token = sessionToken,
                )
            }
        }
        ConnectionDiagnosticReport(checks)
    }

    override suspend fun requestBackendRestart() = adapter.safeCall {
        val baseUrl = appConfig.baseUrl.trimEnd('/')
        val response = client.post("$baseUrl/admin/backend/restart") {
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error(response.status.toDiagnosticDetail())
        }
    }

    override suspend fun requestConnectionRestart() = adapter.safeCall {
        val baseUrl = appConfig.baseUrl.trimEnd('/')
        val response = client.post("$baseUrl/admin/dev/restart-connection") {
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error(response.status.toDiagnosticDetail())
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
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() } ?: return
        bearerToken(token)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearerToken(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun authenticatedDiagnostic(
        baseUrl: String,
        canonicalPath: String,
        legacyPath: String,
        token: String,
    ): String {
        val canonical = client.get(BackendRoutes.url(baseUrl, canonicalPath)) {
            bearerToken(token)
        }
        if (canonical.status != HttpStatusCode.NotFound) return canonical.diagnosticDetail()
        val legacy = client.get(BackendRoutes.url(baseUrl, legacyPath)) {
            bearerToken(token)
        }
        return "${legacy.diagnosticDetail()} (adaptador de lectura heredado)"
    }

    private suspend fun HttpResponse.diagnosticDetail(): String {
        if (!status.isSuccess()) {
            error(status.toDiagnosticDetail())
        }
        return "${status.value} ${status.description}"
    }

    private fun io.ktor.http.HttpStatusCode.toDiagnosticDetail(): String {
        return when (value) {
            401 -> "401 Unauthorized: inicia sesion de nuevo."
            403 -> "403 Forbidden: la sesion no tiene permisos suficientes."
            404 -> "404 Not Found: endpoint no registrado en Ktor."
            in 500..599 -> "$value ${description}: fallo interno de backend o Supabase."
            else -> "$value $description"
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
