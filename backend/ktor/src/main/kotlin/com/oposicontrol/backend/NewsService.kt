package com.oposicontrol.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// =====================================================================
// SERVICIO COMPARTIDO DE NOTICIAS (FA11, `openapi/news/news.yaml`, D4-01..D4-11).
//
// UNA sola implementacion de las reglas de negocio de `public.noticia` / `public.tipo_noticia`: maquina de estados
// (draft -> scheduled -> published -> archived), `scheduled` exige `publicar_desde`, una noticia necesita contenido para
// publicarse, referencias activas, rutas de asset conocidas y la subida de imagenes. Lo usan las rutas canonicas
// (`/api/v1/admin/noticias*`, `/api/v1/admin/tipos-noticia*`) Y las rutas legacy (`/content/noticias*`), que son solo
// adaptadores de cable. El almacenamiento es [NewsGateway] (PostgREST + Storage `news-assets`); ningun otro.
// =====================================================================

private val NewsLogger = LoggerFactory.getLogger("OC-KTOR-NEWS")

/** Destino de la auditoria de escrituras (el actor lo fija [AdminAuditActor], desde el `sub` del JWT; nunca la peticion). */
fun interface NewsAuditSink {
    fun record(accion: String, tabla: String, registroId: String?, antes: JsonElement?, despues: JsonElement?)

    companion object {
        val None = NewsAuditSink { _, _, _, _, _ -> }
    }
}

// ---------- cable canonico (respuestas) ----------

@Serializable
internal data class NoticiaResumenWire(
    val id: String,
    val titulo: String,
    val estado: String,
    val especialidadId: String,
    val tipoNoticiaId: String,
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val imagenUrl: String? = null,
    val actualizadoEn: String,
)

@Serializable
internal data class NoticiaAdminWire(
    val id: String,
    val titulo: String,
    val texto: String? = null,
    val capas: JsonArray,
    val tipoFondo: String,
    val especialidadId: String,
    val tipoNoticiaId: String,
    val backgroundPath: String? = null,
    val imagenPath: String? = null,
    val backgroundUrl: String? = null,
    val imagenUrl: String? = null,
    val estado: String,
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val version: String,
)

@Serializable
internal data class NoticiaAssetWire(val path: String, val publicUrl: String, val mime: String, val bytes: Int)

@Serializable
internal data class TipoNoticiaWire(
    val id: String,
    val codigo: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val activo: Boolean,
    val posicion: Int,
)

// ---------- peticiones ya validadas ----------

internal data class NoticiaCreate(
    val titulo: String,
    val texto: String?,
    val capas: JsonArray,
    val tipoFondo: String,
    val especialidadId: String,
    val tipoNoticiaId: String,
    val backgroundPath: String?,
    val imagenPath: String?,
)

internal data class TipoNoticiaPut(val nombre: String, val slug: String, val descripcion: String?, val activo: Boolean, val posicion: Int)

internal data class NewsListQuery(val filter: NewsListFilter, val page: CursorPageRequest)

internal object NewsRules {
    val ESTADOS = setOf("draft", "scheduled", "published", "archived")
    val EDITABLE = setOf("draft", "scheduled")
    val FONDOS = setOf("A", "B", "C")
    const val Q_MAX = 100

    /** `news/<uuid>/<nombre>` (lo que sube [NewsGateway.uploadAsset]) o `news/<uuid>.<ext>` (forma del contrato). */
    val ASSET_PATH = Regex("^news/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(/[A-Za-z0-9._-]{1,200}|\\.[A-Za-z0-9]{1,8})$")

    /** Una noticia sin texto ni capas con contenido no tiene nada que publicar (misma regla que el guardado legacy). */
    fun hasContent(texto: String?, capaContents: Sequence<String?>): Boolean =
        !texto.isNullOrBlank() || capaContents.any { !it.isNullOrBlank() }
}

