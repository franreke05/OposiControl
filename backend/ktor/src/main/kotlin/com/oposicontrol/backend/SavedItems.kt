package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

// Guardar + OposiTeca (V1, E1-V1-SAVED-CONTRACT.md, FA26): preguntas guardadas del usuario. SOLO `tipo = pregunta`.
//
//   D4-20 PUT    /api/v1/app/opositeca/guardados/{tipo}/{targetId}   guardar (idempotente por naturaleza, sin Idempotency-Key)
//   D4-21 DELETE /api/v1/app/opositeca/guardados/{tipo}/{targetId}   quitar (idempotente: 204 este o no)
//   D4-24 GET    /api/v1/app/opositeca                               la OposiTeca (keyset `guardadoEn desc, id desc`)
//   D4-26 GET    /api/v1/app/opositeca/guardados/{tipo}?ids=...      cuales de hasta 50 ids estan guardados (estado inicial del corazon)
//
// Identidad = SOLO el `sub` del JWT. El acceso (puerta de especialidad + veredicto unico) y el tope los decide la base, en UNA
// funcion por comando bajo un cerrojo por usuario; Ktor valida la forma, limita por usuario y traduce errores.

internal const val SAVED_TIPO_PREGUNTA = "pregunta"
internal const val SAVED_STATE_MAX_IDS = 50

internal enum class SavedOperation(val limit: OposiBotsAuthOperation) {
    WRITE(OposiBotsAuthOperation.SAVED_WRITE),
    READ(OposiBotsAuthOperation.CATALOG_READ),
}

internal class SavedItemsRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: SavedItemsService,
    val limit: suspend (SavedOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): SavedItemsRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return SavedItemsRuntime(
                resolver = resolver,
                service = SavedItemsService(upstream),
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

// ---------- DTOs ----------

@Serializable
internal data class SavedItemDto(val tipo: String, val targetId: String, val guardadoEn: String)

@Serializable
internal data class SavedQuestionDto(val titulo: String, val examenId: String, val examenTitulo: String)

@Serializable
internal data class SavedEntryDto(
    val tipo: String,
    val targetId: String,
    val guardadoEn: String,
    val accesible: Boolean,
    /** `null` cuando el usuario ya no puede abrir ningun examen de la pregunta: su contenido no se sirve. */
    val pregunta: SavedQuestionDto? = null,
)

@Serializable
internal data class SavedPageDto(val items: List<SavedEntryDto>, val nextCursor: String? = null)

@Serializable
internal data class SavedStateDto(val tipo: String, val guardados: List<String>)

@Serializable
private data class SaveRow(@SerialName("guardado_en") val guardadoEn: String)

@Serializable
private data class StateRow(@SerialName("pregunta_id") val preguntaId: String)

@Serializable
private data class ListRow(
    val id: String,
    val tipo: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("guardado_en") val guardadoEn: String,
    val accesible: Boolean,
    val titulo: String? = null,
    @SerialName("examen_id") val examenId: String? = null,
    @SerialName("examen_titulo") val examenTitulo: String? = null,
)

internal data class SavedListQuery(val tipo: String?, val page: CursorPageRequest)

private val SAVED_LIST_PARAMS = setOf("tipo", "cursor", "limit")

internal fun parseSavedListQuery(params: io.ktor.http.Parameters): SavedListQuery {
    requireOnlyKnownQueryParams(params, SAVED_LIST_PARAMS)
    val tipo = params["tipo"]?.trim()?.also { if (it != SAVED_TIPO_PREGUNTA) throw validationError(ErrorIssue("tipo", CanonicalIssue.INVALID_FORMAT)) }
    val page = CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = CursorPageRequest.APP_DEFAULT_LIMIT,
        max = CursorPageRequest.APP_MAX_LIMIT,
        scope = CursorScope(sort = listOf("guardado_en desc", "id desc"), filters = mapOf("tipo" to tipo)),
    )
    return SavedListQuery(tipo, page)
}

/** `tipo` (cerrado) y un uuid; todo lo demas es 400 VALIDATION antes de tocar la base. */
internal fun validateSavedTarget(tipo: String, targetId: String): String {
    val issues = mutableListOf<ErrorIssue>()
    if (tipo != SAVED_TIPO_PREGUNTA) issues += ErrorIssue("tipo", CanonicalIssue.INVALID_FORMAT)
    if (!targetId.isUuid()) issues += ErrorIssue("targetId", CanonicalIssue.INVALID_FORMAT)
    if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
    return targetId.lowercase()
}

