package com.oposicontrol.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Lo que las rutas canonicas de noticias necesitan. `null` = sin runtime: las rutas existen y fallan cerrado (500). */
internal class NewsRuntime(
    val service: NewsService,
    val idempotency: IdempotencyStore,
)

/** Tope del fichero de D4-09 (igual que el de las portadas y el del cliente OC). */
internal const val NEWS_ASSET_MAX_BYTES = 10 * 1024 * 1024

/** Margen de cabeceras multipart sobre el fichero: un `Content-Length` mayor ya no puede caber. */
private const val MULTIPART_OVERHEAD_BYTES = 64 * 1024

private val NEWS_ASSET_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
private val NEWS_ASSET_ROLES = setOf("background", "imagen")
private const val NEWS_NO_STORE = "no-store"

/**
 * Noticias de administracion (`openapi/news/news.yaml`, D4-01..D4-11), plano admin: van DENTRO del bloque
 * `authenticate("gateway-auth")` de [installControlRoutes] (401 sin credencial valida, 403 sin rol admin ACTIVO) y despues de
 * [installAdminAuditActor] (el actor de la auditoria y de la clave de idempotencia es SIEMPRE el `sub` del JWT).
 * Ruta -> [NewsService] (compartido con `/content/noticias*`) -> [NewsGateway]. Las llamadas bloqueantes van en
 * [withUpstreamIo] (conserva MDC y actor de auditoria).
 *
 * Orden: id de ruta (400) -> `If-Match` (400) -> cuerpo (400) -> reglas de negocio. Con `Idempotency-Key` (D4-03, D4-09) la
 * clave se valida antes que el cuerpo y una repeticion devuelve la respuesta guardada sin ejecutar.
 */
internal fun Route.adminNewsRoutes(runtime: NewsRuntime?) {
    val store: IdempotencyStore = runtime?.idempotency ?: MisconfiguredNewsIdempotencyStore
    route("/api/v1/admin") {
        route("/noticias") {
            get {
                val rt = runtime.orMisconfigured()
                val query = NewsCommands.parseListQuery(call.request.queryParameters)
                call.response.header(HttpHeaders.CacheControl, NEWS_NO_STORE)
                call.respond(withUpstreamIo { rt.service.list(query) })
            }
            post {
                val rt = runtime.orMisconfigured()
                val cmd = NewsCommands.parseCreate(call.jsonBody())
                val created = withUpstreamIo { rt.service.create(cmd) }
                call.response.header(HttpHeaders.Location, "/api/v1/admin/noticias/${created.id}")
                call.respond(HttpStatusCode.Created, created)
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/admin/noticias"
                required = true
                afterAuthentication = true
                this.store = store
                before = { call -> call.newsGate(runtime) }
                actor = { call -> call.newsActor() }
            }
            post("/assets") {
                val rt = runtime.orMisconfigured()
                val upload = call.attributes.getOrNull(NewsAssetKey) ?: throw CanonicalErrorCode.INTERNAL.exception()
                val stored = withUpstreamIo { rt.service.uploadAsset(NewsUploadedFile(upload.fileName, upload.mime, upload.bytes)) }
                call.respond(HttpStatusCode.Created, stored)
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/admin/noticias/assets"
                required = true
                afterAuthentication = true
                this.store = store
                before = { call -> call.newsGate(runtime) }
                actor = { call -> call.newsActor() }
                // Multipart: la huella es el contenido (fichero + MIME + rol), y la lectura es acotada y UNICA.
                canonicalBody = { call -> call.readNewsAsset().fingerprint }
            }
            route("/{noticiaId}") {
                get {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    val noticia = withUpstreamIo { rt.service.get(id) }
                    call.response.header(HttpHeaders.CacheControl, NEWS_NO_STORE)
                    call.response.header(HttpHeaders.ETag, "\"${noticia.version}\"")
                    call.respond(noticia)
                }
                patch {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    val version = parseNewsIfMatch(call.request.header(HttpHeaders.IfMatch))
                    val fields = NewsCommands.parsePatch(call.jsonBody())
                    val noticia = withUpstreamIo { rt.service.patch(id, version, fields) }
                    call.response.header(HttpHeaders.ETag, "\"${noticia.version}\"")
                    call.respond(noticia)
                }
                delete {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    withUpstreamIo { rt.service.delete(id) }
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/publicar") {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    call.respond(withUpstreamIo { rt.service.publish(id) })
                }
                post("/programar") {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    val at = NewsCommands.parseSchedule(call.jsonBody())
                    call.respond(withUpstreamIo { rt.service.schedule(id, at) })
                }
                post("/archivar") {
                    val rt = runtime.orMisconfigured()
                    val id = call.newsPathUuid("noticiaId")
                    call.respond(withUpstreamIo { rt.service.archive(id) })
                }
            }
        }
        route("/tipos-noticia") {
            get {
                val rt = runtime.orMisconfigured()
                val params = call.request.queryParameters
                val includeInactive = when (params["includeInactive"]?.trim()?.lowercase()) {
                    null, "" -> true
                    "true" -> true
                    "false" -> false
                    else -> throw validationError(ErrorIssue("includeInactive", CanonicalIssue.INVALID_FORMAT))
                }
                val limit = (params["limit"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull()?.takeIf { value -> value >= 1 } ?: throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE))
                } ?: TYPES_DEFAULT_LIMIT).coerceAtMost(TYPES_MAX_LIMIT)
                val offset = (params["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull() ?: throw validationError(ErrorIssue("offset", CanonicalIssue.OUT_OF_RANGE))
                } ?: 0).coerceAtLeast(0)
                val page = withUpstreamIo { rt.service.listTypes(includeInactive, limit, offset) }
                call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
                call.respond(page)
            }
            put("/{codigo}") {
                val rt = runtime.orMisconfigured()
                val codigo = NewsCommands.validCodigo(call.parameters["codigo"])
                val cmd = NewsCommands.parseTipo(call.jsonBody())
                val (tipo, created) = withUpstreamIo { rt.service.upsertType(codigo, cmd) }
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, tipo)
            }
        }
    }
}