internal class NewsService(
    private val gateway: NewsGateway,
    private val audit: NewsAuditSink = NewsAuditSink.None,
    private val onIndexed: (NoticiaDto) -> Unit = {},
    private val onRemoved: (String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
) {

    // ==================================================================
    // LEGACY (`/content/noticias*`): mismas reglas, cable legacy (NoticiaDto)
    // ==================================================================

    fun listLegacy(limit: Int, offset: Int): List<NoticiaDto> = gateway.listNoticias(limit = limit, offset = offset)

    fun findLegacy(noticiaId: String): NoticiaDto? = gateway.findNoticia(noticiaId)

    fun saveLegacy(input: NoticiaDto): NoticiaDto {
        require(input.titulo.isNotBlank()) { "La noticia necesita titulo." }
        require(NewsRules.hasContent(input.texto, input.capas.asSequence().map { it.contenido })) { "La noticia necesita texto o capas." }
        val estado = input.estado.ifBlank { "draft" }
        require(estado in NewsRules.ESTADOS) { "Estado de noticia no valido." }
        require(estado != "scheduled" || input.publicarDesde.isNotBlank()) { "Una noticia programada necesita fecha de publicacion." }
        val saved = gateway.saveNoticia(input)
        record("noticia_guardada_legacy", "noticia", saved.id, despues = buildJsonObject { put("estado", saved.estado) })
        onIndexed(saved)
        return saved
    }

    fun deleteLegacy(noticiaId: String) {
        gateway.deleteNoticia(noticiaId)
        record("noticia_eliminada_legacy", "noticia", noticiaId, null, null)
        onRemoved(noticiaId)
    }

    fun uploadAssetLegacy(file: NewsUploadedFile): NewsAssetUploadDto = gateway.uploadAsset(file)

    // ==================================================================
    // CANONICO
    // ==================================================================

    /** D4-01 */
    fun list(query: NewsListQuery): CursorPage<NoticiaResumenWire> {
        val rows = gateway.listNoticiaSummaries(query.filter, query.page.after, query.page.fetchLimit)
        val page = query.page.page(rows) { listOf(JsonPrimitive(it.version()), JsonPrimitive(it.id)) }
        return CursorPage(page.items.map { it.toResumen() }, page.nextCursor)
    }

    /** D4-02 */
    fun get(id: String): NoticiaAdminWire = load(id).toWire()

    /** D4-03 */
    fun create(cmd: NoticiaCreate): NoticiaAdminWire {
        requireKnownAssets(cmd.backgroundPath, cmd.imagenPath)
        requireActiveReferences(cmd.especialidadId, cmd.tipoNoticiaId)
        val fields = buildJsonObject {
            put("titulo", cmd.titulo)
            put("texto", cmd.texto ?: "")
            put("capas", cmd.capas)
            put("tipo_fondo", cmd.tipoFondo)
            put("especialidad_id", cmd.especialidadId)
            put("tipo_noticia_id", cmd.tipoNoticiaId)
            cmd.backgroundPath?.let { put("background_path", it) }
            cmd.imagenPath?.let { put("imagen_path", it) }
            put("estado", "draft")
        }
        val created = gateway.insertNoticia(fields)
        record("noticia_creada", "noticia", created.id, despues = buildJsonObject { put("estado", created.estado) })
        indexBestEffort(created)
        return created.toWire()
    }

    /** D4-04. [fields] ya viene con nombres de columna y valores validados ([NewsCommands.parsePatch]). */
    fun patch(id: String, version: String, fields: JsonObject): NoticiaAdminWire {
        val current = load(id)
        if (current.estado !in NewsRules.EDITABLE) throw CanonicalErrorCode.NEWS_NOT_EDITABLE.exception()
        if (current.version() != version) throw CanonicalErrorCode.PRECONDITION_FAILED.exception()
        if (fields.isEmpty()) return current.toWire()
        fields["background_path"].asNewPath(current.backgroundPath)?.let { requireKnownAssets(it) }
        fields["imagen_path"].asNewPath(current.imagenPath)?.let { requireKnownAssets(it) }
        val newEspecialidad = fields.text("especialidad_id")?.takeIf { it != current.especialidadId }
        val newTipo = fields.text("tipo_noticia_id")?.takeIf { it != current.tipoNoticiaId }
        requireActiveReferences(newEspecialidad, newTipo)
        val updated = gateway.updateNoticiaIfVersion(id, version, fields) ?: run {
            // Ninguna fila coincide: o se borro, o alguien la cambio entre la lectura y la escritura.
            val now = gateway.getNoticiaRecord(id) ?: throw CanonicalErrorCode.NEWS_NOT_FOUND.exception()
            if (now.estado !in NewsRules.EDITABLE) throw CanonicalErrorCode.NEWS_NOT_EDITABLE.exception()
            throw CanonicalErrorCode.PRECONDITION_FAILED.exception()
        }
        record(
            "noticia_actualizada", "noticia", id,
            despues = buildJsonObject { put("campos", JsonArray(fields.keys.sorted().map { JsonPrimitive(it) })) },
        )
        indexBestEffort(updated)
        return updated.toWire()
    }

    /** D4-08 */
    fun delete(id: String) {
        val current = load(id)
        val deleted = gateway.deleteNoticiaReturning(id) ?: throw CanonicalErrorCode.NEWS_NOT_FOUND.exception()
        record(
            "noticia_eliminada", "noticia", id,
            antes = buildJsonObject {
                put("titulo", current.titulo)
                put("estado", current.estado)
            },
            despues = null,
        )
        runCatching { onRemoved(id) }.onFailure { NewsLogger.warn("news search index removal failed: {}", it.redactedMessage("index")) }
        // Despues del commit y a mejor esfuerzo: un objeto que ninguna noticia referencia es un huerfano, nunca un error.
        listOfNotNull(deleted.backgroundPath, deleted.imagenPath).distinct().filter { NewsRules.ASSET_PATH.matches(it) }.forEach { path ->
            try {
                if (!gateway.isAssetReferenced(path)) gateway.removeAssets(listOf(path))
            } catch (failure: Exception) {
                NewsLogger.warn("news asset left orphaned after delete: {}", failure.redactedMessage("asset cleanup"))
            }
        }
    }

    /** D4-05 */
    fun publish(id: String): NoticiaAdminWire {
        val now = clock().truncatedTo(ChronoUnit.MICROS)
        return transition(id, "noticia_publicada") { current ->
            when (current.estado) {
                "archived" -> throw CanonicalErrorCode.INVALID_TRANSITION.exception()
                "published" ->
                    // Idempotente por estado. Una publicada SIN sello (filas anteriores al trigger) lo recibe una vez.
                    if (current.publishedAt != null) null else buildJsonObject { put("published_at", now.toString()) }
                else -> {
                    val capaContents = current.capas.asSequence().map { ((it as? JsonObject)?.get("contenido") as? JsonPrimitive)?.contentOrNull }
                    if (!NewsRules.hasContent(current.texto, capaContents)) throw CanonicalErrorCode.NEWS_INCOMPLETE.exception()
                    val scheduledInFuture = current.publicarDesde?.let { parseInstant(it)?.isAfter(now) } == true
                    buildJsonObject {
                        put("estado", "published")
                        // `published_at` se fija UNA vez (reloj del servidor) y se envia explicitamente: es correcto con o sin el
                        // trigger `noticia_sellar_published_at`, que solo rellena un sello vacio.
                        put("published_at", current.publishedAt ?: now.toString())
                        // Publicar es "ahora": una fecha futura de una programacion previa haria la noticia invisible (RLS).
                        if (scheduledInFuture) put("publicar_desde", JsonNull)
                    }
                }
            }
        }
    }

    /** D4-06 */
    fun schedule(id: String, at: Instant): NoticiaAdminWire {
        val now = clock()
        if (!at.isAfter(now)) throw CanonicalErrorCode.NOT_FUTURE_INSTANT.exception()
        val wanted = at.truncatedTo(ChronoUnit.MICROS)
        return transition(id, "noticia_programada") { current ->
            when (current.estado) {
                "published", "archived" -> throw CanonicalErrorCode.INVALID_TRANSITION.exception()
                "scheduled" ->
                    if (current.publicarDesde?.let(::parseInstant) == wanted) null else buildJsonObject { put("publicar_desde", wanted.toString()) }
                else -> buildJsonObject {
                    put("estado", "scheduled")
                    put("publicar_desde", wanted.toString())
                }
            }
        }
    }

    /** D4-07 */
    fun archive(id: String): NoticiaAdminWire =
        transition(id, "noticia_archivada") { current ->
            if (current.estado == "archived") null else buildJsonObject { put("estado", "archived") }
        }

    /**
     * Comando de estado: lee, decide ([decide] devuelve los campos a escribir o `null` = sin cambios), y escribe CONDICIONADO a
     * la version leida. Si otro escritor se adelanta se relee y se decide de nuevo (acotado): el resultado siempre es una
     * transicion valida desde un estado real, nunca un pisotón.
     */
    private fun transition(id: String, accion: String, decide: (NoticiaRecord) -> JsonObject?): NoticiaAdminWire {
        repeat(TRANSITION_ATTEMPTS) {
            val current = load(id)
            val fields = decide(current) ?: return current.toWire()
            val written = gateway.updateNoticiaIfVersion(id, current.version(), fields)
            if (written != null) {
                record(
                    accion, "noticia", id,
                    antes = buildJsonObject { put("estado", current.estado) },
                    despues = buildJsonObject { put("estado", written.estado) },
                )
                indexBestEffort(written)
                return written.toWire()
            }
        }
        throw CanonicalErrorCode.CONFLICT.exception()
    }

    /** D4-09 (la validacion de tamano y de tipo ya ocurrio en la ruta; aqui se sube con la subida existente). */
    fun uploadAsset(file: NewsUploadedFile): NoticiaAssetWire {
        val stored = gateway.uploadAssetDetailed(file)
        record("noticia_asset_subido", "noticia", null, despues = buildJsonObject {
            put("path", stored.path)
            put("mime", stored.mime)
            put("bytes", stored.bytes)
        })
        return NoticiaAssetWire(stored.path, stored.publicUrl, stored.mime, stored.bytes)
    }

    /** D4-10 */
    fun listTypes(includeInactive: Boolean, limit: Int, offset: Int): OffsetPage<TipoNoticiaWire> {
        val (rows, total) = gateway.listTiposNoticia(includeInactive, limit, offset)
        return OffsetPage(items = rows.map { it.toWire() }, total = total, limit = limit, offset = offset)
    }

    /** D4-11. `true` = creado (201), `false` = actualizado (200). */
    fun upsertType(codigo: String, cmd: TipoNoticiaPut): Pair<TipoNoticiaWire, Boolean> {
        val existing = gateway.findTipoByCodigo(codigo)
        gateway.findTipoBySlug(cmd.slug)?.takeIf { it.codigo != codigo }?.let { throw CanonicalErrorCode.SLUG_CONFLICT.exception() }
        val fields = buildJsonObject {
            put("codigo", codigo)
            put("nombre", cmd.nombre)
            put("slug", cmd.slug)
            put("descripcion", cmd.descripcion?.let { JsonPrimitive(it) } ?: JsonNull)
            put("activo", cmd.activo)
            put("posicion", cmd.posicion)
        }
        val saved = try {
            gateway.upsertTipoNoticia(fields)
        } catch (conflict: BackendDomainException) {
            // El unico UNIQUE que puede saltar aqui es el del slug (el de `codigo` lo resuelve ON CONFLICT).
            if (conflict.errorCode == "UNIQUE_VIOLATION") throw CanonicalErrorCode.SLUG_CONFLICT.exception()
            throw conflict
        }
        record(
            "tipo_noticia_guardado", "tipo_noticia", saved.id,
            antes = existing?.let { buildJsonObject { put("activo", it.activo); put("slug", it.slug) } },
            despues = buildJsonObject { put("codigo", saved.codigo); put("activo", saved.activo); put("slug", saved.slug) },
        )
        return saved.toWire() to (existing == null)
    }

    // ------------------------------------------------------------------

    private fun load(id: String): NoticiaRecord =
        gateway.getNoticiaRecord(id) ?: throw CanonicalErrorCode.NEWS_NOT_FOUND.exception()

    private fun requireKnownAssets(vararg paths: String?) {
        if (paths.any { it != null && !NewsRules.ASSET_PATH.matches(it) }) throw CanonicalErrorCode.ASSET_PATH_UNKNOWN.exception()
    }

    private fun requireActiveReferences(especialidadId: String?, tipoNoticiaId: String?) {
        if (especialidadId != null && !gateway.especialidadIsActive(especialidadId)) throw CanonicalErrorCode.INACTIVE_TIPO_OR_ESPECIALIDAD.exception()
        if (tipoNoticiaId != null && !gateway.tipoNoticiaIsActive(tipoNoticiaId)) throw CanonicalErrorCode.INACTIVE_TIPO_OR_ESPECIALIDAD.exception()
    }

    private fun record(accion: String, tabla: String, registroId: String?, antes: JsonElement? = null, despues: JsonElement? = null) {
        audit.record(accion, tabla, registroId, antes, despues)
    }

    /** La busqueda es una vista derivada: que se desactualice NO invalida una escritura canonica ya hecha. */
    private fun indexBestEffort(row: NoticiaRecord) {
        try {
            onIndexed(row.toIndexDto())
        } catch (failure: Exception) {
            NewsLogger.warn("news search index update failed: {}", failure.redactedMessage("index"))
        }
    }

    private fun NoticiaRecord.version(): String = normalizeInstant(actualizadoEn)

    private fun NoticiaRecord.toResumen() = NoticiaResumenWire(
        id = id, titulo = titulo, estado = estado, especialidadId = especialidadId, tipoNoticiaId = tipoNoticiaId,
        publicarDesde = publicarDesde?.let(::normalizeInstant), publishedAt = publishedAt?.let(::normalizeInstant),
        imagenUrl = imagenPath?.let(gateway::publicAssetUrl), actualizadoEn = version(),
    )

    private fun NoticiaRecord.toWire() = NoticiaAdminWire(
        id = id, titulo = titulo, texto = texto, capas = capas, tipoFondo = tipoFondo, especialidadId = especialidadId,
        tipoNoticiaId = tipoNoticiaId, backgroundPath = backgroundPath, imagenPath = imagenPath,
        backgroundUrl = backgroundPath?.let(gateway::publicAssetUrl), imagenUrl = imagenPath?.let(gateway::publicAssetUrl),
        estado = estado, publicarDesde = publicarDesde?.let(::normalizeInstant), publishedAt = publishedAt?.let(::normalizeInstant),
        version = version(),
    )

    private fun TipoNoticiaRecord.toWire() = TipoNoticiaWire(id, codigo, nombre, slug, descripcion, activo, posicion)

    private fun NoticiaRecord.toIndexDto() = NoticiaDto(
        id = id, titulo = titulo, especialidad = especialidadId, tipo = tipoNoticiaId, fondo = tipoFondo,
        fondoPath = backgroundPath, estado = estado, publicarDesde = publicarDesde.orEmpty(), texto = texto.orEmpty(), imagenPath = imagenPath,
    )

    private fun JsonElement?.asNewPath(current: String?): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it != current }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun parseInstant(value: String): Instant? =
        try {
            OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        const val TRANSITION_ATTEMPTS = 3
    }
}

