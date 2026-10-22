package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.repository.TagsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

class KtorTagsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : TagsRepository {
    private val tagsFlow = MutableStateFlow<List<Tag>>(emptyList())

    override fun observeTags(): Flow<List<Tag>> = tagsFlow.asStateFlow()

    override suspend fun refreshTags() = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_ETIQUETAS)) { backendHeaders() }
        r.requireSuccess(BackendRoutes.ADMIN_V1_ETIQUETAS)
        r.body<EtiquetasV1Response>().data.map { it.toDomain() }.also { tagsFlow.value = it }
    }

    override suspend fun upsertTag(tag: Tag) = adapter.safeCall {
        val r = if (tag.id.isBlank()) {
            client.post(url(BackendRoutes.ADMIN_V1_ETIQUETAS)) {
                backendHeaders(); contentType(ContentType.Application.Json); setBody(tag.toDto())
            }
        } else {
            client.patch(url("${BackendRoutes.ADMIN_V1_ETIQUETAS}/${tag.id}")) {
                backendHeaders(); contentType(ContentType.Application.Json); setBody(tag.toDto())
            }
        }
        r.requireSuccess(BackendRoutes.ADMIN_V1_ETIQUETAS)
        r.body<EtiquetaV1Response>().data.toDomain().also { refreshTags() }
    }

    override suspend fun deleteTag(tagId: String) = adapter.safeCall {
        client.delete(url("${BackendRoutes.ADMIN_V1_ETIQUETAS}/$tagId")) { backendHeaders() }
            .requireSuccess("${BackendRoutes.ADMIN_V1_ETIQUETAS}/$tagId")
        refreshTags(); Unit
    }

    private fun url(path: String) = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }
}

@Serializable private data class EtiquetaV1Dto(val id: String = "", val nombre: String, val slug: String = "", val descripcion: String? = null, val activa: Boolean = true)
@Serializable private data class EtiquetasV1Response(val data: List<EtiquetaV1Dto>)
@Serializable private data class EtiquetaV1Response(val data: EtiquetaV1Dto)

private fun EtiquetaV1Dto.toDomain() = Tag(id = id, name = nombre, slug = slug, origin = "manual", active = activa)
private fun Tag.toDto() = EtiquetaV1Dto(id = id, nombre = name, slug = slug, activa = active)