private const val TYPES_DEFAULT_LIMIT = 100
private const val TYPES_MAX_LIMIT = 200

private fun NewsRuntime?.orMisconfigured(): NewsRuntime = this ?: throw CanonicalErrorCode.INTERNAL.exception()

/** El actor de la clave de idempotencia: el `sub` del JWT ya autenticado y autorizado. Otra cosa (token estatico): 401. */
private fun ApplicationCall.newsActor(): String =
    actorIdFromPrincipalName(principal<UserIdPrincipal>()?.name) ?: throw CanonicalErrorCode.UNAUTHENTICATED.exception()

/** Corre antes de mirar la `Idempotency-Key`: sin runtime falla cerrado y el identificador de ruta mal formado es 400. */
private fun ApplicationCall.newsGate(runtime: NewsRuntime?) {
    runtime.orMisconfigured()
    response.header(HttpHeaders.CacheControl, NEWS_NO_STORE)
}

/** Id de ruta en forma canonica (8-4-4-4-12), en minusculas. Otra cosa: 400 VALIDATION INVALID_FORMAT. */
private fun ApplicationCall.newsPathUuid(name: String): String {
    val value = parameters[name].orEmpty().trim()
    if (!value.isUuid()) throw validationError(ErrorIssue(name, CanonicalIssue.INVALID_FORMAT))
    return value.lowercase()
}

/** Cuerpo JSON (lectura unica, acotada a [CANONICAL_COMMAND_MAX_BYTES]); un tipo de contenido distinto con cuerpo es 415. */
private suspend fun ApplicationCall.jsonBody(): ByteArray = canonicalRawBody().also { requireJsonContentIfBody(it) }

/**
 * `If-Match` OBLIGATORIO de D4-04: la version (`NoticiaAdmin.version`, un instante), tal cual o entre comillas (el ETag de D4-02).
 * Ausente -> 400 VALIDATION REQUIRED; no es un instante (o es `*`) -> 400 VALIDATION INVALID_FORMAT.
 */
