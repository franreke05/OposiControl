package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KtorTagSearchRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : TagSearchRepository {
    override suspend fun findTags(query: String) = adapter.safeCall {
        // Legacy /tags disabled until the endpoint is migrated to /v1.
        emptyList<Tag>()
    }

    override suspend fun search(query: TagSearchQuery) = adapter.safeCall {
        // Legacy /search disabled until the endpoint is migrated to /v1.
        emptyList<TaggedSearchResult>()
    }

    override suspend fun reindex() = adapter.safeCall {
        // Legacy /search/reindex disabled until search is migrated to /v1.
        Unit
    }

    private fun backendUrl(): String = appConfig.baseUrl.trimEnd('/')

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() } ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

private suspend fun HttpResponse.failureDetail(route: String): String {
    val body = bodyAsText().take(200).ifBlank { "empty response body" }
    return "backend ${status.value} $route: $body"
}

@Serializable
private data class TagDto(
    val id: String = "",
    val nombre: String,
    val slug: String,
    val origen: String = "auto",
)

@Serializable
private data class TaggedSearchResultDto(
    val entityType: String,
    val entityId: String,
    val section: String,
    val title: String,
    val summary: String = "",
    val tags: List<TagDto> = emptyList(),
    val updatedAt: String = "",
)

private fun TagDto.toDomain() = Tag(
    id = id,
    name = nombre,
    slug = slug,
    origin = origen,
)

private fun TaggedSearchResultDto.toDomain() = TaggedSearchResult(
    entityType = entityType,
    entityId = entityId,
    section = section,
    title = title,
    summary = summary,
    tags = tags.map { it.toDomain() },
    updatedAt = updatedAt,
)
