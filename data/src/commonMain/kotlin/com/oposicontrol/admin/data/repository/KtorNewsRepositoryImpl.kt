package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.repository.NewsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

class KtorNewsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : NewsRepository {
    private val news = MutableStateFlow<List<NewsArticle>>(emptyList())

    override fun observeNews(): Flow<List<NewsArticle>> = news.asStateFlow()

    override suspend fun refreshNews() = adapter.safeCall {
        val response = client.get(newsUrl()) {
            expectSuccess = false
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} /content/noticias: ${response.bodyAsText().take(200)}")
        }
        response.body<List<NoticiaDto>>()
            .map { it.toDomain() }
            .also { news.value = it }
    }

    override suspend fun upsertNews(article: NewsArticle) = adapter.safeCall {
        val body = article.toDto()
        val saved: NoticiaDto = if (article.id.isBlank()) {
            client.post(newsUrl()) {
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        } else {
            client.put(newsUrl()) {
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        }
        saved.toDomain().also { savedArticle ->
            news.value = listOf(savedArticle) + news.value.filterNot { it.id == savedArticle.id }
        }
    }

    override suspend fun deleteNews(newsId: String) = adapter.safeCall {
        client.delete("${newsUrl()}/$newsId") {
            backendHeaders()
        }
        news.value = news.value.filterNot { it.id == newsId }
        Unit
    }

    private fun newsUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/content/noticias"

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() }
            ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable
private data class NoticiaDto(
    val id: String = "",
    val titulo: String,
    val especialidad: String = "",
    val tipo: String = "",
    val fondo: String = "SKY",
    val fondoPath: String? = null,
    val estado: String = "draft",
    val publicarDesde: String = "",
    val texto: String,
    val imagenPath: String? = null,
    val capas: List<NoticiaLayerDto> = emptyList(),
)

@Serializable
private data class NoticiaLayerDto(
    val id: String = "",
    val nombre: String,
    val tipo: String,
    val contenido: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String = "sans",
    val textColor: String = "",
    val visible: Boolean,
    val locked: Boolean = false,
    val orden: Int,
    val rotationDegrees: Int = 0,
)

private fun NoticiaDto.toDomain() = NewsArticle(
    id = id,
    title = titulo,
    specialty = especialidad,
    type = tipo,
    backgroundType = fondo,
    backgroundPath = fondoPath,
    status = estado,
    publishFrom = publicarDesde,
    text = texto,
    imagePath = imagenPath,
    layers = capas.map { it.toDomain() },
)

private fun NewsArticle.toDto() = NoticiaDto(
    id = id,
    titulo = title,
    especialidad = specialty,
    tipo = type,
    fondo = backgroundType.ifBlank { "SKY" },
    fondoPath = backgroundPath,
    estado = status.ifBlank { "draft" },
    publicarDesde = publishFrom,
    texto = text,
    imagenPath = imagePath,
    capas = layers.mapIndexed { index, layer -> layer.toDto(index) },
)

private fun NoticiaLayerDto.toDomain() = NewsLayer(
    id = id,
    name = nombre,
    kind = tipo,
    content = contenido,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = opacity,
    fontFamily = fontFamily,
    textColor = textColor,
    visible = visible,
    sortOrder = orden,
    locked = locked,
    rotationDegrees = rotationDegrees.normalizedDegrees(),
)

private fun NewsLayer.toDto(order: Int) = NoticiaLayerDto(
    id = id.ifBlank { randomUuid() },
    nombre = name,
    tipo = kind,
    contenido = content,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = opacity,
    fontFamily = fontFamily,
    textColor = textColor,
    visible = visible,
    locked = locked,
    orden = order,
    rotationDegrees = rotationDegrees.normalizedDegrees(),
)

private fun Int.normalizedDegrees(): Int {
    val mod = this % 360
    return if (mod < 0) mod + 360 else mod
}
