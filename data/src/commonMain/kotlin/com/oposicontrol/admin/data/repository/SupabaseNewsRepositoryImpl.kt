package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.repository.NewsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class SupabaseNewsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : NewsRepository {
    private val news = MutableStateFlow<List<NewsArticle>>(emptyList())

    override fun observeNews(): Flow<List<NewsArticle>> = news.asStateFlow()

    override suspend fun refreshNews(): AppResult<List<NewsArticle>> {
        val token = requireAccessToken("cargar noticias") ?: return authFailure("cargar noticias")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            val rows: List<NewsRowDto> = client.get("${appConfig.supabaseUrl}/rest/v1/noticia?select=*&deleted_at=is.null&order=created_at.desc") {
                supabaseHeaders(token)
            }.body()
            val layers = if (rows.isEmpty()) {
                emptyList()
            } else {
                val ids = rows.mapNotNull { it.id }.joinToString(",")
                runCatching {
                    client.get("${appConfig.supabaseUrl}/rest/v1/noticia_layer?select=*&noticia_id=in.($ids)&deleted_at=is.null&order=sort_order.asc") {
                        supabaseHeaders(token)
                    }.body<List<NewsLayerRowDto>>()
                }.getOrElse { emptyList() }
            }
            val byNews = layers.groupBy { it.newsId }
            rows.map { row -> row.toDomain(byNews[row.id].orEmpty().map { it.toDomain() }) }
                .also { news.value = it }
        }
    }

    override suspend fun upsertNews(article: NewsArticle): AppResult<NewsArticle> {
        val token = requireAccessToken("guardar noticia") ?: return authFailure("guardar noticia")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val saved: NewsRowDto = client.post("${appConfig.supabaseUrl}/rest/v1/noticia?on_conflict=id") {
                supabaseHeaders(token)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(article.toRowDto()))
            }.body<List<NewsRowDto>>().first()
            val savedId = saved.id ?: error("Supabase no devolvio id de noticia.")
            runCatching {
                client.delete("${appConfig.supabaseUrl}/rest/v1/noticia_layer?noticia_id=eq.$savedId") {
                    supabaseHeaders(token)
                }
                if (article.layers.isNotEmpty()) {
                    client.post("${appConfig.supabaseUrl}/rest/v1/noticia_layer") {
                        supabaseHeaders(token)
                        header("Prefer", "return=minimal")
                        contentType(ContentType.Application.Json)
                        setBody(article.layers.mapIndexed { index, layer -> layer.toRowDto(savedId, index) })
                    }
                }
            }
            saved.toDomain(article.layers.mapIndexed { index, layer -> layer.copy(sortOrder = index) })
        }
        if (result is AppResult.Success) refreshNews()
        return result
    }

    override suspend fun deleteNews(newsId: String): AppResult<Unit> {
        val token = requireAccessToken("borrar noticia") ?: return authFailure("borrar noticia")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val numericId = newsId.toIntOrNull()
        val result = adapter.safeCall {
            if (numericId != null) {
                client.patch("${appConfig.supabaseUrl}/rest/v1/noticia?id=eq.$numericId") {
                    supabaseHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("deleted_at" to Clock.System.now().toString()))
                }
            }
            Unit
        }
        if (result is AppResult.Success) refreshNews()
        return result
    }

    override suspend fun uploadNewsAsset(localPathOrUri: String): AppResult<NewsAssetUpload> {
        return AppResult.Failure(AppError.Validation("La subida de assets de noticia requiere el backend Ktor."))
    }

    private fun HttpRequestBuilder.supabaseHeaders(accessToken: String) {
        header("apikey", appConfig.supabaseAnonKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun requireAccessToken(action: String): String? = sessionLocalDataSource.currentSession()?.accessToken

    private fun authFailure(action: String): AppResult.Failure {
        return AppResult.Failure(AppError.Authentication("Necesitas iniciar sesion para $action."))
    }

    private fun ensureConfigured(): AppError.Validation? {
        if (appConfig.supabaseUrl.isBlank()) return AppError.Validation("SUPABASE_URL no esta configurado.")
        if (appConfig.supabaseAnonKey.isBlank()) return AppError.Validation("SUPABASE_ANON_KEY no esta configurado.")
        return null
    }
}

@Serializable
private data class NewsRowDto(
    val id: Int? = null,
    val titulo: String,
    @SerialName("tipo_fondo")
    val backgroundType: String = "A",
    @SerialName("especialidad_id")
    val specialty: Int = 1,
    val texto: String,
    @SerialName("tipo_noticia_id")
    val type: Int = 1,
    @SerialName("imagen_path")
    val imagePath: String? = null,
    val estado: String = "draft",
    @SerialName("publicar_desde")
    val publishFrom: String? = null,
)

@Serializable
private data class NewsLayerRowDto(
    val id: String,
    @SerialName("noticia_id")
    val newsId: Int,
    val name: String,
    val kind: String,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    @SerialName("font_family")
    val fontFamily: String = "sans",
    @SerialName("text_color")
    val textColor: String = "",
    val visible: Boolean,
    @SerialName("sort_order")
    val sortOrder: Int,
)

private fun NewsRowDto.toDomain(layers: List<NewsLayer>) = NewsArticle(
    id = id?.toString().orEmpty(),
    title = titulo,
    specialty = specialty.toString(),
    type = type.toString(),
    backgroundType = backgroundType,
    backgroundPath = imagePath,
    status = estado,
    publishFrom = publishFrom.orEmpty(),
    text = texto,
    imagePath = imagePath,
    layers = layers,
)

private fun NewsArticle.toRowDto() = NewsRowDto(
    id = id.toIntOrNull(),
    titulo = title,
    backgroundType = backgroundType,
    specialty = specialty.toIntOrNull() ?: 1,
    texto = text,
    type = type.toIntOrNull() ?: 1,
    imagePath = imagePath ?: backgroundPath,
    estado = status,
    publishFrom = publishFrom.ifBlank { null },
)

private fun NewsLayerRowDto.toDomain() = NewsLayer(
    id = id,
    name = name,
    kind = kind,
    content = content,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = opacity,
    fontFamily = fontFamily,
    textColor = textColor,
    visible = visible,
    sortOrder = sortOrder,
)

private fun NewsLayer.toRowDto(newsId: Int, order: Int) = NewsLayerRowDto(
    id = id.ifBlank { randomUuid() },
    newsId = newsId,
    name = name,
    kind = kind,
    content = content,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = opacity,
    fontFamily = fontFamily,
    textColor = textColor,
    visible = visible,
    sortOrder = order,
)