// =====================================================================
// Validacion de entrada (cable canonico). A mano, no con `receiveCanonicalCommand`: `capas` es un objeto opaco por contrato,
// `backgroundPath`/`imagenPath` admiten `null` explicito para borrar, y `posicion` es un campo de catalogo (no un ranking).
// Misma salida de error que los comandos canonicos: 400 MALFORMED_BODY / VALIDATION con `details.issues`, sin repetir la entrada.
// =====================================================================

internal object NewsCommands {
    private val SAFE_FIELD_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
    private const val TITULO_MAX = 300
    private const val TEXTO_MAX = 200_000
    private const val PATH_MAX = 500
    private const val NOMBRE_MAX = 200
    private const val SLUG_MAX = 100
    private const val DESCRIPCION_MAX = 2_000

    private val CREATE_FIELDS = setOf("titulo", "texto", "capas", "tipoFondo", "especialidadId", "tipoNoticiaId", "backgroundPath", "imagenPath")
    private val PATCH_FIELDS = CREATE_FIELDS
    private val TIPO_FIELDS = setOf("nombre", "slug", "descripcion", "activo", "posicion")

    private class Issues {
        val list = mutableListOf<ErrorIssue>()
        fun add(field: String, issue: String) {
            list += ErrorIssue(field, issue)
        }