internal fun parseNewsIfMatch(header: String?): String {
    val value = header?.trim()?.removePrefix("W/")?.trim('"')?.trim().orEmpty()
    if (value.isEmpty()) throw validationError(ErrorIssue("If-Match", CanonicalIssue.REQUIRED))
    return try {
        OffsetDateTime.parse(value).toInstant().toString()
    } catch (_: DateTimeParseException) {
        throw validationError(ErrorIssue("If-Match", CanonicalIssue.INVALID_FORMAT))
    }
}

// ---------------------------------------------------------------------
// D4-09: lectura acotada del multipart (una sola vez, la comparten la huella de idempotencia y el handler)
// ---------------------------------------------------------------------

internal class NewsAssetUpload(val fileName: String, val mime: String, val bytes: ByteArray, val rol: String?) {
    val fingerprint: String
        get() = RequestFingerprint.multipartBody(
            listOf("file" to bytes, "mime" to mime.toByteArray(Charsets.UTF_8), "rol" to rol.orEmpty().toByteArray(Charsets.UTF_8)),
        )
}

private val NewsAssetKey = AttributeKey<NewsAssetUpload>("oc-news-asset-upload")

private suspend fun ApplicationCall.readNewsAsset(): NewsAssetUpload {
    attributes.getOrNull(NewsAssetKey)?.let { return it }
    if (!request.contentType().match(ContentType.MultiPart.FormData)) throw CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE.exception()
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > NEWS_ASSET_MAX_BYTES + MULTIPART_OVERHEAD_BYTES) throw CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
    var file: Triple<String, String, ByteArray>? = null
    var rol: String? = null
    val multipart = receiveMultipart()
    var part = multipart.readPart()
    while (part != null) {
        try {
            when {
                part is PartData.FileItem && part.name == "file" && file == null -> {
                    // Lectura acotada: nunca se bufferiza mas de tope+1 bytes.
                    val bytes = part.provider().readRemaining(NEWS_ASSET_MAX_BYTES.toLong() + 1).readByteArray()
                    if (bytes.size > NEWS_ASSET_MAX_BYTES) throw CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
                    val mime = part.contentType?.let { "${it.contentType}/${it.contentSubtype}" }.orEmpty().lowercase()
                    file = Triple(part.originalFileName.orEmpty().ifBlank { "news-asset" }, mime, bytes)
                }
                part is PartData.FormItem && part.name == "rol" -> rol = part.value.trim()
                else -> Unit
            }
        } finally {
            part.dispose.invoke()
        }
        part = multipart.readPart()
    }
    val (name, mime, bytes) = file ?: throw validationError(ErrorIssue("file", CanonicalIssue.REQUIRED))
    if (rol != null && rol !in NEWS_ASSET_ROLES) throw validationError(ErrorIssue("rol", CanonicalIssue.INVALID_FORMAT))
    if (mime !in NEWS_ASSET_MIMES) throw CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE.exception()
    // El MIME lo declara el cliente: los primeros bytes tienen que ser de ese formato.
    if (bytes.isEmpty() || !bytes.looksLike(mime)) throw validationError(ErrorIssue("file", CanonicalIssue.INVALID_FORMAT))
    return NewsAssetUpload(name, mime, bytes, rol).also { attributes.put(NewsAssetKey, it) }
}

private fun ByteArray.startsWith(vararg prefix: Int, offset: Int = 0): Boolean =
    size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it].toByte() }

private fun ByteArray.looksLike(mime: String): Boolean = when (mime) {
    "image/png" -> startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    "image/jpeg" -> startsWith(0xFF, 0xD8, 0xFF)
    "image/gif" -> startsWith(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || startsWith(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
    "image/webp" -> startsWith(0x52, 0x49, 0x46, 0x46) && startsWith(0x57, 0x45, 0x42, 0x50, offset = 8)
    else -> false
}

/** Sin runtime no hay almacen: la puerta ya responde 500 antes; esto solo evita un `lateinit` sin inicializar. */
private object MisconfiguredNewsIdempotencyStore : IdempotencyStore {
    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = throw CanonicalErrorCode.INTERNAL.exception()

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) = Unit

    override suspend fun release(claim: IdempotencyClaim) = Unit
}
