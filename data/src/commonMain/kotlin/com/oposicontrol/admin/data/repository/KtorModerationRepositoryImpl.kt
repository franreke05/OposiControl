package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportContentPreview
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.repository.ModerationRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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

    override suspend fun listReports(includeResolved: Boolean) = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_REPORTES)) {
            backendHeaders()
            if (includeResolved) parameter("estado", "todos")
        }
        r.requireSuccess(BackendRoutes.ADMIN_V1_REPORTES)
        r.body<ReportesV1Response>().data.map { it.toDomain() }
    }

    override suspend fun getReport(reportId: String) = adapter.safeCall {
        val path = BackendRoutes.adminV1Reporte(reportId)
        val r = client.get(url(path)) { backendHeaders() }
        r.requireSuccess(path)
        val dto = r.body<ReporteV1Dto>()
        ForumReportDetail(
            report = dto.toDomain(),
            contentPreview = dto.contentPreview?.toDomain(),
        )
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

    override suspend fun warnUser(userId: String, message: String) = adapter.safeCall {
        val path = BackendRoutes.adminV1ModerationActions()
        client.post(url(path)) {
            backendHeaders(); contentType(ContentType.Application.Json)
            setBody(WarnUserBody(actionType = "advertencia", targetUser = userId, reason = message))
        }.requireSuccess(path)
        Unit
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
    val revisadoEn: String = "",
    val contentPreview: ContentPreviewV1Dto? = null,
)
@Serializable private data class ContentPreviewV1Dto(
    val type: String,
    val text: String = "",
    val author: String = "",
    val createdAt: String = "",
    val threadTitle: String? = null,
    val currentState: String = "",
)
@Serializable private data class ReportesV1Response(val data: List<ReporteV1Dto>)
@Serializable private data class ResolverReporteBody(val accion: String, val notas: String? = null, val ticketsReporter: Int = 0)
@Serializable private data class GrantTicketsBody(val cantidad: Int, val motivo: String)
@Serializable private data class GrantTicketsResponse(val saldo: Int = 0)
@Serializable private data class WarnUserBody(val actionType: String, val targetUser: String, val reason: String)

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
    resolvedAt = revisadoEn,
)

private fun ContentPreviewV1Dto.toDomain() = ForumReportContentPreview(
    type = type,
    text = text,
    author = author,
    createdAt = createdAt,
    threadTitle = threadTitle,
    currentState = currentState,
)