        fun throwIfAny() {
            if (list.isNotEmpty()) throw validationError(*list.take(MAX_VALIDATION_ISSUES).toTypedArray())
        }
    }

    private fun parseBody(raw: ByteArray): JsonObject {
        val parsed = try {
            CanonicalCommandJson.parseToJsonElement(String(raw, Charsets.UTF_8).ifBlank { "{}" })
        } catch (_: kotlinx.serialization.SerializationException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        }
        return parsed as? JsonObject ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
    }

    private fun JsonObject.rejectUnknown(allowed: Set<String>, issues: Issues) {
        keys.filter { it !in allowed }.forEach { issues.add(if (SAFE_FIELD_NAME.matches(it)) it else "(desconocido)", CanonicalIssue.UNKNOWN_FIELD) }
    }

    private fun JsonObject.required(name: String, issues: Issues): Boolean {
        if (name in this) return true
        issues.add(name, CanonicalIssue.REQUIRED)
        return false
    }

    private fun JsonObject.text(name: String, issues: Issues, max: Int, allowBlank: Boolean): String? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            issues.add(name, CanonicalIssue.INVALID_FORMAT)
            return null
        }
        if (!allowBlank && primitive.content.isBlank()) {
            issues.add(name, CanonicalIssue.INVALID_FORMAT)
            return null
        }
        if (primitive.content.length > max) {
            issues.add(name, CanonicalIssue.OUT_OF_RANGE)
            return null
        }
        return primitive.content
    }

    private fun JsonObject.uuid(name: String, issues: Issues): String? {
        val value = text(name, issues, 64, allowBlank = false) ?: return null
        if (!value.isUuid()) {
            issues.add(name, CanonicalIssue.INVALID_FORMAT)
            return null
        }
        return value.lowercase()
    }

    private fun JsonObject.capas(issues: Issues): JsonArray? {
        val value = this["capas"] ?: return null
        val array = value as? JsonArray
        if (array == null || array.any { it !is JsonObject }) {
            issues.add("capas", CanonicalIssue.INVALID_FORMAT)
            return null
        }
        return array
    }

    private fun JsonObject.fondo(issues: Issues): String? {
        val value = text("tipoFondo", issues, 8, allowBlank = false) ?: return null
        if (value !in NewsRules.FONDOS) {
            issues.add("tipoFondo", CanonicalIssue.INVALID_FORMAT)
            return null
        }
        return value
    }

    fun parseCreate(raw: ByteArray): NoticiaCreate {
        val body = parseBody(raw)
        val issues = Issues()
        body.rejectUnknown(CREATE_FIELDS, issues)
        listOf("titulo", "capas", "tipoFondo", "especialidadId", "tipoNoticiaId").forEach { body.required(it, issues) }
        val titulo = body.text("titulo", issues, TITULO_MAX, allowBlank = false)?.trim()
        val texto = body.text("texto", issues, TEXTO_MAX, allowBlank = true)
        val capas = body.capas(issues)
        val fondo = body.fondo(issues)
        val especialidad = body.uuid("especialidadId", issues)
        val tipo = body.uuid("tipoNoticiaId", issues)
        val background = body.text("backgroundPath", issues, PATH_MAX, allowBlank = false)
        val imagen = body.text("imagenPath", issues, PATH_MAX, allowBlank = false)
        issues.throwIfAny()
        return NoticiaCreate(titulo!!, texto, capas!!, fondo!!, especialidad!!, tipo!!, background, imagen)
    }

    /** Campos a escribir con NOMBRE DE COLUMNA. Un `null` solo se admite en las dos rutas de asset (borra el vinculo). */
    fun parsePatch(raw: ByteArray): JsonObject {
        val body = parseBody(raw)
        val issues = Issues()
        body.rejectUnknown(PATCH_FIELDS, issues)
        val fields = mutableMapOf<String, JsonElement>()
        if ("titulo" in body) body.text("titulo", issues, TITULO_MAX, allowBlank = false)?.let { fields["titulo"] = JsonPrimitive(it.trim()) }
        if ("texto" in body) body.text("texto", issues, TEXTO_MAX, allowBlank = true)?.let { fields["texto"] = JsonPrimitive(it) }
        if ("capas" in body) body.capas(issues)?.let { fields["capas"] = it }
        if ("tipoFondo" in body) body.fondo(issues)?.let { fields["tipo_fondo"] = JsonPrimitive(it) }
        if ("especialidadId" in body) body.uuid("especialidadId", issues)?.let { fields["especialidad_id"] = JsonPrimitive(it) }
        if ("tipoNoticiaId" in body) body.uuid("tipoNoticiaId", issues)?.let { fields["tipo_noticia_id"] = JsonPrimitive(it) }
        listOf("backgroundPath" to "background_path", "imagenPath" to "imagen_path").forEach { (wire, column) ->
            if (wire !in body) return@forEach
            if (body[wire] is JsonNull) fields[column] = JsonNull else body.text(wire, issues, PATH_MAX, allowBlank = false)?.let { fields[column] = JsonPrimitive(it) }
        }
        issues.throwIfAny()
        return JsonObject(fields)
    }

    fun parseSchedule(raw: ByteArray): Instant {
        val body = parseBody(raw)
        val issues = Issues()
        body.rejectUnknown(setOf("publicarDesde"), issues)
        body.required("publicarDesde", issues)
        val text = body.text("publicarDesde", issues, 64, allowBlank = false)
        val instant = text?.let {
            try {
                OffsetDateTime.parse(it).toInstant()
            } catch (_: DateTimeParseException) {
                issues.add("publicarDesde", CanonicalIssue.INVALID_FORMAT)
                null
            }
        }
        issues.throwIfAny()
        return instant!!
    }

    fun parseTipo(raw: ByteArray): TipoNoticiaPut {
        val body = parseBody(raw)
        val issues = Issues()
        body.rejectUnknown(TIPO_FIELDS, issues)
        listOf("nombre", "slug", "activo", "posicion").forEach { body.required(it, issues) }
        val nombre = body.text("nombre", issues, NOMBRE_MAX, allowBlank = false)?.trim()
        val slug = body.text("slug", issues, SLUG_MAX, allowBlank = false)?.trim()
        val descripcion = body.text("descripcion", issues, DESCRIPCION_MAX, allowBlank = true)
        val activo = (body["activo"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if ("activo" in body && activo == null) issues.add("activo", CanonicalIssue.INVALID_FORMAT)
        val posicion = (body["posicion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if ("posicion" in body && posicion == null) issues.add("posicion", CanonicalIssue.INVALID_FORMAT)
        issues.throwIfAny()
        return TipoNoticiaPut(nombre!!, slug!!, descripcion, activo!!, posicion!!)
    }

    private val CODIGO = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** `codigo` de ruta: clave natural, sin caracteres que no sean de un identificador (nunca llega a un filtro sin validar). */
    fun validCodigo(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (!CODIGO.matches(value)) throw validationError(ErrorIssue("codigo", CanonicalIssue.INVALID_FORMAT))
        return value
    }

    /** Filtros de D4-01. Todo fallo es 400 INVALID_FILTER salvo el `limit`/cursor (VALIDATION / INVALID_CURSOR). */
    fun parseListQuery(params: io.ktor.http.Parameters): NewsListQuery {
        fun invalidFilter(field: String): Nothing =
            throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = listOf(ErrorIssue(field, CanonicalIssue.INVALID_FORMAT))))
        val estado = params["estado"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in NewsRules.ESTADOS) invalidFilter("estado") }
        val especialidad = params["especialidadId"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!it.isUuid()) invalidFilter("especialidadId") }?.lowercase()
        val tipo = params["tipoNoticiaId"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!it.isUuid()) invalidFilter("tipoNoticiaId") }?.lowercase()
        val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > NewsRules.Q_MAX) invalidFilter("q") }
        val filter = NewsListFilter(estado, especialidad, tipo, q)
        val scope = CursorScope(
            sort = listOf("actualizado_en:desc", "id:desc"),
            filters = mapOf("estado" to estado, "especialidadId" to especialidad, "tipoNoticiaId" to tipo, "q" to q?.lowercase()),
        )
        val page = CursorPageRequest.of(
            cursor = params["cursor"],
            limit = params["limit"],
            default = CursorPageRequest.ADMIN_DEFAULT_LIMIT,
            max = CursorPageRequest.ADMIN_MAX_LIMIT,
            scope = scope,
        )
        // Las claves del cursor se interpolan en un filtro: solo un instante y un uuid, o INVALID_CURSOR.
        page.after?.let { keys ->
            val at = (keys.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = (keys.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val validAt = at != null && runCatching { Instant.parse(at) }.isSuccess
            if (keys.size != 2 || !validAt || id == null || !id.isUuid()) throw CanonicalErrorCode.INVALID_CURSOR.exception()
        }
        return NewsListQuery(filter, page)
    }
}
