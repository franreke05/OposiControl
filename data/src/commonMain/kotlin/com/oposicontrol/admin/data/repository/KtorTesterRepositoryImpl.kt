package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.TesterReport
import com.oposicontrol.admin.domain.model.TesterReportPage
import com.oposicontrol.admin.domain.repository.TesterRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KtorTesterRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : TesterRepository {

    override suspend fun listReports(status: String?, cursor: String?, type: String?, pageSize: Int) = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_FEEDBACK_PREGUNTAS)) {
            backendHeaders()
            parameter("limit", pageSize)
            cursor?.let { parameter("cursor", it) }
            status?.let { parameter("estado", it) }
            type?.let { parameter("tipo", it) }
        }
        r.requireSuccess(BackendRoutes.ADMIN_V1_FEEDBACK_PREGUNTAS)
        val dto = r.body<AdminFeedbacksV1Response>()
        TesterReportPage(items = dto.data.map { it.toDomain() }, nextCursor = dto.nextCursor)
    }

    private fun url(path: String) = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() } ?: return
        header(io.ktor.http.HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable private data class AdminFeedbacksV1Response(val data: List<AdminFeedbackV1Dto>, val nextCursor: String? = null)

@Serializable private data class AdminFeedbackV1Dto(
    val id: String = "",
    val usuarioId: String? = null,
    val autorNombre: String = "",
    val preguntaId: String = "",
    val intentoId: String? = null,
    val tipoFeedback: String = "",
    val descripcion: String = "",
    val estado: String = "pendiente",
    val creadoEn: String = "",
)

private fun AdminFeedbackV1Dto.toDomain() = TesterReport(
    id = id,
    authorName = autorNombre,
    type = tipoFeedback,
    description = descripcion,
    status = estado,
    createdAt = creadoEn,
)