internal fun parseSavedIds(tipo: String, raw: String?): List<String> {
    val issues = mutableListOf<ErrorIssue>()
    if (tipo != SAVED_TIPO_PREGUNTA) issues += ErrorIssue("tipo", CanonicalIssue.INVALID_FORMAT)
    val ids = raw?.split(',')?.map { it.trim() }.orEmpty()
    when {
        raw == null || raw.isBlank() -> issues += ErrorIssue("ids", CanonicalIssue.REQUIRED)
        ids.size > SAVED_STATE_MAX_IDS -> issues += ErrorIssue("ids", CanonicalIssue.OUT_OF_RANGE)
        ids.any { !it.isUuid() } -> issues += ErrorIssue("ids", CanonicalIssue.INVALID_FORMAT)
    }
    if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
    return ids.map { it.lowercase() }.distinct()
}

// ---------- servicio ----------

internal class SavedItemsService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun save(userId: CanonicalUserId, tipo: String, targetId: String): SavedItemDto {
        val row = rpc<SaveRow>("app_save_item", args(userId, tipo, targetId)).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return SavedItemDto(tipo, targetId, row.guardadoEn)
    }

    suspend fun unsave(userId: CanonicalUserId, tipo: String, targetId: String) {
        val response = canonicalUpstreamIo { upstream.rpc("app_unsave_item", args(userId, tipo, targetId)) }
        if (!response.ok) throw EconomyRpcErrors.failure("app_unsave_item", response)
    }

    suspend fun state(userId: CanonicalUserId, tipo: String, ids: List<String>): SavedStateDto {
        val body = buildJsonObject {
            put("p_usuario", userId.value)
            put("p_tipo", tipo)
            put("p_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        return SavedStateDto(tipo, rpc<StateRow>("app_saved_state", body).map { it.preguntaId })
    }

    suspend fun list(userId: CanonicalUserId, query: SavedListQuery): SavedPageDto {
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val body = buildJsonObject {
            put("p_usuario", userId.value)
            put("p_tipo", query.tipo?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("p_after_ts", after?.timestamp?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("p_after_id", after?.id?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("p_limit", query.page.fetchLimit)
        }.toString()
        val rows = rpc<ListRow>("app_list_saved", body)
        val page = query.page.page(rows) { KeysetPosition(it.guardadoEn, it.id).toKeys() }
        return SavedPageDto(
            items = page.items.map { row ->
                val question = if (row.accesible && row.titulo != null && row.examenId != null) SavedQuestionDto(row.titulo, row.examenId, row.examenTitulo.orEmpty()) else null
                SavedEntryDto(row.tipo, row.targetId, row.guardadoEn, question != null, question)
            },
            nextCursor = page.nextCursor,
        )
    }

    private fun args(userId: CanonicalUserId, tipo: String, targetId: String) = buildJsonObject {
        put("p_usuario", userId.value)
        put("p_tipo", tipo)
        put("p_target", targetId)
    }.toString()

    private suspend inline fun <reified T> rpc(function: String, body: String): List<T> {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<T>()), response.body) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }
}

// ---------- rutas ----------

private val SavedIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-saved-identity")

private suspend fun ApplicationCall.savedGate(runtime: SavedItemsRuntime?, operation: SavedOperation): CanonicalUserIdentity {
    attributes.getOrNull(SavedIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(SavedIdentityKey, identity)
    return identity
}

internal fun Route.savedItemsRoutes(runtime: SavedItemsRuntime?) {
    route("/api/v1/app/opositeca") {
        get {
            val identity = call.savedGate(runtime, SavedOperation.READ)
            val query = parseSavedListQuery(call.request.queryParameters)
            call.respond(runtime!!.service.list(identity.userId, query))
        }
        route("/guardados") {
            get("/{tipo}") {
                val identity = call.savedGate(runtime, SavedOperation.READ)
                requireOnlyKnownQueryParams(call.request.queryParameters, setOf("ids"))
                val tipo = call.parameters["tipo"].orEmpty().trim()
                val ids = parseSavedIds(tipo, call.request.queryParameters["ids"])
                call.respond(runtime!!.service.state(identity.userId, tipo, ids))
            }
            put("/{tipo}/{targetId}") {
                val identity = call.savedGate(runtime, SavedOperation.WRITE)
                requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
                val tipo = call.parameters["tipo"].orEmpty().trim()
                val target = validateSavedTarget(tipo, call.parameters["targetId"].orEmpty().trim())
                call.respond(HttpStatusCode.OK, runtime!!.service.save(identity.userId, tipo, target))
            }
            delete("/{tipo}/{targetId}") {
                val identity = call.savedGate(runtime, SavedOperation.WRITE)
                requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
                val tipo = call.parameters["tipo"].orEmpty().trim()
                val target = validateSavedTarget(tipo, call.parameters["targetId"].orEmpty().trim())
                runtime!!.service.unsave(identity.userId, tipo, target)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
