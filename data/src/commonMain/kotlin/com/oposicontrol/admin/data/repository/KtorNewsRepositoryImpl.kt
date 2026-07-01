package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.storage.LocalBinaryAsset
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
    private val fileReader: PlatformFileReader,
    private val adapter: NetworkResultAdapter,
) : NewsRepository {
    private val news = MutableStateFlow<List<NewsArticle>>(emptyList())

    override fun observeNews(): Flow<List<NewsArticle>> = news.asStateFlow()

    override suspend fun refreshNews() = adapter.safeCall {
        client.get(newsUrl()) {
            backendHeaders()
        }.body<List<NoticiaDto>>().map(NoticiaDto::toDomain).also { news.value = it }
    }

    override suspend fun upsertNews(article: NewsArticle) = adapter.safeCall {
        val body = article.toDto()
        val response = if (article.id.isBlank()) {
            client.post(newsUrl()) {
                expectSuccess = false
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            client.put(newsUrl()) {
                expectSuccess = false
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value}: ${response.bodyAsText().take(300)}")
        }
        response.body<NoticiaDto>().toDomain().also { savedArticle ->
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

    override suspend fun uploadNewsAsset(localPathOrUri: String) = adapter.safeCall {
        val readPath = localPathOrUri.removePrefix("file://")
        val asset = when (val read = fileReader.read(readPath)) {
            is AppResult.Failure -> throw IllegalStateException(read.error.message)
            is AppResult.Success -> read.value
        }
        require(asset.bytes.size <= NEWS_ASSET_MAX_BYTES) { "La imagen de noticia supera el limite de 10 MB." }
        require(asset.mimeType?.startsWith("image/") == true) { "El asset de noticia debe ser una imagen." }
        val response = client.post("${newsUrl()}/assets") {
            expectSuccess = false
            backendHeaders()
            setBody(asset.toMultipartBody())
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} /content/noticias/assets: ${response.bodyAsText().take(200)}")
        }
        response.body<NewsAssetUploadDto>().toDomain()
    }

    private fun newsUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/content/noticias"

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() }
            ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun LocalBinaryAsset.toMultipartBody(): MultiPartFormDataContent {
        return MultiPartFormDataContent(
            formData {
                append(
                    key = "file",
                    value = bytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${fileName.sanitizeFilename()}\"")
                        append(HttpHeaders.ContentType, mimeType ?: ContentType.Application.OctetStream.toString())
                    },
                )
            },
        )
    }

    private fun String.sanitizeFilename(): String = replace("\"", "").ifBlank { "news-asset.bin" }

    private companion object {
        const val NEWS_ASSET_MAX_BYTES = 10 * 1024 * 1024
    }
}

@Serializable
private data class NewsAssetUploadDto(
    val path: String,
    val publicUrl: String,
    val mimeType: String? = null,
)

@Serializable
private data class NoticiaDto(
    val id: String = "",
    val titulo: String = "",
    val especialidad: String = "",
    val tipo: String = "",
    val fondo: String = "SKY",
    val fondoPath: String? = null,
    val estado: String = "draft",
    val publicarDesde: String = "",
    val texto: String = "",
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

private fun NewsAssetUploadDto.toDomain() = NewsAssetUpload(
    path = path,
    publicUrl = publicUrl,
    mimeType = mimeType,
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
