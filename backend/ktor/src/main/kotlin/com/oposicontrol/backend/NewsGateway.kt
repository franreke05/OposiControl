package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val STORED_NEWS_TEXT_PREFIX = "__OPOSICONTROL_NEWS_V1__\n"

interface NewsGateway {
    fun listNoticias(): List<NoticiaDto>
    fun saveNoticia(input: NoticiaDto): NoticiaDto
    fun deleteNoticia(noticiaId: String)
    fun uploadAsset(file: NewsUploadedFile): NewsAssetUploadDto
}

data class NewsUploadedFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

@Serializable
data class NewsAssetUploadDto(
    val path: String,
    val publicUrl: String,
    val mimeType: String? = null,
)

@Serializable
data class NoticiaDto(
    val id: String = "",
    val titulo: String,
    val especialidad: String = "",
    val tipo: String = "",
    val fondo: String = "A",
    val fondoPath: String? = null,
    val estado: String = "draft",
    val publicarDesde: String = "",
    val texto: String,
    val imagenPath: String? = null,
    val capas: List<NoticiaLayerDto> = emptyList(),
)

@Serializable
data class NoticiaLayerDto(
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

class InMemoryNewsGateway : NewsGateway {
    private val noticias = linkedMapOf<String, NoticiaDto>()

    override fun listNoticias(): List<NoticiaDto> = noticias.values.toList()

    override fun saveNoticia(input: NoticiaDto): NoticiaDto {
        require(input.titulo.isNotBlank()) { "La noticia necesita titulo." }
        require(input.texto.isNotBlank() || input.capas.any { it.contenido.isNotBlank() }) { "La noticia necesita texto o capas." }
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        return input.copy(id = id).also { noticias[id] = it }
    }

    override fun deleteNoticia(noticiaId: String) {
        noticias.remove(noticiaId)
    }

    override fun uploadAsset(file: NewsUploadedFile): NewsAssetUploadDto {
        val path = "news-assets/local/${file.fileName.sanitizeStorageSegment()}"
        return NewsAssetUploadDto(path = path, publicUrl = path, mimeType = file.mimeType)
    }
}

class SupabaseNewsGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val newsAssetsBucket: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : NewsGateway {
    @Volatile
    private var storageBucketChecked = false

    override fun listNoticias(): List<NoticiaDto> {
        val rows = request(
            method = "GET",
            path = "/rest/v1/noticia?select=*&order=id.desc",
            body = null,
        ).decodeList<SupabaseNoticiaRow>()
        return rows.map { row -> row.toDto(json) }
    }

    override fun saveNoticia(input: NoticiaDto): NoticiaDto {
        require(input.titulo.isNotBlank()) { "La noticia necesita titulo." }
        require(input.texto.isNotBlank() || input.capas.any { it.contenido.isNotBlank() }) { "La noticia necesita texto o capas." }
        val row = input.toSupabaseRow(
            json = json,
            resolveEspecialidadId = ::resolveEspecialidadId,
            resolveTipoNoticiaId = ::resolveTipoNoticiaId,
        )
        val saved = request(
            method = "POST",
            path = "/rest/v1/noticia?on_conflict=id",
            body = json.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<SupabaseNoticiaRow>().first()
        return saved.toDto(json)
    }

    override fun deleteNoticia(noticiaId: String) {
        val numericId = noticiaId.toIntOrNull() ?: throw IllegalArgumentException("Id de noticia no valido.")
        request(
            method = "DELETE",
            path = "/rest/v1/noticia?id=eq.$numericId",
            body = null,
        )
    }

    override fun uploadAsset(file: NewsUploadedFile): NewsAssetUploadDto {
        require(file.mimeType.startsWith("image/")) { "El asset de noticia debe ser una imagen." }
        val objectPath = "news/${UUID.randomUUID()}/${file.fileName.sanitizeStorageSegment()}"
        storageUpload(objectPath, file.mimeType, file.bytes)
        return NewsAssetUploadDto(
            path = objectPath,
            publicUrl = storagePublicUrl(objectPath),
            mimeType = file.mimeType,
        )
    }

    private fun storageUpload(objectPath: String, mimeType: String, bytes: ByteArray) {
        ensureStorageBucketExists()
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$newsAssetsBucket/$encodedPath"
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", mimeType)
            .header("x-upsert", "true")
        val response = try {
            client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase Storage network error on news asset upload: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase Storage news asset upload -> ${response.statusCode()}: ${response.body().take(300)}")
        }
    }

    private fun ensureStorageBucketExists() {
        if (storageBucketChecked) return
        val bucketId = newsAssetsBucket.toStorageBucketId()
        val body = """{"id":"$bucketId","name":"$bucketId","public":true}"""
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/bucket"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        val response = try {
            client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("Supabase Storage bucket check network error: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.statusCode() in 200..299 || response.statusCode() == 409) {
            storageBucketChecked = true
            return
        }
        throw IllegalStateException("Supabase Storage bucket check -> ${response.statusCode()}: ${response.body().take(300)}")
    }

    private fun storagePublicUrl(objectPath: String): String {
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        return "$supabaseUrl/storage/v1/object/public/$newsAssetsBucket/$encodedPath"
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        prefer: String? = null,
    ): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = try {
            client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            val preview = response.body().take(300)
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: $preview")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> {
        return json.decodeFromString(this)
    }

    private fun resolveEspecialidadId(value: String): Int {
        return resolveCatalogId(
            value = value,
            table = "especialidad",
            label = "especialidad",
        )
    }

    private fun resolveTipoNoticiaId(value: String): Int {
        return resolveCatalogId(
            value = value,
            table = "tipo_noticia",
            label = "tipo de noticia",
        )
    }

    private fun resolveCatalogId(value: String, table: String, label: String): Int {
        value.toIntOrNull()?.let { return it }
        val query = value.trim()
        require(query.isNotBlank()) { "La noticia necesita $label valida." }
        val encoded = query.toPostgrestValue()
        val resolved = runCatching {
            request(
                method = "GET",
                path = "/rest/v1/$table?select=id&or=(slug.eq.$encoded,nombre.eq.$encoded)&limit=1",
                body = null,
            ).decodeList<CatalogIdRow>().firstOrNull()?.id
        }.getOrNull()
        return resolved ?: throw IllegalArgumentException("No existe $label '$query'.")
    }

    companion object {
        fun fromEnvironment(json: Json): SupabaseNewsGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            val bucket = System.getenv("SUPABASE_BUCKET_NEWS_ASSETS").orEmpty().ifBlank { "news-assets" }
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseNewsGateway(url, serviceRoleKey, bucket, json)
        }
    }
}

@Serializable
private data class CatalogIdRow(
    val id: Int,
)

@Serializable
private data class SupabaseNoticiaRow(
    val id: Int? = null,
    val titulo: String,
    @SerialName("tipo_fondo")
    val fondo: String = "A",
    @SerialName("especialidad_id")
    val especialidadId: Int = 1,
    val texto: String,
    @SerialName("tipo_noticia_id")
    val tipoNoticiaId: Int = 1,
    @SerialName("imagen_path")
    val imagenPath: String? = null,
    val estado: String = "draft",
)

@Serializable
private data class StoredNewsText(
    val body: String = "",
    val capas: List<NoticiaLayerDto> = emptyList(),
)

private fun SupabaseNoticiaRow.toDto(json: Json): NoticiaDto {
    val stored = texto.decodeStoredNewsText(json)
    return NoticiaDto(
        id = id?.toString().orEmpty(),
        titulo = titulo,
        especialidad = especialidadId.toString(),
        tipo = tipoNoticiaId.toString(),
        fondo = fondo.toClientBackground(),
        fondoPath = imagenPath,
        estado = estado,
        publicarDesde = "",
        texto = stored.body,
        imagenPath = imagenPath,
        capas = stored.capas,
    )
}

private fun NoticiaDto.toSupabaseRow(
    json: Json,
    resolveEspecialidadId: (String) -> Int,
    resolveTipoNoticiaId: (String) -> Int,
): SupabaseNoticiaRow {
    return SupabaseNoticiaRow(
        id = id.toIntOrNull(),
        titulo = titulo,
        fondo = fondo.toDatabaseBackground(),
        especialidadId = resolveEspecialidadId(especialidad),
        texto = texto.encodeStoredNewsText(capas, json),
        tipoNoticiaId = resolveTipoNoticiaId(tipo),
        imagenPath = imagenPath ?: fondoPath,
        estado = estado.ifBlank { "draft" },
    )
}

private fun String.decodeStoredNewsText(json: Json): StoredNewsText {
    if (!startsWith(STORED_NEWS_TEXT_PREFIX)) return StoredNewsText(body = this)
    val payload = removePrefix(STORED_NEWS_TEXT_PREFIX)
    return runCatching { json.decodeFromString<StoredNewsText>(payload) }
        .getOrElse { StoredNewsText(body = this) }
}

private fun String.encodeStoredNewsText(capas: List<NoticiaLayerDto>, json: Json): String {
    if (capas.isEmpty()) return this
    val normalizedLayers = capas.sortedBy { it.orden }.mapIndexed { index, layer -> layer.copy(orden = index) }
    return STORED_NEWS_TEXT_PREFIX + json.encodeToString(StoredNewsText(body = this, capas = normalizedLayers))
}

private fun String.toDatabaseBackground(): String {
    return when (trim().uppercase()) {
        "SKY", "A" -> "A"
        "MINT", "B" -> "B"
        "GOLD", "C" -> "C"
        else -> "A"
    }
}

private fun String.toClientBackground(): String {
    return when (trim().uppercase()) {
        "A", "SKY" -> "SKY"
        "B", "MINT" -> "MINT"
        "C", "GOLD" -> "GOLD"
        else -> "SKY"
    }
}

private fun String.toPostgrestValue(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
}

private fun String.sanitizeStorageSegment(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "file" }
}

private fun String.toStorageBucketId(): String {
    require(matches(Regex("[A-Za-z0-9._-]+"))) { "Bucket de storage no valido." }
    return this
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
