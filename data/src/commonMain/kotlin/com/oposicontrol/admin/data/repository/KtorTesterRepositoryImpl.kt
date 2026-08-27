package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.TesterQuestionContext
import com.oposicontrol.admin.domain.model.TesterReport
import com.oposicontrol.admin.domain.model.TesterReportDetail
import com.oposicontrol.admin.domain.model.TesterReportPage
import com.oposicontrol.admin.domain.repository.TesterRepository
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
import io.ktor.http.contentType
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

    override suspend fun getReport(id: String) = adapter.safeCall {
        val path = BackendRoutes.adminV1FeedbackPregunta(id)
        val r = client.get(url(path)) { backendHeaders() }
        r.requireSuccess(path)
        r.body<AdminFeedbackDetailV1Response>().data.toDomain()
    }

    override suspend fun resolveReport(id: String, estado: String, nota: String?) = adapter.safeCall {
        val path = BackendRoutes.adminV1FeedbackPreguntaResolver(id)
        client.post(url(path)) {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(ResolverFeedbackV1Body(estado = estado, nota = nota.orEmpty()))
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

@Serializable private data class AdminFeedbackDetailV1Response(val data: AdminFeedbackDetailV1Dto)

@Serializable private data class AdminFeedbackDetailV1Dto(
    val id: String = "",
    val usuarioId: String? = null,
    val autorNombre: String = "",
    val preguntaId: String = "",
    val intentoId: String? = null,
    val tipoFeedback: String = "",
    val descripcion: String = "",
    val estado: String = "pendiente",
    val creadoEn: String = "",
    val pregunta: AdminFeedbackPreguntaContextV1Dto? = null,
)

@Serializable private data class AdminFeedbackPreguntaContextV1Dto(
    val codigoPregunta: String = "",
    val titulo: String = "",
    val especialidadNombre: String? = null,
)

@Serializable private data class ResolverFeedbackV1Body(val estado: String, val nota: String = "")

private fun AdminFeedbackDetailV1Dto.toDomain() = TesterReportDetail(
    id = id,
    authorName = autorNombre,
    type = tipoFeedback,
    description = descripcion,
    status = estado,
    createdAt = creadoEn,
    question = pregunta?.let {
        TesterQuestionContext(code = it.codigoPregunta, title = it.titulo, specialtyName = it.especialidadNombre)
    },
)
