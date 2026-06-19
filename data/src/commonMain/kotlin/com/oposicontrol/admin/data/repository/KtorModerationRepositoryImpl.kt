package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.repository.ModerationRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KtorModerationRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ModerationRepository {

    override suspend fun listPendingReports() = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_REPORTES)) { backendHeaders() }
        r.requireSuccess(BackendRoutes.ADMIN_V1_REPORTES)
        r.body<ReportesV1Response>().data.map { it.toDomain() }
    }

    override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int) = adapter.safeCall {
        val path = BackendRoutes.adminV1ReporteResolver(reportId)
        client.post(url(path)) {
            backendHeaders(); contentType(ContentType.Application.Json)
            setBody(ResolverReporteBody(accion = action, notas = notes, ticketsReporter = ticketsReporter))
        }.requireSuccess(path)
        Unit
    }

    override suspend fun grantTickets(userId: String, amount: Int, reason: String) = adapter.safeCall {
        val path = BackendRoutes.adminV1UsuarioTickets(userId)
        val r = client.post(url(path)) {
            backendHeaders(); contentType(ContentType.Application.Json)
            setBody(GrantTicketsBody(cantidad = amount, motivo = reason))
        }
        r.requireSuccess(path)
        r.body<GrantTicketsResponse>().saldo
    }

    private fun url(path: String) = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() } ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable private data class ReporteV1Dto(
    val id: String,
    val tipoEntidad: String = "",
    val entidadId: String = "",
    val motivo: String = "",
    val descripcion: String = "",
    val estado: String = "",
    val creadoEn: String = "",
    val reportadoPor: String = "",
    val reporterNombre: String = "",
)
@Serializable private data class ReportesV1Response(val data: List<ReporteV1Dto>)
@Serializable private data class ResolverReporteBody(val accion: String, val notas: String? = null, val ticketsReporter: Int = 0)
@Serializable private data class GrantTicketsBody(val cantidad: Int, val motivo: String)
@Serializable private data class GrantTicketsResponse(val saldo: Int = 0)

private fun ReporteV1Dto.toDomain() = ForumReport(
    id = id,
    entityType = tipoEntidad,
    entityId = entidadId,
    reason = motivo,
    description = descripcion,
    status = estado,
    createdAt = creadoEn,
    reporterId = reportadoPor,
    reporterName = reporterNombre,
)
