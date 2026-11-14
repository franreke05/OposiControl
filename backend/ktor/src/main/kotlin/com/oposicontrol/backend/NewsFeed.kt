package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// F25 / FA49 (decision del propietario 2026-11-11): noticias de la app, SOLO LECTURA.
//
//   D4-18 GET /api/v1/app/noticias              noticias visibles, por cursor (fecha desc, id desc)
//   D4-27 GET /api/v1/app/noticias/{noticiaId}  una noticia visible, con su cuerpo (`texto`)
//
// Alcance: las de la especialidad principal del usuario; un usuario SIN especialidad ve las de todas (regla del propietario
// 2026-11-14). Visible = su `tipo_noticia` esta activo y
//   - `published` con `published_at` (lo sella el trigger) y `publicar_desde` nula o pasada (= RLS `noticia_select_publico`)
//     -> fecha = published_at, o
//   - `scheduled` con `publicar_desde` pasada: no hay trabajo que la publique, asi que una programada vencida se sirve
//     EXACTAMENTE como publicada, con fecha = publicar_desde (el momento en que paso a ser publica). Las futuras nunca salen.
// Dos lecturas PostgREST (una por estado) tras el mismo cursor `(fecha, id)` mezcladas por `(fecha desc, id desc)`, como D7-05.
// Imagenes: el bucket `news-assets` es PUBLICO; Ktor construye la URL publica del objeto a partir de la ruta guardada. Una capa
// `imagen` cuyo contenido no es un objeto de `news-assets` sale con contenido vacio (no se reenvia una URL ajena).
// Sin runtime las rutas existen y fallan cerradas (500).
// =====================================================================

internal class AppNewsRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: AppNewsService,
    /** Limite por usuario CATALOG_READ (revision 2026-11-14), antes de leer nada. Por defecto no limita (pruebas). */
    val limit: suspend (CanonicalUserId) -> Unit = { },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime? = null,
            environment: (String) -> String? = System::getenv,
        ): AppNewsRuntime? {
            val transport = SupabaseRequestTransport.fromEnvironment(environment) ?: return null
            val bucket = environment("SUPABASE_BUCKET_NEWS_ASSETS").orEmpty().ifBlank { NEWS_ASSETS_BUCKET }
            val limiter = auth?.rateLimiter
            return AppNewsRuntime(
                resolver,
                AppNewsService(AppNewsGateway(upstream = { path -> transport.get(path) }), publicUrl = { path -> transport.storagePublicUrl(bucket, path) }),
                limit = { userId ->
                    if (limiter != null) {
                        val decision = limiter.checkSubject(OposiBotsAuthOperation.CATALOG_READ, userId.value)
                        if (!decision.allowed) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                    }
                },
            )
        }
    }
}

internal fun Route.appNewsRoutes(runtime: AppNewsRuntime?) {
    route("/api/v1/app/noticias") {
        get {
            val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
            val identity = rt.resolver.activeIdentity(call) { userId -> rt.limit(userId) }
            val page = parseAppNewsQuery(call.request.queryParameters)
            val result = rt.service.list(identity.userId, page)
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(result)
        }
        get("/{noticiaId}") {
            val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
            val identity = rt.resolver.activeIdentity(call) { userId -> rt.limit(userId) }
            val id = call.parameters["noticiaId"].orEmpty().trim()
            if (!id.isUuid()) throw validationError(ErrorIssue("noticiaId", CanonicalIssue.INVALID_FORMAT))
            val noticia = rt.service.detail(identity.userId, id.lowercase())
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(noticia)
        }
    }
}

internal fun parseAppNewsQuery(params: Parameters): CursorPageRequest = CursorPageRequest.of(
    cursor = params["cursor"],
    limit = params["limit"],
    default = CursorPageRequest.APP_DEFAULT_LIMIT,
    max = CursorPageRequest.APP_MAX_LIMIT,
    scope = CursorScope(sort = listOf("fecha desc", "id desc"), filters = emptyMap()),
)

