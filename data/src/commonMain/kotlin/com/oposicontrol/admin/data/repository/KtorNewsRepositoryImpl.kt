package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.data.storage.LocalBinaryAsset
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.model.NewsListFilter
import com.oposicontrol.admin.domain.model.NewsPage
import com.oposicontrol.admin.domain.model.NewsSummary
import com.oposicontrol.admin.domain.repository.NewsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Noticias sobre el contrato canonico `/api/v1/admin/noticias*` (D4-01..D4-09).
 *
 * Lo que decide este fichero y por que:
 *  - El cuerpo de edicion se construye a mano como [JsonObject]: el `Json` del cliente omite los nulos
 *    (`explicitNulls = false`), y `backgroundPath`/`imagenPath` necesitan un `null` EXPLICITO para vaciar
 *    el vinculo (D4-04). Con un DTO, quitar el fondo de una noticia guardada no viajaria y el servidor
 *    respondria 200 con el fondo anterior.
 *  - `capas` es un objeto opaco por contrato: se escribe con los mismos nombres que la fila `jsonb`
 *    (`nombre`, `tipo`, `contenido`, `orden`...) y se lee con tolerancia por capa, para que una capa que
 *    otro escritor dejo mal formada no tumbe la ficha entera.
 *  - Las rutas de asset del fondo y de la imagen principal viajan como RUTA del bucket (`news/<uuid>/...`),
 *    que es lo unico que el servidor acepta; la URL publica la devuelve el servidor y aqui solo se transporta.
 *    Las filas antiguas guardaron una URL completa en esa columna: se conserva tal cual (el servidor no la
 *    revalida si no cambia) y se usa directamente como URL para pintarla.
 */
class KtorNewsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val fileReader: PlatformFileReader,
    private val adapter: NetworkResultAdapter,
) : NewsRepository {
    private val news = MutableStateFlow<List<NewsSummary>>(emptyList())

    override fun observeNews(): Flow<List<NewsSummary>> = news.asStateFlow()

    override suspend fun refreshNews(filter: NewsListFilter): AppResult<NewsPage> = adapter.safeCall {
        val response = client.get(url(NEWS)) {
            backendHeaders()
            parameter("limit", PAGE_SIZE)
            applyFilter(filter)
            expectSuccess = false
        }
        response.requireSuccess(NEWS)
        response.body<NoticiaPageDto>().toDomain().also { page -> news.value = page.items }
    }

    override suspend fun loadMoreNews(filter: NewsListFilter, cursor: String): AppResult<NewsPage> = adapter.safeCall {
        val response = client.get(url(NEWS)) {
            backendHeaders()
            parameter("limit", PAGE_SIZE)
            parameter("cursor", cursor)
            applyFilter(filter)
            expectSuccess = false
        }
        response.requireSuccess(NEWS)
        response.body<NoticiaPageDto>().toDomain().also { page ->
            val known = news.value.map { it.id }.toSet()
            news.value = news.value + page.items.filterNot { it.id in known }
        }
    }

    override suspend fun getNews(newsId: String): AppResult<NewsArticle> = adapter.safeCall {
        val endpoint = "$NEWS/$newsId"
        val response = client.get(url(endpoint)) {
            backendHeaders()
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<NoticiaAdminDto>().toDomain()
    }

    override suspend fun createNews(article: NewsArticle, idempotencyKey: String): AppResult<NewsArticle> = adapter.safeCall {
        val response = client.post(url(NEWS)) {
            backendHeaders()
            header(IDEMPOTENCY_KEY, idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(article.toCreateBody().toString())
            expectSuccess = false
        }
        response.requireSuccess(NEWS)
        response.body<NoticiaAdminDto>().toDomain().also(::remember)
    }

    override suspend fun updateNews(article: NewsArticle): AppResult<NewsArticle> = adapter.safeCall {
        require(article.id.isNotBlank()) { "La noticia no tiene identificador: guarda primero el borrador." }
        require(article.version.isNotBlank()) { "La noticia no tiene version: vuelve a abrirla antes de editarla." }
        val endpoint = "$NEWS/${article.id}"
        val response = client.patch(url(endpoint)) {
            backendHeaders()
            header(HttpHeaders.IfMatch, article.version)
            contentType(ContentType.Application.Json)
            setBody(article.toPatchBody().toString())
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<NoticiaAdminDto>().toDomain().also(::remember)
    }

    override suspend fun deleteNews(newsId: String): AppResult<Unit> = adapter.safeCall {
        val endpoint = "$NEWS/$newsId"
        val response = client.delete(url(endpoint)) {
            backendHeaders()
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        news.value = news.value.filterNot { it.id == newsId }
    }

    override suspend fun publishNews(newsId: String): AppResult<NewsArticle> = adapter.safeCall {
        command("$NEWS/$newsId/publicar")
    }

    override suspend fun scheduleNews(newsId: String, publishFromIsoUtc: String): AppResult<NewsArticle> = adapter.safeCall {
        command("$NEWS/$newsId/programar", body = JsonObject(mapOf("publicarDesde" to JsonPrimitive(publishFromIsoUtc))))
    }

    override suspend fun archiveNews(newsId: String): AppResult<NewsArticle> = adapter.safeCall {
        command("$NEWS/$newsId/archivar")
    }

    override suspend fun uploadNewsAsset(
        localPathOrUri: String,
        idempotencyKey: String,
        onProgress: (Float) -> Unit,
    ): AppResult<NewsAssetUpload> = adapter.safeCall {
        val readPath = localPathOrUri.removePrefix("file://")
        val asset = when (val read = fileReader.read(readPath)) {
            is AppResult.Failure -> throw IllegalStateException(read.error.message)
            is AppResult.Success -> read.value
        }
        require(asset.bytes.size <= NEWS_ASSET_MAX_BYTES) { "La imagen supera el limite de 10 MB." }
        require(asset.mimeType in NEWS_ASSET_MIMES) { "La imagen debe ser PNG, JPG, WebP o GIF." }
        val endpoint = "$NEWS/assets"
        val response = client.post(url(endpoint)) {
            backendHeaders()
            header(IDEMPOTENCY_KEY, idempotencyKey)
            setBody(asset.toMultipartBody())
            onUpload { sent, total ->
                val size = total ?: asset.bytes.size.toLong()
                if (size > 0) onProgress((sent.toFloat() / size).coerceIn(0f, 1f))
            }
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<NoticiaAssetDto>().toDomain()
    }

    /** Un comando de estado: POST sin cuerpo (o con el de programar) que devuelve la ficha con su estado nuevo. */
    private suspend fun command(endpoint: String, body: JsonObject? = null): NewsArticle {
        val response = client.post(url(endpoint)) {
            backendHeaders()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        return response.body<NoticiaAdminDto>().toDomain().also(::remember)
    }

    /** La lista observada refleja la escritura sin volver a pedir la pagina: la fila sube arriba, como en el servidor. */
    private fun remember(article: NewsArticle) {
        news.value = listOf(article.toSummary()) + news.value.filterNot { it.id == article.id }
    }

    private fun url(endpoint: String): String = BackendRoutes.url(appConfig.baseUrl, endpoint)

    private fun HttpRequestBuilder.backendHeaders() {
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }

    private fun HttpRequestBuilder.applyFilter(filter: NewsListFilter) {
        filter.status?.takeIf { it.isNotBlank() }?.let { parameter("estado", it) }
        filter.specialtyId?.takeIf { it.isNotBlank() }?.let { parameter("especialidadId", it) }
        filter.typeId?.takeIf { it.isNotBlank() }?.let { parameter("tipoNoticiaId", it) }
        filter.query?.trim()?.takeIf { it.isNotBlank() }?.let { parameter("q", it.take(QUERY_MAX)) }
    }

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(300)}")
        }
    }

    private fun LocalBinaryAsset.toMultipartBody(): MultiPartFormDataContent = MultiPartFormDataContent(
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

    private fun String.sanitizeFilename(): String = replace("\"", "").ifBlank { "news-asset.bin" }

    private companion object {
        const val NEWS = "/api/v1/admin/noticias"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /** Tope del listado canonico (`LimitAdminCursor`): una pagina es lo que la biblioteca ensena de golpe. */
        const val PAGE_SIZE = 100
        const val QUERY_MAX = 100
        const val NEWS_ASSET_MAX_BYTES = 10 * 1024 * 1024
        val NEWS_ASSET_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Cable canonico
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
private data class NoticiaPageDto(
    val items: List<NoticiaResumenDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
private data class NoticiaResumenDto(
    val id: String = "",
    val titulo: String = "",
    val estado: String = "draft",
    val especialidadId: String = "",
    val tipoNoticiaId: String = "",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val imagenUrl: String? = null,
    val actualizadoEn: String = "",
)

@Serializable
private data class NoticiaAdminDto(
    val id: String = "",
    val titulo: String = "",
    val texto: String? = null,
    val capas: JsonArray = JsonArray(emptyList()),
    val tipoFondo: String = "A",
    val especialidadId: String = "",
    val tipoNoticiaId: String = "",
    val backgroundPath: String? = null,
    val imagenPath: String? = null,
    val backgroundUrl: String? = null,
    val imagenUrl: String? = null,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val version: String = "",
)

@Serializable
private data class NoticiaAssetDto(
    val path: String,
    val publicUrl: String,
    val mime: String? = null,
    val bytes: Int = 0,
)

/**
 * Una capa dentro de `capas`. Mismos nombres y mismos defaults que `NoticiaLayerDto` del backend legacy, que es
 * quien lee la misma columna para la lista `/content/noticias`: un default distinto aqui convertiria una capa
 * derivada en una capa con tamano fijo solo por pasar por el panel. `0` = derivado del tipo de capa.
 */
@Serializable
private data class NoticiaLayerDto(
    val id: String = "",
    val nombre: String = "",
    val tipo: String = "",
    val contenido: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 100,
    val height: Int = 40,
    val opacity: Int = 100,
    val fontFamily: String = "sans",
    val textColor: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val orden: Int = 0,
    val rotationDegrees: Int = 0,
    val fontSizeSp: Int = 0,
    val fontWeight: String = "",
)

private fun NoticiaPageDto.toDomain() = NewsPage(items = items.map { it.toDomain() }, nextCursor = nextCursor)

private fun NoticiaResumenDto.toDomain() = NewsSummary(
    id = id,
    title = titulo,
    status = estado,
    specialtyId = especialidadId,
    typeId = tipoNoticiaId,
    publishFrom = publicarDesde,
    publishedAt = publishedAt,
    imageUrl = imagenUrl,
    updatedAt = actualizadoEn,
)

private fun NoticiaAssetDto.toDomain() = NewsAssetUpload(path = path, publicUrl = publicUrl, mimeType = mime, bytes = bytes)

private fun NoticiaAdminDto.toDomain() = NewsArticle(
    id = id,
    title = titulo,
    specialty = especialidadId,
    type = tipoNoticiaId,
    backgroundType = tipoFondo.toEditorBackground(),
    backgroundPath = backgroundPath,
    status = estado,
    publishFrom = publicarDesde.orEmpty(),
    text = texto.orEmpty(),
    imagePath = imagenPath,
    layers = capas.decodeLayers(),
    version = version,
    publishedAt = publishedAt,
    backgroundUrl = resolveAssetUrl(backgroundPath, backgroundUrl),
    imageUrl = resolveAssetUrl(imagenPath, imagenUrl),
)

/**
 * Las filas creadas por el panel anterior guardaron la URL publica entera en `background_path`; el servidor
 * construye `backgroundUrl` a partir de esa columna sin distinguirlo, y el resultado no es una URL valida.
 * Una ruta que ya es una URL se usa tal cual; en cualquier otro caso manda lo que resolvio el servidor.
 */
private fun resolveAssetUrl(path: String?, serverUrl: String?): String? = when {
    path.isNullOrBlank() -> null
    path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> path
    else -> serverUrl?.takeIf { it.isNotBlank() }
}

private fun NewsArticle.toSummary() = NewsSummary(
    id = id,
    title = title,
    status = status,
    specialtyId = specialty,
    typeId = type,
    publishFrom = publishFrom.ifBlank { null },
    publishedAt = publishedAt,
    imageUrl = imageUrl,
    updatedAt = version,
)

/** Cuerpo de D4-03. Sin claves nulas: el contrato solo admite rutas como texto no vacio. */
private fun NewsArticle.toCreateBody(): JsonObject = JsonObject(
    mapOf(
        "titulo" to JsonPrimitive(title.trim()),
        "texto" to JsonPrimitive(text),
        "capas" to layers.toJsonArray(),
        "tipoFondo" to JsonPrimitive(backgroundType.toWireBackground()),
        "especialidadId" to JsonPrimitive(specialty),
        "tipoNoticiaId" to JsonPrimitive(type),
    ) + listOfNotNull(
        backgroundPath?.takeIf { it.isNotBlank() }?.let { "backgroundPath" to JsonPrimitive(it) },
        imagePath?.takeIf { it.isNotBlank() }?.let { "imagenPath" to JsonPrimitive(it) },
    ),
)

/**
 * Cuerpo de D4-04: todo el contenido, con `null` explicito en las dos rutas de asset cuando van vacias.
 * Enviar los campos que no cambiaron es inocuo (el servidor solo revalida lo que difiere de la fila) y evita
 * tener que calcular diferencias contra una copia que podria no ser la ultima leida.
 */
private fun NewsArticle.toPatchBody(): JsonObject = JsonObject(
    mapOf(
        "titulo" to JsonPrimitive(title.trim()),
        "texto" to JsonPrimitive(text),
        "capas" to layers.toJsonArray(),
        "tipoFondo" to JsonPrimitive(backgroundType.toWireBackground()),
        "especialidadId" to JsonPrimitive(specialty),
        "tipoNoticiaId" to JsonPrimitive(type),
        "backgroundPath" to backgroundPath.toJsonPathOrNull(),
        "imagenPath" to imagePath.toJsonPathOrNull(),
    ),
)

private fun String?.toJsonPathOrNull(): JsonElement = this?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull

/**
 * Las capas se escriben con TODAS sus claves (`encodeDefaults = true`), al contrario que el `Json` general del
 * cliente: `capas` es un objeto opaco que leen otros (la lista legacy, OposiBot), y una clave ausente obliga a cada
 * lector a conocer el default correcto. Una capa en `x = 0` o con `orden = 0` dice `0`, no «adivina».
 */
private val layerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private fun List<NewsLayer>.toJsonArray(): JsonArray = JsonArray(
    sortedBy { it.sortOrder }.mapIndexed { index, layer -> layerJson.encodeToJsonElement(layer.toDto(index)) },
)

/** Lectura tolerante por capa: una capa que no encaja se omite; las demas se conservan. */
private fun JsonArray.decodeLayers(): List<NewsLayer> =
    mapNotNull { element -> runCatching { layerJson.decodeFromJsonElement<NoticiaLayerDto>(element).toDomain() }.getOrNull() }
        .sortedBy { it.sortOrder }

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
    fontSizeSp = fontSizeSp,
    fontWeight = fontWeight,
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
    fontSizeSp = fontSizeSp,
    fontWeight = fontWeight,
)

private fun String.toWireBackground(): String = when (trim().uppercase()) {
    "MINT", "B" -> "B"
    "GOLD", "C" -> "C"
    else -> "A"
}

private fun String.toEditorBackground(): String = when (trim().uppercase()) {
    "B", "MINT" -> "MINT"
    "C", "GOLD" -> "GOLD"
    else -> "SKY"
}

private fun Int.normalizedDegrees(): Int {
    val mod = this % 360
    return if (mod < 0) mod + 360 else mod
}
