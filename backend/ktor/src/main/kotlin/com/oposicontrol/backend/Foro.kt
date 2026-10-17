package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// V1 wave 5b (E1-V1-W5-FORO-CONTRACT.md, FA35): Foro / Red, parte de texto.
//
//   D5-02 GET    /api/v1/app/oposired/canales                          canales visibles (puerta de especialidad)
//   D5-03 GET    /api/v1/app/oposired/publicaciones                    feed (categoriaId, orden, cursor, limit)
//   D5-04 GET    /api/v1/app/oposired/publicaciones/{id}               detalle
//   D5-05 GET    /api/v1/app/oposired/publicaciones/{id}/comentarios   comentarios planos
//   D5-06 POST   /api/v1/app/oposired/publicaciones                    crear (Idempotency-Key obligatoria)
//   D5-07 POST   /api/v1/app/oposired/publicaciones/{id}/comentarios   comentar (Idempotency-Key obligatoria)
//   D5-08 DELETE /api/v1/app/oposired/publicaciones/{id}               borrar la propia
//   D5-09 DELETE /api/v1/app/oposired/comentarios/{id}                 borrar el propio
//   D5-10/11 PUT|DELETE .../publicaciones/{id}/voto ; D5-12/13 PUT|DELETE .../comentarios/{id}/voto   voto "me gusta" (solo sube)
//   D5-14 POST   /api/v1/app/oposired/reportes                         reportar (4 motivos)
//
// Identidad = SOLO el `sub` del JWT. Toda regla (puerta de especialidad, restriccion, premoderacion, limites, duplicados, normalizacion de texto) vive en la base
// (servicio -> RPC SECURITY DEFINER); aqui solo forma, limite por usuario y traduccion de errores. Nada de ids de usuario en ninguna ruta ni cuerpo.

internal enum class ForoOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
    WRITE(OposiBotsAuthOperation.PLAN_WRITE),
}

internal class ForoRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: ForoService,
    val limit: suspend (ForoOperation, CanonicalUserId) -> Unit = { _, _ -> },
    val images: ForoImageService? = null,
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): ForoRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return ForoRuntime(
                resolver = resolver,
                service = ForoService(upstream),
                images = ForoImageService.fromEnvironment(environment),
                limit = { operation, userId ->
                    if (limiter != null) {
                        val decision = limiter.checkSubject(operation.limit, userId.value)
                        if (!decision.allowed) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                    }
                },
            )
        }
    }
}

@Serializable
@CanonicalCommand
internal data class ForoPostRequest(val categoriaId: String, val titulo: String, val cuerpo: String, val imagenId: String? = null)

@Serializable
@CanonicalCommand
internal data class ForoImageInitRequest(val mime: String, val bytes: Int)

@Serializable
@CanonicalCommand
internal data class ForoCommentRequest(val cuerpo: String)

@Serializable
@CanonicalCommand
internal data class ForoReportRequest(val entidadTipo: String, val entidadId: String, val motivo: String, val descripcion: String? = null)

