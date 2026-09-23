package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumReplyPage
import com.oposicontrol.admin.domain.model.ForumThread
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import com.oposicontrol.admin.domain.model.ForumThreadPage
import com.oposicontrol.admin.domain.repository.ForumRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KtorForumRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ForumRepository {

    override suspend fun listThreads(cursor: String?, reportedOnly: Boolean, categoryId: String?, pageSize: Int) = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_FORO_HILOS)) {
            backendHeaders()
            parameter("limit", pageSize)
            cursor?.let { parameter("cursor", it) }
            if (reportedOnly) parameter("reportados", "true")
            categoryId?.let { parameter("categoriaId", it) }
        }
        r.requireSuccess(BackendRoutes.ADMIN_V1_FORO_HILOS)
        val dto = r.body<ForoHilosV1Response>()
        ForumThreadPage(items = dto.items.map { it.toDomain() }, nextCursor = dto.nextCursor)
    }

    override suspend fun getThread(threadId: String) = adapter.safeCall {
        val path = BackendRoutes.adminV1ForoHilo(threadId)
        val r = client.get(url(path)) { backendHeaders() }
        r.requireSuccess(path)
        r.body<ForoHiloDetailV1Dto>().toDomain()
    }

    override suspend fun listReplies(threadId: String, cursor: String?, pageSize: Int) = adapter.safeCall {
        val path = BackendRoutes.adminV1ForoHiloRespuestas(threadId)
        val r = client.get(url(path)) {
            backendHeaders()
            parameter("limit", pageSize)
            cursor?.let { parameter("cursor", it) }
        }
        r.requireSuccess(path)
        val dto = r.body<ForoRespuestasV1Response>()
        ForumReplyPage(items = dto.items.map { it.toDomain() }, nextCursor = dto.nextCursor)
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

@Serializable private data class ForoHilosV1Response(val items: List<ForoHiloV1Dto>, val nextCursor: String? = null)

@Serializable private data class ForoHiloV1Dto(
    val id: String,
    val categoriaId: String? = null,
    val categoriaNombre: String = "",
    val titulo: String = "",
    val fragmento: String = "",
    val estado: String = "",
    val fijada: Boolean = false,
    val autorNombre: String = "",
    val creadoEn: String = "",
    val actualizadoEn: String = "",
    val respuestas: Int = 0,
    val tieneReportePendiente: Boolean = false,
)

private fun ForoHiloV1Dto.toDomain() = ForumThread(
    id = id,
    categoryId = categoriaId,
    categoryName = categoriaNombre,
    title = titulo,
    snippet = fragmento,
    status = estado,
    pinned = fijada,
    authorName = autorNombre,
    createdAt = creadoEn,
    updatedAt = actualizadoEn,
    replyCount = respuestas,
    hasPendingReport = tieneReportePendiente,
)

@Serializable private data class ForoHiloDetailV1Dto(
    val id: String,
    val categoriaId: String? = null,
    val categoriaNombre: String = "",
    val titulo: String = "",
    val contenido: String = "",
    val estado: String = "",
    val fijada: Boolean = false,
    val autorNombre: String = "",
    val creadoEn: String = "",
    val actualizadoEn: String = "",
    val respuestas: Int = 0,
    val reportesAsociados: Int = 0,
)

private fun ForoHiloDetailV1Dto.toDomain() = ForumThreadDetail(
    id = id,
    categoryId = categoriaId,
    categoryName = categoriaNombre,
    title = titulo,
    content = contenido,
    status = estado,
    pinned = fijada,
    authorName = autorNombre,
    createdAt = creadoEn,
    updatedAt = actualizadoEn,
    replyCount = respuestas,
    associatedReportsCount = reportesAsociados,
)

@Serializable private data class ForoRespuestasV1Response(val items: List<ForoRespuestaV1Dto>, val nextCursor: String? = null)

@Serializable private data class ForoRespuestaV1Dto(
    val id: String,
    val autorNombre: String = "",
    val contenido: String = "",
    val estado: String = "",
    val creadoEn: String = "",
)

private fun ForoRespuestaV1Dto.toDomain() = ForumReply(
    id = id,
    authorName = autorNombre,
    content = contenido,
    status = estado,
    createdAt = creadoEn,
)
