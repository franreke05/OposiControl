package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val STORED_NEWS_TEXT_PREFIX = "__OPOSICONTROL_NEWS_V1__\n"

interface NewsGateway {
    /**
     * [limit] acota la lectura. La biblioteca pedía la tabla entera en cada
     * refresco, lo que funciona con doce noticias y deja de funcionar solo:
     * el coste crece para siempre y nadie se entera hasta que duele.
     */
    fun listNoticias(limit: Int = DEFAULT_NEWS_PAGE_SIZE, offset: Int = 0): List<NoticiaDto>

    /** Releer UNA noticia sin traerse la biblioteca completa detrás. */
    fun findNoticia(noticiaId: String): NoticiaDto?

    fun saveNoticia(input: NoticiaDto): NoticiaDto
    fun deleteNoticia(noticiaId: String)
    fun uploadAsset(file: NewsUploadedFile): NewsAssetUploadDto

    // ------------------------------------------------------------------
    // Superficie canonica (`openapi/news/news.yaml`, D4-01..D4-11). Las ejecuta el servicio compartido [NewsService]; los
    // dobles de prueba del CRUD legacy (InMemoryNewsGateway) no las implementan.
    // ------------------------------------------------------------------

    /** Igual que [uploadAsset] pero con el tamano REAL del objeto subido (tras comprimir). */
    fun uploadAssetDetailed(file: NewsUploadedFile): NewsStoredAsset = unsupportedCanonical()

    /** URL publica de un objeto del bucket de noticias. */
    fun publicAssetUrl(path: String): String = unsupportedCanonical()

    /** Filas RESUMEN, orden `(actualizado_en desc, id desc)`; [after] = claves de la ultima fila vista. */
    fun listNoticiaSummaries(filter: NewsListFilter, after: List<JsonElement>?, fetchLimit: Int): List<NoticiaRecord> = unsupportedCanonical()

    fun getNoticiaRecord(id: String): NoticiaRecord? = unsupportedCanonical()
    fun especialidadIsActive(id: String): Boolean = unsupportedCanonical()
    fun tipoNoticiaIsActive(id: String): Boolean = unsupportedCanonical()
    fun insertNoticia(fields: JsonObject): NoticiaRecord = unsupportedCanonical()

    /** UPDATE condicionado a `actualizado_en = version`. `null` = ninguna fila coincide (no existe o la version cambio). */
    fun updateNoticiaIfVersion(id: String, version: String, fields: JsonObject): NoticiaRecord? = unsupportedCanonical()

    /** DELETE devolviendo la fila borrada; `null` si no existia. */
    fun deleteNoticiaReturning(id: String): NoticiaRecord? = unsupportedCanonical()

    fun isAssetReferenced(path: String): Boolean = unsupportedCanonical()
    fun removeAssets(paths: Collection<String>): Unit = unsupportedCanonical()
    fun listTiposNoticia(includeInactive: Boolean, limit: Int, offset: Int): Pair<List<TipoNoticiaRecord>, Int?> = unsupportedCanonical()
    fun findTipoByCodigo(codigo: String): TipoNoticiaRecord? = unsupportedCanonical()
    fun findTipoBySlug(slug: String): TipoNoticiaRecord? = unsupportedCanonical()
    fun upsertTipoNoticia(fields: JsonObject): TipoNoticiaRecord = unsupportedCanonical()
}

private fun <T> unsupportedCanonical(): T = throw UnsupportedOperationException("Operacion canonica de noticias no disponible en este gateway.")

/** Bucket publico canonico de las imagenes de noticias. */
const val NEWS_ASSETS_BUCKET = "news-assets"

/** `Instant.toString()` de un `timestamptz`: lo unico que se interpola en el filtro de version (nada del cliente). */
internal val NEWS_VERSION_SHAPE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$")

/** Filtros ya VALIDADOS del listado D4-01. */
data class NewsListFilter(
    val estado: String? = null,
    val especialidadId: String? = null,
    val tipoNoticiaId: String? = null,
    val q: String? = null,
)

/** Un objeto subido a `news-assets`: ruta, URL publica, MIME y tamano finales. */
data class NewsStoredAsset(val path: String, val publicUrl: String, val mime: String, val bytes: Int)

/**
 * Fila de `public.noticia` para la superficie canonica. `capas` es opaco (jsonb array) y se guarda tal cual. Los campos con
 * valor por defecto son los que el listado RESUMEN no selecciona.
 */
@Serializable
data class NoticiaRecord(
    val id: String,
    val titulo: String,
    val texto: String? = null,
    val capas: JsonArray = JsonArray(emptyList()),
    @SerialName("tipo_fondo") val tipoFondo: String = "A",
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("tipo_noticia_id") val tipoNoticiaId: String,
    @SerialName("background_path") val backgroundPath: String? = null,
    @SerialName("imagen_path") val imagenPath: String? = null,
    val estado: String,
    @SerialName("publicar_desde") val publicarDesde: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String,
)