/** Largo maximo del `resumen` (primer parrafo de `texto`). */
internal const val APP_NEWS_RESUMEN_MAX = 160

// ---------- cable ----------

@Serializable
internal data class AppNoticiaCapa(
    val tipo: String,
    val contenido: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val rotationDegrees: Int,
    val fontFamily: String,
    val fontSizeSp: Int,
    val fontWeight: String,
    val textColor: String,
    val orden: Int,
)

@Serializable
internal data class AppNoticia(
    val id: String,
    val titulo: String,
    val resumen: String? = null,
    val fecha: String,
    val tipoFondo: String,
    val backgroundUrl: String? = null,
    val imagenUrl: String? = null,
    val capas: List<AppNoticiaCapa>,
    /** Solo en el detalle (D4-27). */
    val texto: String? = null,
)

// ---------- pasarela (solo GET PostgREST, columnas explicitas) ----------

@Serializable
internal data class AppNewsTipoRef(val activo: Boolean = false)

@Serializable
internal data class AppNewsRow(
    val id: String,
    val titulo: String,
    val texto: String? = null,
    val capas: JsonArray = JsonArray(emptyList()),
    @SerialName("tipo_fondo") val tipoFondo: String = "A",
    @SerialName("background_path") val backgroundPath: String? = null,
    @SerialName("imagen_path") val imagenPath: String? = null,
    val estado: String,
    @SerialName("publicar_desde") val publicarDesde: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    /** Embebido `tipo_noticia!inner(activo)`: `null` o inactivo = no visible. */
    @SerialName("tipo_noticia") val tipoNoticia: AppNewsTipoRef? = null,
)

@Serializable
private data class AppNewsSpecialtyRow(@SerialName("especialidad_principal_id") val especialidadPrincipalId: String? = null)

