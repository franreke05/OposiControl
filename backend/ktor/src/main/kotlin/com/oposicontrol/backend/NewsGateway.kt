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
    val titulo: String = "",
    val especialidad: String = "",
    val tipo: String = "",
    val fondo: String = "A",
    val fondoPath: String? = null,
    val estado: String = "draft",
    val publicarDesde: String = "",
    val texto: String = "",
    val imagenPath: String? = null,
    val capas: List<NoticiaLayerDto> = emptyList(),
)

@Serializable
data class NoticiaLayerDto(
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
    private val client: HttpClient = newUpstreamHttpClient(),
) : NewsGateway {
    @Volatile
    private var storageBucketChecked = false

    override fun listNoticias(): List<NoticiaDto> {
        val rows = request(
            method = "GET",
            // Ordenar por `id` dejó de tener sentido al ser `uuid`: el orden
            // sería arbitrario. La biblioteca necesita lo más reciente arriba.
            path = "/rest/v1/noticia?select=*&order=creado_en.desc",
            body = null,
        ).decodeList<SupabaseNoticiaRow>()
        return rows.map { row -> row.toDto() }
    }

    override fun saveNoticia(input: NoticiaDto): NoticiaDto {
        require(input.titulo.isNotBlank()) { "La noticia necesita titulo." }
        require(input.texto.isNotBlank() || input.capas.any { it.contenido.isNotBlank() }) { "La noticia necesita texto o capas." }
        val row = input.toSupabaseRow(
            resolveEspecialidadId = ::resolveEspecialidadId,
            resolveTipoNoticiaId = ::resolveTipoNoticiaId,
        )
        val saved = request(
            method = "POST",
            path = "/rest/v1/noticia?on_conflict=id",
            body = json.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<SupabaseNoticiaRow>().first()
        return saved.toDto()
    }

    override fun deleteNoticia(noticiaId: String) {
        // El id es `uuid`. Validarlo aquí evita mandar a PostgREST un filtro que
        // no puede comparar y que volvería como 400 sin explicación util.
        require(noticiaId.isCanonicalUuid()) { "Id de noticia no valido." }
        request(
            method = "DELETE",
            path = "/rest/v1/noticia?id=eq.$noticiaId",
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
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
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
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
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
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
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

    private fun resolveEspecialidadId(value: String): String {
        return resolveCatalogId(
            value = value,
            // La tabla es `especialidades`, en plural. Apuntaba a `especialidad`,
            // que no existe: la resolución fallaba en silencio (`runCatching`) y
            // salía por «No existe especialidad».
            table = "especialidades",
            label = "especialidad",
        )
    }

    private fun resolveTipoNoticiaId(value: String): String {
        return resolveCatalogId(
            value = value,
            table = "tipo_noticia",
            label = "tipo de noticia",
        )
    }

    /**
     * Acepta el `uuid` que ya envía el panel, o resuelve por `slug`/`nombre`
     * para quien trabaje con nombres legibles.
     *
     * Un uuid se da por bueno sin ir a la base: es el camino normal del panel y
     * ahorra una petición por guardado. Si no existe, la FK lo rechaza al
     * insertar, que es donde debe rechazarse.
     */
    private fun resolveCatalogId(value: String, table: String, label: String): String {
        val query = value.trim()
        require(query.isNotBlank()) { "La noticia necesita $label valida." }
        if (query.isCanonicalUuid()) return query
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
        /**
         * F-01/F-02: clave por [SupabaseRequestAuthPolicy.secretFromEnvironment] y
         * cabeceras por [supabaseElevatedAuth]. Leía sólo `SUPABASE_SERVICE_ROLE_KEY`
         * (con la configuración canónica el backend no arrancaba) y firmaba `Bearer`
         * sin mirar la forma de la clave.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): SupabaseNewsGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            val bucket = environment("SUPABASE_BUCKET_NEWS_ASSETS").orEmpty().ifBlank { "news-assets" }
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseNewsGateway(url, serviceRoleKey, bucket, json)
        }
    }
}

@Serializable
private data class CatalogIdRow(
    val id: String,
)

/**
 * Un `uuid` canónico, no cualquier texto con guiones. Sin esta comprobación,
 * `"sin-seleccionar"` se tomaría por identificador y llegaría a la FK como un
 * error de base de datos en vez de como un mensaje que el panel pueda mostrar.
 */
internal fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString().equals(this, ignoreCase = true) }.getOrDefault(false)

/**
 * Fila de `public.noticia`.
 *
 * Los identificadores son `uuid`, no enteros. Se declaraban `Int` contra una
 * base cuyas claves primarias son `uuid` desde siempre: `especialidad_id` no
 * podía resolver nunca, y por eso guardar una noticia fallaba aunque el resto
 * de la vertical estuviera completa.
 *
 * `capas` es una columna `jsonb` propia. Antes la composición se serializaba
 * DENTRO de `texto` con un prefijo, lo que hacía que una misma columna
 * significara dos cosas y que la composición no se pudiera consultar.
 */
@Serializable
internal data class SupabaseNoticiaRow(
    // Nulo en alta: PostgREST omite el campo (`explicitNulls = false`) y Postgres
    // aplica `gen_random_uuid()`. En edición viaja y `on_conflict=id` fusiona.
    val id: String? = null,
    val titulo: String,
    @SerialName("tipo_fondo")
    val fondo: String = "A",
    @SerialName("especialidad_id")
    val especialidadId: String,
    val texto: String,
    val capas: List<NoticiaLayerDto> = emptyList(),
    @SerialName("tipo_noticia_id")
    val tipoNoticiaId: String,
    @SerialName("imagen_path")
    val imagenPath: String? = null,
    @SerialName("background_path")
    val backgroundPath: String? = null,
    val estado: String = "draft",
    @SerialName("publicar_desde")
    val publicarDesde: String? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
)

internal fun SupabaseNoticiaRow.toDto(): NoticiaDto {
    return NoticiaDto(
        id = id.orEmpty(),
        titulo = titulo,
        especialidad = especialidadId,
        tipo = tipoNoticiaId,
        fondo = fondo.toClientBackground(),
        // ponytail: filas antiguas guardaban el fondo en imagen_path; retirar
        // cuando no queden filas creadas antes de `background_path`.
        fondoPath = backgroundPath ?: imagenPath,
        estado = estado,
        publicarDesde = publicarDesde.orEmpty(),
        texto = texto,
        imagenPath = imagenPath,
        capas = capas.sortedBy { it.orden },
    )
}

internal fun NoticiaDto.toSupabaseRow(
    resolveEspecialidadId: (String) -> String,
    resolveTipoNoticiaId: (String) -> String,
): SupabaseNoticiaRow {
    // El orden se normaliza al guardar: el cliente puede haber reordenado capas
    // dejando huecos (0, 2, 5) y el lienzo pinta por índice, no por distancia.
    val normalizedLayers = capas.sortedBy { it.orden }.mapIndexed { index, layer -> layer.copy(orden = index) }
    return SupabaseNoticiaRow(
        id = id.takeIf { it.isNotBlank() },
        titulo = titulo,
        fondo = fondo.toDatabaseBackground(),
        especialidadId = resolveEspecialidadId(especialidad),
        texto = texto,
        capas = normalizedLayers,
        tipoNoticiaId = resolveTipoNoticiaId(tipo),
        imagenPath = imagenPath,
        backgroundPath = fondoPath,
        estado = estado.ifBlank { "draft" },
        publicarDesde = publicarDesde.takeIf { it.isNotBlank() },
    )
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