internal class ForoService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun channels(user: CanonicalUserId): JsonElement = rpc("app_foro_categorias", buildJsonObject { put("p_usuario", user.value) })

    suspend fun list(user: CanonicalUserId, categoriaId: String?, orden: String?, cursor: String?, limit: Int): JsonElement {
        if (categoriaId != null && !categoriaId.isUuid()) throw validationError(ErrorIssue("categoriaId", CanonicalIssue.INVALID_FORMAT))
        if (orden != null && orden != "recientes" && orden != "votadas") throw validationError(ErrorIssue("orden", CanonicalIssue.INVALID_FORMAT))
        checkCursor(cursor)
        return rpc(
            "app_foro_list",
            buildJsonObject {
                put("p_usuario", user.value)
                put("p_categoria", categoriaId?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
                put("p_orden", orden ?: "recientes")
                put("p_cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
                put("p_limite", limit.coerceIn(1, MAX_LIMIT))
            },
        )
    }

    suspend fun post(user: CanonicalUserId, id: String): JsonElement = rpc("app_foro_post", buildJsonObject { put("p_usuario", user.value); put("p_id", requireId(id)) })

    suspend fun comments(user: CanonicalUserId, id: String, cursor: String?, limit: Int): JsonElement {
        checkCursor(cursor)
        return rpc(
            "app_foro_comments",
            buildJsonObject {
                put("p_usuario", user.value); put("p_post", requireId(id))
                put("p_cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull); put("p_limite", limit.coerceIn(1, MAX_LIMIT))
            },
        )
    }

    suspend fun createPost(user: CanonicalUserId, request: ForoPostRequest, key: String?): JsonElement {
        if (!request.categoriaId.isUuid()) throw validationError(ErrorIssue("categoriaId", CanonicalIssue.INVALID_FORMAT))
        if (request.imagenId != null && !request.imagenId.isUuid()) throw validationError(ErrorIssue("imagenId", CanonicalIssue.INVALID_FORMAT))
        return rpc(
            "app_foro_create_post",
            buildJsonObject {
                put("p_usuario", user.value); put("p_categoria", request.categoriaId.lowercase()); put("p_titulo", request.titulo); put("p_cuerpo", request.cuerpo); put("p_clave", key?.let { JsonPrimitive(it) } ?: JsonNull)
                put("p_imagen", request.imagenId?.takeIf { it.isUuid() }?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    suspend fun createComment(user: CanonicalUserId, id: String, request: ForoCommentRequest, key: String?): JsonElement =
        rpc("app_foro_create_comment", buildJsonObject { put("p_usuario", user.value); put("p_post", requireId(id)); put("p_cuerpo", request.cuerpo); put("p_clave", key?.let { JsonPrimitive(it) } ?: JsonNull) })

    suspend fun deleteOwn(user: CanonicalUserId, tipo: String, id: String): JsonElement =
        rpc("app_foro_delete_own", buildJsonObject { put("p_usuario", user.value); put("p_tipo", tipo); put("p_id", requireId(id)) })

    suspend fun vote(user: CanonicalUserId, tipo: String, id: String, on: Boolean): JsonElement =
        rpc("app_foro_vote", buildJsonObject { put("p_usuario", user.value); put("p_tipo", tipo); put("p_id", requireId(id)); put("p_on", on) })

    suspend fun report(user: CanonicalUserId, request: ForoReportRequest): JsonElement {
        if (request.entidadTipo != "publicacion" && request.entidadTipo != "comentario") throw validationError(ErrorIssue("entidadTipo", CanonicalIssue.INVALID_FORMAT))
        return rpc(
            "app_foro_report",
            buildJsonObject {
                put("p_usuario", user.value); put("p_tipo", request.entidadTipo); put("p_id", requireId(request.entidadId)); put("p_motivo", request.motivo)
                put("p_descripcion", request.descripcion?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    private fun requireId(id: String): String {
        if (!id.isUuid()) throw validationError(ErrorIssue("id", CanonicalIssue.INVALID_FORMAT))
        return id.lowercase()
    }

    private fun checkCursor(cursor: String?) {
        if (cursor != null && (cursor.length > 300 || !cursor.matches(CURSOR_PATTERN))) throw CanonicalErrorCode.INVALID_CURSOR.exception()
    }

    private suspend fun rpc(function: String, body: JsonObject): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body.toString()) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    companion object {
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20
        private val CURSOR_PATTERN = Regex("^[A-Za-z0-9_=-]+$")
    }
}

private val ForoIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-foro-identity")

private suspend fun ApplicationCall.foroGate(runtime: ForoRuntime?, operation: ForoOperation): CanonicalUserIdentity {
    attributes.getOrNull(ForoIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(ForoIdentityKey, identity)
    return identity
}

private fun ApplicationCall.pageLimit(): Int {
    val raw = request.queryParameters["limit"]?.trim() ?: return ForoService.DEFAULT_LIMIT
    return raw.toIntOrNull() ?: throw validationError(ErrorIssue("limit", CanonicalIssue.INVALID_FORMAT))
}

private fun ApplicationCall.writeKey(): String? = IdempotencyKey.parse(request.headers["Idempotency-Key"], required = false)?.value

private suspend fun ApplicationCall.respondCreated(result: JsonElement, replayedField: String) {
    val replayed = (result as? JsonObject)?.get(replayedField)?.jsonPrimitive?.booleanOrNull == true
    if (replayed) {
        response.header(IdempotencyHeaders.REPLAYED, "true")
        respond(HttpStatusCode.OK, result)
    } else {
        respond(HttpStatusCode.Created, result)
    }
}

internal fun Route.foroRoutes(runtime: ForoRuntime?) {
    get("/api/v1/app/oposired/canales") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.channels(identity.userId))
    }
    get("/api/v1/app/oposired/publicaciones") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, setOf("categoriaId", "orden", "cursor", "limit"))
        val q = call.request.queryParameters
        call.respond(runtime!!.service.list(identity.userId, q["categoriaId"]?.trim(), q["orden"]?.trim(), q["cursor"]?.trim(), call.pageLimit()))
    }
    get("/api/v1/app/oposired/publicaciones/{id}") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.post(identity.userId, call.parameters["id"].orEmpty().trim()))
    }
    get("/api/v1/app/oposired/publicaciones/{id}/comentarios") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, setOf("cursor", "limit"))
        call.respond(runtime!!.service.comments(identity.userId, call.parameters["id"].orEmpty().trim(), call.request.queryParameters["cursor"]?.trim(), call.pageLimit()))
    }
    post("/api/v1/app/oposired/publicaciones") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val key = call.writeKey()
        val request = call.receiveCanonicalCommand<ForoPostRequest>()
        call.respondCreated(runtime!!.service.createPost(identity.userId, request, key), "repetida")
    }
    post("/api/v1/app/oposired/publicaciones/{id}/comentarios") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val key = call.writeKey()
        val request = call.receiveCanonicalCommand<ForoCommentRequest>()
        call.respondCreated(runtime!!.service.createComment(identity.userId, call.parameters["id"].orEmpty().trim(), request, key), "repetido")
    }
    delete("/api/v1/app/oposired/publicaciones/{id}") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.deleteOwn(identity.userId, "publicacion", call.parameters["id"].orEmpty().trim()))
    }
    delete("/api/v1/app/oposired/comentarios/{id}") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.deleteOwn(identity.userId, "comentario", call.parameters["id"].orEmpty().trim()))
    }
    for ((segment, tipo) in listOf("publicaciones" to "publicacion", "comentarios" to "comentario")) {
        put("/api/v1/app/oposired/$segment/{id}/voto") {
            val identity = call.foroGate(runtime, ForoOperation.WRITE)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.vote(identity.userId, tipo, call.parameters["id"].orEmpty().trim(), true))
        }
        delete("/api/v1/app/oposired/$segment/{id}/voto") {
            val identity = call.foroGate(runtime, ForoOperation.WRITE)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.vote(identity.userId, tipo, call.parameters["id"].orEmpty().trim(), false))
        }
    }
    // ---- images (D5-30..D5-33): the bytes come here, are verified and RE-ENCODED, and only then stored (private bucket)
    post("/api/v1/app/oposired/imagenes") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val images = runtime!!.images ?: throw CanonicalErrorCode.INTERNAL.exception()
        val request = call.receiveCanonicalCommand<ForoImageInitRequest>()
        call.respond(HttpStatusCode.Created, images.init(identity.userId, request.mime, request.bytes))
    }
    put("/api/v1/app/oposired/imagenes/{id}/contenido") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val images = runtime!!.images ?: throw CanonicalErrorCode.INTERNAL.exception()
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: throw validationError(ErrorIssue("Content-Length", CanonicalIssue.REQUIRED))
        if (declared < 1 || declared > ForoImageSanitizer.MAX_BYTES) throw CanonicalErrorCode.IMAGE_REJECTED.exception()
        val body = call.receive<ByteArray>()
        call.respond(images.upload(identity.userId, call.parameters["id"].orEmpty().trim(), call.request.headers[HttpHeaders.ContentType], body))
    }
    get("/api/v1/app/oposired/imagenes/{id}") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val images = runtime!!.images ?: throw CanonicalErrorCode.INTERNAL.exception()
        call.respond(images.status(identity.userId, call.parameters["id"].orEmpty().trim()))
    }
    get("/api/v1/app/oposired/publicaciones/{id}/imagen") {
        val identity = call.foroGate(runtime, ForoOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val images = runtime!!.images ?: throw CanonicalErrorCode.INTERNAL.exception()
        call.respond(images.signedUrl(identity.userId, call.parameters["id"].orEmpty().trim()))
    }
    post("/api/v1/app/oposired/reportes") {
        val identity = call.foroGate(runtime, ForoOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val request = call.receiveCanonicalCommand<ForoReportRequest>(nullableFields = setOf("descripcion"))
        call.respond(HttpStatusCode.Accepted, runtime!!.service.report(identity.userId, request))
    }
}