/** Fila de `public.tipo_noticia`. */
@Serializable
data class TipoNoticiaRecord(
    val id: String,
    val codigo: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val activo: Boolean = true,
    val posicion: Int = 1,
)

/** Suficiente para la biblioteca real y un techo honesto frente al crecimiento. */
const val DEFAULT_NEWS_PAGE_SIZE = 200

/** Ni cero ni negativo ni ilimitado: un `limit=0` devolvería una lista vacía sin decir por qué. */
internal fun sanitizeNewsLimit(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.coerceIn(1, 500) ?: DEFAULT_NEWS_PAGE_SIZE

internal fun sanitizeNewsOffset(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

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
    /**
     * Tipografia de la capa. Campos nuevos CON default: `capas` es `jsonb`, las
     * filas ya guardadas no traen estas claves y deserializan a `0`/`"normal"`
     * sin tocar la base ni migrar nada.
     *
     * `fontSizeSp = 0` significa «derivado del tipo de capa», no «tamano cero»:
     * es como se pinta hoy el lienzo y como debe seguir pintandose lo ya
     * guardado. Ademas, el `Json` del backend no serializa defaults
     * (`encodeDefaults = false`), asi que una capa derivada no engorda el
     * `jsonb` con dos claves que no dicen nada.
     */
    val fontSizeSp: Int = 0,
    val fontWeight: String = "",
)

class InMemoryNewsGateway : NewsGateway {
    private val noticias = linkedMapOf<String, NoticiaDto>()

    override fun listNoticias(limit: Int, offset: Int): List<NoticiaDto> =
        noticias.values.toList().drop(offset).take(limit)

    override fun findNoticia(noticiaId: String): NoticiaDto? = noticias[noticiaId]

    override fun saveNoticia(input: NoticiaDto): NoticiaDto {
        // Las reglas (titulo, texto o capas, estado) viven en [NewsService], comunes a todo gateway.
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
    private val transport: SupabaseRequestTransport,
    private val newsAssetsBucket: String,
    private val json: Json,
) : NewsGateway {

    override fun listNoticias(limit: Int, offset: Int): List<NoticiaDto> {
        val rows = request(
            method = "GET",
            // Ordenar por `id` dejó de tener sentido al ser `uuid`: el orden
            // sería arbitrario. La biblioteca necesita lo más reciente arriba.
            path = "/rest/v1/noticia?select=*&order=creado_en.desc&limit=$limit&offset=$offset",
            body = null,
        ).let { decodeNoticiaRowsTolerant(json, it) }
        return rows.map { row -> row.toDto() }
    }

    override fun findNoticia(noticiaId: String): NoticiaDto? {
        require(noticiaId.isCanonicalUuid()) { "Id de noticia no valido." }
        return request(
            method = "GET",
            path = "/rest/v1/noticia?id=eq.$noticiaId&select=*&limit=1",
            body = null,
        ).let { decodeNoticiaRowsTolerant(json, it) }.firstOrNull()?.toDto()
    }

    override fun saveNoticia(input: NoticiaDto): NoticiaDto {
        val row = input.toSupabaseRow(
            resolveEspecialidadId = ::resolveEspecialidadId,
            resolveTipoNoticiaId = ::resolveTipoNoticiaId,
        )
        val saved = request(
            method = "POST",
            path = "/rest/v1/noticia?on_conflict=id",
            body = json.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).let { decodeNoticiaRowsTolerant(json, it) }.first()
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

    /**
     * El asset se COMPRIME antes de cruzar la red hacia el bucket.
     *
     * La validación de que es una imagen va primero y no cambia: comprimir no
     * puede convertir en admisible algo que no lo era. Lo que sí cambia es que
     * el objeto puede acabar siendo un JPEG aunque llegara como PNG, y por eso
     * la extensión del objeto se deriva del MIME **resultante** y no del nombre
     * que mandó el cliente — un `fondo.png` que contiene un JPEG se descarga
     * roto en la mitad de los visores.
     *
     * Si la compresión no sirve de nada (falla, no la sabemos hacer, o el
     * resultado pesa más), [MediaCompression.compress] devuelve los bytes y el
     * MIME originales y aquí no se nota: se sube exactamente lo que llegó.
     */
    override fun uploadAsset(file: NewsUploadedFile): NewsAssetUploadDto =
        uploadAssetDetailed(file).let { NewsAssetUploadDto(path = it.path, publicUrl = it.publicUrl, mimeType = it.mime) }

    override fun uploadAssetDetailed(file: NewsUploadedFile): NewsStoredAsset {
        require(file.mimeType.startsWith("image/")) { "El asset de noticia debe ser una imagen." }
        val optimized = MediaCompression.compress(
            bytes = file.bytes,
            mimeType = file.mimeType,
            // La ruta del objeto se construye aquí mismo: este punto de subida
            // SÍ puede garantizar que extensión y MIME acaben de acuerdo.
            allowFormatChange = true,
        )
        val objectName = file.fileName.sanitizeStorageSegment().withExtensionFor(optimized.mimeType)
        val objectPath = "news/${UUID.randomUUID()}/$objectName"
        transport.storageEnsureBucket(newsAssetsBucket, public = true)
        transport.storagePut(newsAssetsBucket, objectPath, optimized.bytes, optimized.mimeType, upsert = true)
        return NewsStoredAsset(
            path = objectPath,
            publicUrl = transport.storagePublicUrl(newsAssetsBucket, objectPath),
            mime = optimized.mimeType,
            bytes = optimized.bytes.size,
        )
    }

    // ------------------------------------------------------------------
    // Canonico (D4-01..D4-11): mismas tablas y mismo bucket, lecturas y escrituras condicionadas.
    // ------------------------------------------------------------------

    override fun publicAssetUrl(path: String): String = transport.storagePublicUrl(newsAssetsBucket, path)

    override fun listNoticiaSummaries(filter: NewsListFilter, after: List<JsonElement>?, fetchLimit: Int): List<NoticiaRecord> {
        val query = StringBuilder(
            "select=id,titulo,estado,especialidad_id,tipo_noticia_id,publicar_desde,published_at,imagen_path,actualizado_en" +
                "&order=actualizado_en.desc,id.desc&limit=$fetchLimit",
        )
        // Todo valor que llega aqui ya esta validado (enum, uuid) o escapado (q) por el servicio.
        filter.estado?.let { query.append("&estado=eq.$it") }
        filter.especialidadId?.let { query.append("&especialidad_id=eq.$it") }
        filter.tipoNoticiaId?.let { query.append("&tipo_noticia_id=eq.$it") }
        filter.q?.takeIf { it.isNotEmpty() }?.let { query.append("&titulo=ilike.*${it.likeEscaped().urlEncode()}*") }
        after?.let { keys ->
            val at = (keys[0] as JsonPrimitive).content
            val id = (keys[1] as JsonPrimitive).content
            query.append("&or=(actualizado_en.lt.${at.urlEncode()},and(actualizado_en.eq.${at.urlEncode()},id.lt.${id.urlEncode()}))")
        }
        return request("GET", "/rest/v1/noticia?$query", null).decodeList<NoticiaRecord>()
    }

    override fun getNoticiaRecord(id: String): NoticiaRecord? {
        require(id.isCanonicalUuid()) { "Id de noticia no valido." }
        return request("GET", "/rest/v1/noticia?id=eq.$id&select=*&limit=1", null).decodeList<NoticiaRecord>().firstOrNull()
    }

    override fun especialidadIsActive(id: String): Boolean {
        require(id.isCanonicalUuid()) { "Id de especialidad no valido." }
        return request("GET", "/rest/v1/especialidades?select=id&id=eq.$id&activa=eq.true&limit=1", null).decodeList<CatalogIdRow>().isNotEmpty()
    }

    override fun tipoNoticiaIsActive(id: String): Boolean {
        require(id.isCanonicalUuid()) { "Id de tipo de noticia no valido." }
        return request("GET", "/rest/v1/tipo_noticia?select=id&id=eq.$id&activo=eq.true&limit=1", null).decodeList<CatalogIdRow>().isNotEmpty()
    }

    override fun insertNoticia(fields: JsonObject): NoticiaRecord =
        request("POST", "/rest/v1/noticia", JsonArray(listOf(fields)).toString(), prefer = "return=representation")
            .decodeList<NoticiaRecord>().first()

    override fun updateNoticiaIfVersion(id: String, version: String, fields: JsonObject): NoticiaRecord? {
        require(id.isCanonicalUuid()) { "Id de noticia no valido." }
        require(NEWS_VERSION_SHAPE.matches(version)) { "Version de noticia no valida." }
        return request(
            "PATCH",
            "/rest/v1/noticia?id=eq.$id&actualizado_en=eq.$version",
            fields.toString(),
            prefer = "return=representation",
        ).decodeList<NoticiaRecord>().firstOrNull()
    }

    override fun deleteNoticiaReturning(id: String): NoticiaRecord? {
        require(id.isCanonicalUuid()) { "Id de noticia no valido." }
        return request("DELETE", "/rest/v1/noticia?id=eq.$id", null, prefer = "return=representation")
            .decodeList<NoticiaRecord>().firstOrNull()
    }

    override fun isAssetReferenced(path: String): Boolean {
        val encoded = path.urlEncode()
        return request(
            "GET",
            "/rest/v1/noticia?select=id&or=(background_path.eq.$encoded,imagen_path.eq.$encoded)&limit=1",
            null,
        ).decodeList<CatalogIdRow>().isNotEmpty()
    }

    override fun removeAssets(paths: Collection<String>) {
        paths.forEach { transport.storageDelete(newsAssetsBucket, it) }
    }

    override fun listTiposNoticia(includeInactive: Boolean, limit: Int, offset: Int): Pair<List<TipoNoticiaRecord>, Int?> {
        val path = "/rest/v1/tipo_noticia?select=id,codigo,nombre,slug,descripcion,activo,posicion" +
            "${if (includeInactive) "" else "&activo=eq.true"}&order=posicion.asc,codigo.asc&limit=$limit&offset=$offset"
        // El total es informativo (`null` si no se puede contar): una pagina sin total es mejor que ningun listado.
        return try {
            val page = transport.getPage(path)
            page.body.decodeList<TipoNoticiaRecord>() to page.total
        } catch (_: IllegalStateException) {
            request("GET", path, null).decodeList<TipoNoticiaRecord>() to null
        }
    }

    override fun findTipoByCodigo(codigo: String): TipoNoticiaRecord? =
        request("GET", "/rest/v1/tipo_noticia?select=id,codigo,nombre,slug,descripcion,activo,posicion&codigo=eq.${codigo.urlEncode()}&limit=1", null)
            .decodeList<TipoNoticiaRecord>().firstOrNull()

    override fun findTipoBySlug(slug: String): TipoNoticiaRecord? =
        request("GET", "/rest/v1/tipo_noticia?select=id,codigo,nombre,slug,descripcion,activo,posicion&slug=eq.${slug.urlEncode()}&limit=1", null)
            .decodeList<TipoNoticiaRecord>().firstOrNull()

    override fun upsertTipoNoticia(fields: JsonObject): TipoNoticiaRecord =
        request(
            "POST",
            "/rest/v1/tipo_noticia?on_conflict=codigo",
            JsonArray(listOf(fields)).toString(),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<TipoNoticiaRecord>().first()

    private fun request(
        method: String,
        path: String,
        body: String?,
        prefer: String? = null,
    ): String = transport.send(method = method, path = path, body = body, prefer = prefer)

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
         * F-01/F-02: clave por [SupabaseRequestAuthPolicy.secretFromEnvironment]. Todo el trafico (PostgREST y
         * Storage) pasa por [SupabaseRequestTransport], el unico punto que firma las peticiones de servicio.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): SupabaseNewsGateway? {
            val transport = SupabaseRequestTransport.fromEnvironment(environment) ?: return null
            val bucket = environment("SUPABASE_BUCKET_NEWS_ASSETS").orEmpty().ifBlank { NEWS_ASSETS_BUCKET }
            return SupabaseNewsGateway(transport, bucket.toStorageBucketId(), json)
        }

        /** Solo para tests: transporte inyectado para observar lo que sale hacia el upstream. */
        internal fun forTesting(transport: SupabaseRequestTransport, json: Json, bucket: String = NEWS_ASSETS_BUCKET): SupabaseNewsGateway =
            SupabaseNewsGateway(transport, bucket.toStorageBucketId(), json)
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
/**
 * Lectura legacy tolerante: las rutas canonicas guardan `capas` como objetos opacos, y el lector legacy las tipa
 * (`NoticiaLayerDto`). Una fila cuyas capas no encajan NO tumba la pagina entera: esa fila se lee sin capas
 * (el resto de sus campos intacto). Cualquier otro fallo de forma sigue propagandose.
 */
internal fun decodeNoticiaRowsTolerant(json: Json, text: String): List<SupabaseNoticiaRow> =
    json.parseToJsonElement(text).jsonArray.map { element ->
        runCatching { json.decodeFromJsonElement<SupabaseNoticiaRow>(element) }.getOrElse {
            json.decodeFromJsonElement<SupabaseNoticiaRow>(JsonObject(element.jsonObject + ("capas" to JsonArray(emptyList()))))
        }
    }

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

/**
 * Ajusta la extensión al MIME real de los bytes que se van a subir.
 *
 * Sólo toca los formatos que sabe nombrar: para cualquier otro MIME devuelve el
 * nombre tal cual, porque inventar una extensión es peor que no tocarla.
 */
internal fun String.withExtensionFor(mimeType: String): String {
    val extension = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> return this
    }
    val base = substringBeforeLast('.', this).ifBlank { "file" }
    return "$base.$extension"
}

private fun String.sanitizeStorageSegment(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "file" }
}

/** `%`, `_` y la barra invertida literales en un ILIKE (el escape por defecto de Postgres es la barra invertida). */
private fun String.likeEscaped(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun String.toStorageBucketId(): String {
    require(matches(Regex("[A-Za-z0-9._-]+"))) { "Bucket de storage no valido." }
    return this
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