internal class AppNewsGateway(
    private val upstream: AppReadsUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private inline fun <reified T> decodeList(body: String): List<T> = json.decodeFromString(ListSerializer(serializer<T>()), body)

    fun specialtyOf(userId: String): String? {
        require(userId.isUuid()) { "userId" }
        return decodeList<AppNewsSpecialtyRow>(
            upstream.get("/rest/v1/perfiles_usuario?select=especialidad_principal_id&usuario_id=eq.$userId&limit=1"),
        ).firstOrNull()?.especialidadPrincipalId
    }

    /** `published` visibles, `published_at DESC, id DESC`, tras [after]. [especialidadId] `null` = todas. */
    fun published(especialidadId: String?, now: Instant, after: KeysetPosition?, fetchLimit: Int): List<AppNewsRow> {
        val at = encodeQuery(now.toString())
        val and = StringBuilder("or(publicar_desde.is.null,publicar_desde.lte.$at)")
        if (after != null) and.append(",").append(keysetOr("published_at", after))
        return decodeList(
            upstream.get(
                "/rest/v1/noticia?select=$COLUMNS${scope(especialidadId)}&estado=eq.published&published_at=not.is.null" +
                    "&and=($and)&order=published_at.desc,id.desc&limit=$fetchLimit",
            ),
        )
    }

    /** `scheduled` cuya fecha ya paso, `publicar_desde DESC, id DESC`, tras [after]. [especialidadId] `null` = todas. */
    fun scheduledDue(especialidadId: String?, now: Instant, after: KeysetPosition?, fetchLimit: Int): List<AppNewsRow> {
        val query = StringBuilder(
            "/rest/v1/noticia?select=$COLUMNS${scope(especialidadId)}&estado=eq.scheduled" +
                "&publicar_desde=lte.${encodeQuery(now.toString())}",
        )
        if (after != null) query.append("&and=(").append(keysetOr("publicar_desde", after)).append(")")
        query.append("&order=publicar_desde.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** Una noticia (de la especialidad si se da), sin filtro de visibilidad (lo decide el servicio con el mismo reloj). */
    fun byId(especialidadId: String?, id: String): AppNewsRow? {
        require(id.isUuid()) { "id" }
        return decodeList<AppNewsRow>(
            upstream.get("/rest/v1/noticia?select=$COLUMNS&id=eq.$id${scope(especialidadId)}&limit=1"),
        ).firstOrNull()
    }

    /** Filtro de tipo activo (siempre) y de especialidad (si la hay). */
    private fun scope(especialidadId: String?): String {
        require(especialidadId == null || especialidadId.isUuid()) { "especialidadId" }
        return "&tipo_noticia.activo=is.true" + (especialidadId?.let { "&especialidad_id=eq.$it" } ?: "")
    }

    /** Keyset descendente `(col, id) < (X, Y)` como expresion de `and=(...)`. [after] ya esta validado. */
    private fun keysetOr(column: String, after: KeysetPosition): String {
        val ts = encodeQuery(after.timestamp)
        return "or($column.lt.$ts,and($column.eq.$ts,id.lt.${after.id}))"
    }

    companion object {
        /**
         * `texto` se lee tambien en el listado para el `resumen`, que se recorta aqui: el cuerpo entero solo sale en el detalle.
         * `tipo_noticia!inner(activo)` + `tipo_noticia.activo=is.true` deja fuera las noticias de un tipo desactivado.
         */
        internal const val COLUMNS =
            "id,titulo,texto,capas,tipo_fondo,background_path,imagen_path,estado,publicar_desde,published_at,tipo_noticia!inner(activo)"
    }
}

// ---------- servicio ----------

internal class AppNewsService(
    private val gateway: AppNewsGateway,
    /** Ruta de `news-assets` -> URL publica del objeto (bucket publico). */
    private val publicUrl: (String) -> String,
    private val clock: () -> Instant = Instant::now,
) {
    private class Keyed(val row: AppNewsRow, val at: Instant, val rawAt: String)

    /** Especialidad del usuario, o `null` = sin especialidad (ve todas). Una especialidad que no es uuid es un contrato roto. */
    private fun scopeOf(userId: CanonicalUserId): String? =
        gateway.specialtyOf(userId.value)?.also { if (!it.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }?.lowercase()

    suspend fun list(userId: CanonicalUserId, page: CursorPageRequest): CursorPage<AppNoticia> {
        val after = KeysetPosition.fromCursorKeys(page.after)
        val merged = withUpstreamIo {
            val especialidad = scopeOf(userId)
            val now = clock()
            val fetch = page.fetchLimit
            (gateway.published(especialidad, now, after, fetch) + gateway.scheduledDue(especialidad, now, after, fetch))
                .mapNotNull { it.visibleAt(now) }
                .sortedWith(compareByDescending<Keyed> { it.at }.thenByDescending { it.row.id.lowercase() })
                .take(fetch)
        }
        val keyed = page.page(merged) { KeysetPosition(it.rawAt, it.row.id.lowercase()).toKeys() }
        return CursorPage(items = keyed.items.map { it.toWire(withTexto = false) }, nextCursor = keyed.nextCursor)
    }

    suspend fun detail(userId: CanonicalUserId, id: String): AppNoticia {
        val now = clock()
        val row = withUpstreamIo { gateway.byId(scopeOf(userId), id) } ?: throw CanonicalErrorCode.NEWS_NOT_FOUND.exception()
        val keyed = row.visibleAt(now) ?: throw CanonicalErrorCode.NEWS_NOT_FOUND.exception()
        return keyed.toWire(withTexto = true)
    }

    /** La regla de visibilidad de la cabecera, tambien para el detalle (y defensa sobre las lecturas). `null` = no visible. */
    private fun AppNewsRow.visibleAt(now: Instant): Keyed? {
        if (tipoNoticia?.activo != true) return null
        return when (estado) {
            "published" -> publishedAt?.takeIf { publicarDesde == null || !instantOrFail(publicarDesde).isAfter(now) }?.let { keyed() }
            "scheduled" -> publicarDesde?.takeIf { !instantOrFail(it).isAfter(now) }?.let { keyed() }
            else -> null
        }
    }

    private fun AppNewsRow.keyed(): Keyed {
        if (!id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val raw = (if (estado == "published") publishedAt else publicarDesde) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return Keyed(this, instantOrFail(raw), raw)
    }

    private fun assetUrl(path: String?): String? = path?.takeIf { NewsRules.ASSET_PATH.matches(it) }?.let(publicUrl)

    private fun Keyed.toWire(withTexto: Boolean): AppNoticia = AppNoticia(
        id = row.id.lowercase(),
        titulo = row.titulo,
        resumen = resumenOf(row.texto),
        fecha = at.toString(),
        tipoFondo = row.tipoFondo.takeIf { it in TIPOS_FONDO } ?: "A",
        backgroundUrl = assetUrl(row.backgroundPath),
        imagenUrl = assetUrl(row.imagenPath),
        capas = row.capas.mapNotNull { it.toCapa() }.sortedBy { it.orden },
        texto = if (withTexto) row.texto.orEmpty() else null,
    )

    private fun JsonElement.toCapa(): AppNoticiaCapa? {
        val o = this as? JsonObject ?: return null
        fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun i(key: String, default: Int) = (o[key] as? JsonPrimitive)?.intOrNull ?: default
        if ((o["visible"] as? JsonPrimitive)?.booleanOrNull == false) return null
        val tipo = s("tipo")?.takeIf { it in TIPOS_CAPA } ?: return null
        // La URL se RECONSTRUYE desde la ruta validada: nunca se reenvia el texto que escribio el editor.
        val contenido = if (tipo == "imagen") assetUrl(imageLayerPath(o)).orEmpty() else s("contenido").orEmpty()
        return AppNoticiaCapa(
            tipo = tipo,
            contenido = contenido,
            x = i("x", 0),
            y = i("y", 0),
            width = i("width", 100),
            height = i("height", 40),
            opacity = i("opacity", 100).coerceIn(0, 100),
            rotationDegrees = i("rotationDegrees", 0),
            fontFamily = s("fontFamily").orEmpty().ifBlank { "sans" },
            fontSizeSp = i("fontSizeSp", 0).coerceAtLeast(0),
            fontWeight = s("fontWeight").orEmpty(),
            textColor = s("textColor")?.takeIf { HEX_COLOR.matches(it) }.orEmpty(),
            orden = i("orden", 0),
        )
    }

    companion object {
        private val TIPOS_FONDO = setOf("A", "B", "C")
        private val TIPOS_CAPA = setOf("titulo", "texto", "imagen")
        private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        private val PUBLIC_OBJECT = Regex("/storage/v1/object/public/[A-Za-z0-9._-]+/(news/[^?#]+)$")

        /** Ruta de `news-assets` de una capa `imagen` (su contenido es la URL publica que escribio el editor), o `null`. */
        internal fun imageLayerPath(capa: JsonElement): String? {
            val o = capa as? JsonObject ?: return null
            if ((o["tipo"] as? JsonPrimitive)?.contentOrNull != "imagen") return null
            val url = (o["contenido"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return PUBLIC_OBJECT.find(url)?.groupValues?.get(1)?.takeIf { NewsRules.ASSET_PATH.matches(it) }
        }

        /** Primer parrafo no vacio de [texto], recortado a [APP_NEWS_RESUMEN_MAX] caracteres; `null` sin texto. */
        internal fun resumenOf(texto: String?): String? {
            val first = texto?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            return if (first.length <= APP_NEWS_RESUMEN_MAX) first else first.take(APP_NEWS_RESUMEN_MAX - 1).trimEnd() + "…"
        }
    }
}

private fun instantOrFail(raw: String): Instant =
    runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }
