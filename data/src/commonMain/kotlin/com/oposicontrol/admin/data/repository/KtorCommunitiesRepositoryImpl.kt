package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class KtorCommunitiesRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : CommunitiesRepository {
    private val communitiesFlow = MutableStateFlow<List<Community>>(emptyList())

    override fun observeCommunities(): Flow<List<Community>> = communitiesFlow.asStateFlow()

    override suspend fun refreshCommunities() = adapter.safeCall {
        val r = client.get(url(BackendRoutes.ADMIN_V1_COMUNIDADES)) { backendHeaders() }
        r.requireSuccess(BackendRoutes.ADMIN_V1_COMUNIDADES)
        r.body<ComunidadesV1Response>().data.map { it.toDomain() }.also { communitiesFlow.value = it }
    }

    override suspend fun upsertCommunity(community: Community) = adapter.safeCall {
        val r = if (community.id.isBlank()) {
            client.post(url(BackendRoutes.ADMIN_V1_COMUNIDADES)) {
                backendHeaders(); contentType(ContentType.Application.Json); setBody(community.toDto())
            }
        } else {
            client.patch(url("${BackendRoutes.ADMIN_V1_COMUNIDADES}/${community.id}")) {
                backendHeaders(); contentType(ContentType.Application.Json); setBody(community.toDto())
            }
        }
        r.requireSuccess(BackendRoutes.ADMIN_V1_COMUNIDADES)
        r.body<ComunidadV1Response>().data.toDomain().also { refreshCommunities() }
    }

    override suspend fun deleteCommunity(communityId: String) = adapter.safeCall {
        client.delete(url("${BackendRoutes.ADMIN_V1_COMUNIDADES}/$communityId")) { backendHeaders() }
            .requireSuccess("${BackendRoutes.ADMIN_V1_COMUNIDADES}/$communityId")
        refreshCommunities(); Unit
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

@Serializable private data class ComunidadV1Dto(val id: String = "", val nombre: String, val codigo: String = "", val slug: String = "", val activa: Boolean = true, val posicion: Int = 0)
@Serializable private data class ComunidadesV1Response(val data: List<ComunidadV1Dto>)
@Serializable private data class ComunidadV1Response(val data: ComunidadV1Dto)

private fun ComunidadV1Dto.toDomain() = Community(
    id = id, name = nombre, code = codigo, slug = slug, isActive = activa, sortOrder = posicion,
    createdAt = Instant.parse(Clock.System.now().toString()),
    updatedAt = Instant.parse(Clock.System.now().toString()),
)
private fun Community.toDto() = ComunidadV1Dto(id = id, nombre = name, codigo = code, slug = slug, activa = isActive, posicion = sortOrder)
