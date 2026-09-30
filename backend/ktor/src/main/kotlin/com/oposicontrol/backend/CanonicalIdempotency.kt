package com.oposicontrol.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

// =====================================================================
// IDEMPOTENCIA CANONICA (SLICE 0, `specs.idempotency`, FROZEN)
//
// Cabecera `Idempotency-Key` (UUID). Unicidad (actor, scope, clave); actor =
// JWT sub; scope = "<METHOD> <route template>"; huella = sha256 del metodo, la
// plantilla, los path params ordenados y el cuerpo JSON canonico (RFC 8785).
// Un plugin de RUTA envuelve solo las rutas que lo declaran; la logica de
// negocio sigue en su servicio canonico. Solo se guardan y repiten respuestas
// 2xx; un fallo libera la clave para poder reintentar con la misma.
//
// El almacen nunca es la unica guarda: cada comando respaldado por el es
// idempotente tambien en su propia RPC (indice unico, idempotency_key del
// libro de tickets, restricciones unicas). Por eso tomar una fila STALE y
// volver a ejecutar es seguro.
// =====================================================================

object IdempotencyHeaders {
    const val KEY = "Idempotency-Key"
    const val REPLAYED = "Idempotent-Replayed"
}

/** Una `Idempotency-Key` valida, siempre en minusculas. */
@JvmInline
value class IdempotencyKey private constructor(val value: String) {
    companion object {
        /**
         * Ausente y [required] -> 400 IDEMPOTENCY_KEY_REQUIRED; ausente y opcional
         * -> null; presente y no UUID -> 400 IDEMPOTENCY_KEY_INVALID.
         */
        fun parse(raw: String?, required: Boolean): IdempotencyKey? {
            if (raw == null || raw.isEmpty()) {
                if (required) throw CanonicalErrorCode.IDEMPOTENCY_KEY_REQUIRED.exception()
                return null
            }
            if (!raw.isUuid()) throw CanonicalErrorCode.IDEMPOTENCY_KEY_INVALID.exception()
            return IdempotencyKey(raw.lowercase())
        }
    }
}

/** La huella de la peticion: lo que distingue "misma peticion" de "otra con la misma clave". */
object RequestFingerprint {

    /**
     * sha256 hex de `METHOD \n template \n k=v&k=v (ordenados) \n cuerpo canonico`.
     * Las cabeceras quedan fuera.
     */
    fun of(method: String, routeTemplate: String, pathParams: Map<String, String>, canonicalBody: String): String {
        val params = pathParams.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val material = listOf(method.uppercase(), routeTemplate, params, canonicalBody).joinToString("\n")
        return sha256Hex(material.toByteArray(Charsets.UTF_8))
    }

    /**
     * Cuerpo JSON en forma canonica RFC 8785 (JCS). Cuerpo vacio = "". Un cuerpo
     * que no es JSON es 400 MALFORMED_BODY.
     */
    fun canonicalJsonBody(rawBody: ByteArray): String {
        if (rawBody.isEmpty()) return ""
        val text = String(rawBody, Charsets.UTF_8)
        if (text.isBlank()) return ""
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        }
        return JsonCanonicalizer.canonicalize(element)
    }

    /** Multipart: nombres de las partes en orden + sha256 de cada parte. */
    fun multipartBody(parts: List<Pair<String, ByteArray>>): String =
        parts.joinToString("\n") { (name, bytes) -> "$name:${sha256Hex(bytes)}" }
}

/**
 * RFC 8785 (JSON Canonicalization Scheme): claves ordenadas por unidades UTF-16,
 * sin espacios, cadenas con el escape minimo de ECMAScript y numeros en el
 * formato de `Number.prototype.toString` (IEEE-754 double).
 */
object JsonCanonicalizer {
    fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                // `compareTo` de String compara unidades UTF-16: exactamente lo que pide JCS.
                element.entries.sortedWith { a, b -> a.key.compareTo(b.key) }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    write(value, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(element.content, out)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(formatNumber(element.content))
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    /** ECMAScript Number::toString sobre el double (JDK 19+ ya da el mas corto que redondea igual). */
    internal fun formatNumber(literal: String): String {
        val value = literal.toDoubleOrNull() ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        if (value.isNaN() || value.isInfinite()) throw CanonicalErrorCode.MALFORMED_BODY.exception()
        if (value == 0.0) return "0"
        val shortest = BigDecimal(java.lang.Double.toString(Math.abs(value))).stripTrailingZeros()
        val digits = shortest.unscaledValue().toString()
        val k = digits.length
        // n: posicion del punto decimal respecto al primer digito (valor = 0.d1d2... x 10^n).
        val n = k - shortest.scale()
        val sign = if (value < 0) "-" else ""
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (exponent >= 0) "+" else "-") + Math.abs(exponent)
            }
        }
        return sign + body
    }
}

// ---------------------------------------------------------------------
// Almacen
// ---------------------------------------------------------------------

/** La respuesta 2xx guardada para repetirla. Solo cabeceras `Location` y `Content-Type`. */
data class StoredIdempotentResponse(
    val status: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
)

data class IdempotencyBeginRequest(
    val actorId: String,
    val scope: String,
    val key: IdempotencyKey,
    val fingerprint: String,
    val ttl: Duration = IDEMPOTENCY_DEFAULT_TTL,
    val lease: Duration = IDEMPOTENCY_DEFAULT_LEASE,
)

val IDEMPOTENCY_DEFAULT_TTL: Duration = Duration.ofHours(24)
val IDEMPOTENCY_DEFAULT_LEASE: Duration = Duration.ofSeconds(30)

/**
 * Un claim sobre una fila: su id y el token de fencing (`claim_token`). El token
 * rota en cada claim (NEW y STALE), asi que un dueno lento cuyo lease fue tomado
 * ya no puede completar ni liberar la fila del nuevo dueno.
 */
data class IdempotencyClaim(val id: String, val token: String)

/** Resultado de `idem_begin`. */
sealed interface IdempotencyBeginResult {
    /** NEW (fila insertada) o STALE (lease caducado, misma huella, tomada): ejecutar. */
    data class Claimed(val claim: IdempotencyClaim, val takeover: Boolean) : IdempotencyBeginResult {
        val id: String get() = claim.id
    }

    /** Completada con la misma huella: devolver lo guardado sin ejecutar. */
    data class Replay(val response: StoredIdempotentResponse) : IdempotencyBeginResult

    /** Otra huella con la misma clave (cualquier estado): 422. */
    data object Mismatch : IdempotencyBeginResult

    /** Misma huella y lease vigente: 409 + Retry-After: 1. */
    data object InProgress : IdempotencyBeginResult
}

/** El almacen de claves. Las tres operaciones son las tres RPC de la migracion. */
interface IdempotencyStore {
    /** Atomico: una sola decision por (actor, scope, clave) aunque lleguen N a la vez. */
    suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult

    /** 2xx: guarda estado, cuerpo y cabeceras (state=completed). No-op si el token ya no es el vigente. */
    suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse)

    /** No 2xx o excepcion: borra la fila; se puede reintentar con la misma clave. No-op si el token ya no es el vigente. */
    suspend fun release(claim: IdempotencyClaim)
}

/**
 * Almacen real: las RPC `idem_begin`, `idem_complete` e `idem_release` de la
 * migracion de SLICE 0 (SECURITY DEFINER, solo `service_role`), llamadas con la
 * clave de servicio del [SupabaseRequestTransport].
 *
 * Contrato de las RPC (migracion `20260928100100_idempotency_keys.sql`):
 * - `idem_begin(p_actor_id uuid, p_scope text, p_idem_key text,
 *   p_request_fingerprint text, p_ttl interval, p_lease interval)` -> tabla de una
 *   fila `{outcome: NEW|REPLAY|MISMATCH|IN_PROGRESS|STALE, record_id,
 *   stored_status, stored_body, stored_headers, retry_after_seconds, claim_token}`.
 * - `idem_complete(p_record_id uuid, p_claim_token uuid, p_status integer, p_body jsonb, p_headers jsonb)`.
 * - `idem_release(p_record_id uuid, p_claim_token uuid)`.
 */
class PostgrestIdempotencyStore(
    private val transport: SupabaseRequestTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IdempotencyStore {

    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult {
        val body = buildJsonObject {
            put("p_actor_id", request.actorId)
            put("p_scope", request.scope)
            put("p_idem_key", request.key.value)
            put("p_request_fingerprint", request.fingerprint)
            put("p_ttl", "${request.ttl.seconds} seconds")
            put("p_lease", "${request.lease.seconds} seconds")
        }
        val raw = withUpstreamIo { transport.rpc("idem_begin", body.toString()) }
        return parseBegin(raw)
    }

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) {
        val body = buildJsonObject {
            put("p_record_id", claim.id)
            put("p_claim_token", claim.token)
            put("p_status", response.status)
            put("p_body", response.body?.let(::bodyAsJson) ?: JsonNull)
            put("p_headers", JsonObject(response.headers.mapValues { JsonPrimitive(it.value) }))
        }
        val raw = withUpstreamIo { transport.rpc("idem_complete", body.toString()) }
        warnIfFencedNoOp("idem_complete", claim, raw)
    }

    override suspend fun release(claim: IdempotencyClaim) {
        val body = buildJsonObject {
            put("p_record_id", claim.id)
            put("p_claim_token", claim.token)
        }
        val raw = withUpstreamIo { transport.rpc("idem_release", body.toString()) }
        warnIfFencedNoOp("idem_release", claim, raw)
    }

    /** Acepta objeto o array de una fila (funcion que devuelve tabla). */
    internal fun parseBegin(raw: String): IdempotencyBeginResult {
        val element = json.parseToJsonElement(raw)
        val row = when (element) {
            is JsonArray -> element.singleOrNull()?.jsonObject
            is JsonObject -> element
            else -> null
        } ?: throw SerializationException("idem_begin devolvio una forma inesperada")
        val outcome = row["outcome"]?.jsonPrimitive?.contentOrNull?.uppercase()
        val id = row["record_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        val token = row["claim_token"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        fun claim(label: String) = IdempotencyClaim(
            id = id ?: throw SerializationException("$label sin id"),
            token = token ?: throw SerializationException("$label sin claim_token"),
        )
        return when (outcome) {
            "NEW" -> IdempotencyBeginResult.Claimed(claim("NEW"), takeover = false)
            "STALE" -> IdempotencyBeginResult.Claimed(claim("STALE"), takeover = true)
            "MISMATCH" -> IdempotencyBeginResult.Mismatch
            "IN_PROGRESS" -> IdempotencyBeginResult.InProgress
            "REPLAY" -> {
                val status = row["stored_status"]?.jsonPrimitive?.intOrNull
                    ?: throw SerializationException("REPLAY sin stored_status")
                val storedBody = row["stored_body"]?.takeIf { it !is JsonNull }
                val headers = (row["stored_headers"] as? JsonObject)
                    ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
                    ?.toMap()
                    .orEmpty()
                IdempotencyBeginResult.Replay(
                    StoredIdempotentResponse(
                        status = status,
                        body = storedBody?.let { if (it is JsonPrimitive && it.isString && !headers.isJsonContent()) it.content else it.toString() },
                        headers = headers,
                    ),
                )
            }
            else -> throw SerializationException("idem_begin devolvio un outcome desconocido")
        }
    }

    /** Un cuerpo JSON va como jsonb tal cual; uno que no lo es, como cadena JSON. */
    private fun bodyAsJson(body: String): JsonElement =
        runCatching { json.parseToJsonElement(body) }.getOrElse { JsonPrimitive(body) }

    private fun Map<String, String>.isJsonContent(): Boolean =
        entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.value?.contains("json", ignoreCase = true) ?: true

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): PostgrestIdempotencyStore? =
            SupabaseRequestTransport.fromEnvironment(environment)?.let(::PostgrestIdempotencyStore)
    }
}

// ---------------------------------------------------------------------
// Cuerpo crudo de la peticion (una sola lectura)
// ---------------------------------------------------------------------

/** Tope del cuerpo de un comando canonico JSON. Mas grande -> 413. */
const val CANONICAL_COMMAND_MAX_BYTES: Long = 1L * 1024 * 1024

private val CanonicalRawBodyKey = AttributeKey<ByteArray>("canonical-raw-body")

/**
 * El cuerpo crudo, leido UNA vez y cacheado en la llamada: el plugin de
 * idempotencia lo necesita para la huella y el handler para decodificarlo. Por
 * eso los comandos canonicos decodifican con `receiveCanonicalCommand` y no con
 * `receive`, que encontraria el canal ya consumido.
 */
suspend fun ApplicationCall.canonicalRawBody(maxBytes: Long = CANONICAL_COMMAND_MAX_BYTES): ByteArray {
    attributes.getOrNull(CanonicalRawBodyKey)?.let { return it }
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) throw CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
    attributes.put(CanonicalRawBodyKey, bytes)
    return bytes
}

// ---------------------------------------------------------------------
// Plugin de ruta
// ---------------------------------------------------------------------

class CanonicalIdempotencyConfig {
    /** Plantilla de ruta canonica, p. ej. `/api/v1/app/examenes/{examId}/intentos`. Obligatoria. */
    var routeTemplate: String = ""

    /** `!` en el contrato: la cabecera es obligatoria. */
    var required: Boolean = true

    lateinit var store: IdempotencyStore

    /** Actor = JWT sub (uuid). Lanza 401 si no hay identidad. */
    var actor: suspend (ApplicationCall) -> String = {
        throw CanonicalErrorCode.UNAUTHENTICATED.exception()
    }

    /** Forma canonica del cuerpo para la huella. Por defecto JSON (JCS). */
    var canonicalBody: suspend (ApplicationCall) -> String = { call ->
        RequestFingerprint.canonicalJsonBody(call.canonicalRawBody())
    }

    var ttl: Duration = IDEMPOTENCY_DEFAULT_TTL
    var lease: Duration = IDEMPOTENCY_DEFAULT_LEASE
}

private val IdempotencyClaimKey = AttributeKey<IdempotencyClaim>("canonical-idempotency-claim")
internal val IdempotencyFinishedKey = AttributeKey<Boolean>("canonical-idempotency-finished")
private val IdempotencyLogger = LoggerFactory.getLogger("com.oposicontrol.backend.Idempotency")

/**
 * PF2-2-04: `idem_complete` / `idem_release` devuelven `false` cuando la fila ya
 * no es nuestra (claim_token rotado por un STALE takeover, fila expirada o ya
 * completada). Es un no-op correcto por diseno (fencing), pero no debe ser
 * silencioso: se registra sin contenido de la peticion. Devuelve `true` si fue no-op.
 */
internal fun warnIfFencedNoOp(function: String, claim: IdempotencyClaim, rawResult: String): Boolean {
    if (rawResult.trim() != "false") return false
    IdempotencyLogger.warn("{} was a fenced no-op (record {} no longer owned by this claim)", function, claim.id)
    return true
}

private val TEMPLATE_PARAM = Regex("\\{([A-Za-z0-9_]+)}")

/**
 * El plugin que declara una ruta de comando con `Idempotency-Key`.
 *
 * ```
 * post("/api/v1/app/examenes/{examId}/intentos") { ... }.install(CanonicalIdempotency) {
 *     routeTemplate = "/api/v1/app/examenes/{examId}/intentos"; store = ...; actor = { ... }
 * }
 * ```
 *
 * NEW/STALE -> ejecuta el handler; REPLAY -> responde lo guardado con
 * `Idempotent-Replayed: true` sin ejecutar; MISMATCH -> 422
 * IDEMPOTENCY_KEY_REUSED; IN_PROGRESS -> 409 IDEMPOTENCY_KEY_IN_PROGRESS +
 * `Retry-After: 1`. Tras ejecutar: 2xx -> `complete`; cualquier otra cosa ->
 * `release`.
 */
val CanonicalIdempotency = createRouteScopedPlugin("CanonicalIdempotency", ::CanonicalIdempotencyConfig) {
    val config = pluginConfig
    require(config.routeTemplate.startsWith("/")) { "CanonicalIdempotency necesita routeTemplate" }
    val paramNames = TEMPLATE_PARAM.findAll(config.routeTemplate).map { it.groupValues[1] }.toList()

    onCall { call ->
        val key = IdempotencyKey.parse(call.request.headers[IdempotencyHeaders.KEY], config.required) ?: return@onCall
        val actorId = config.actor(call)
        val method = call.request.httpMethod.value.uppercase()
        val pathParams = paramNames.associateWith { call.parameters[it].orEmpty() }
        val fingerprint = RequestFingerprint.of(method, config.routeTemplate, pathParams, config.canonicalBody(call))
        val request = IdempotencyBeginRequest(
            actorId = actorId,
            scope = "$method ${config.routeTemplate}",
            key = key,
            fingerprint = fingerprint,
            ttl = config.ttl,
            lease = config.lease,
        )
        when (val result = config.store.begin(request)) {
            is IdempotencyBeginResult.Claimed -> call.attributes.put(IdempotencyClaimKey, result.claim)
            is IdempotencyBeginResult.Replay -> {
                call.attributes.put(IdempotencyFinishedKey, true)
                call.respondStoredIdempotent(result.response)
            }
            IdempotencyBeginResult.Mismatch -> throw CanonicalErrorCode.IDEMPOTENCY_KEY_REUSED.exception()
            IdempotencyBeginResult.InProgress ->
                throw CanonicalErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS.exception(retryAfterSeconds = 1)
        }
    }

    // Una excepcion del handler la responde el StatusPages de la APLICACION,
    // fuera del pipeline de esta ruta: aqui se libera la clave ANTES de que salga
    // ese error, para que un reintento inmediato con la misma clave ejecute.
    // MASTER3-IDEM-001: la finalizacion (release/complete) corre en NonCancellable -- si el cliente cierra la conexion
    // la corrutina de la llamada esta cancelada y el almacen ni se llegaba a tocar (la CancellationException se tragaba
    // como un fallo cualquiera), dejando la clave `in_progress` hasta que caducara el lease (409 al reintentar). La marca
    // de "terminado" se pone SOLO si el almacen respondio: si falla, la red de seguridad de ResponseSent vuelve a liberar.
    on(CallFailed) { call, _ ->
        val claim = call.attributes.getOrNull(IdempotencyClaimKey) ?: return@on
        if (call.attributes.getOrNull(IdempotencyFinishedKey) == true) return@on
        finalizeIdempotencyClaim(call.attributes, call.requestIdOrNull(), "release") { config.store.release(claim) }
    }

    on(ResponseBodyReadyForSend) { call, content ->
        val claim = call.attributes.getOrNull(IdempotencyClaimKey) ?: return@on
        if (call.attributes.getOrNull(IdempotencyFinishedKey) == true) return@on
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        // No 2xx, o contenido que no se puede guardar (MASTER-IDEM-003): fail-closed, se libera el claim para que
        // el reintento re-ejecute en vez de "reproducir" un cuerpo vacio.
        // La respuesta ya esta decidida; si el almacen falla, la red de seguridad libera y, si tampoco puede, la fila
        // queda in_progress hasta que caduque el lease (STALE); la RPC de negocio, idempotente por si misma, cubre la
        // re-ejecucion.
        finalizeIdempotencyClaim(call.attributes, call.requestIdOrNull(), "finalize") {
            val stored = if (status.value in 200..299) content.toStoredResponse(status, call) else null
            if (stored != null) config.store.complete(claim, stored) else config.store.release(claim)
        }
    }

    on(ResponseSent) { call ->
        val claim = call.attributes.getOrNull(IdempotencyClaimKey) ?: return@on
        if (call.attributes.getOrNull(IdempotencyFinishedKey) == true) return@on
        call.attributes.put(IdempotencyFinishedKey, true)
        // Red de seguridad (la respuesta salio sin pasar por el hook de arriba):
        // liberar en segundo plano; si falla, el lease caduca y la fila es STALE.
        call.application.launch { runCatching { config.store.release(claim) } }
    }
}

/**
 * MASTER3-IDEM-001: ejecuta [action] contra el almacen sin que la cancelacion de la llamada lo impida
 * ([NonCancellable]), marca el claim como terminado SOLO si el almacen respondio, y relanza una cancelacion en vez de
 * tragarla (tras haber finalizado). Un fallo del almacen se registra y deja la marca sin poner (red de seguridad).
 */
internal suspend fun finalizeIdempotencyClaim(
    attributes: Attributes,
    requestId: String?,
    operation: String,
    action: suspend () -> Unit,
) {
    val failure: Exception? = withContext(NonCancellable) {
        try {
            action()
            null
        } catch (failure: Exception) {
            failure
        }
    }
    if (failure == null) {
        attributes.put(IdempotencyFinishedKey, true)
    } else {
        IdempotencyLogger.warn(
            "idempotency {} failed (requestId={}): {}",
            operation,
            requestId,
            failure.redactedMessage("idempotency store failure"),
        )
    }
    // La llamada pudo cancelarse mientras se finalizaba: se propaga la cancelacion (nunca se traga).
    currentCoroutineContext().ensureActive()
}

/**
 * `null` = contenido que NO se puede reproducir fielmente (stream / WriteChannelContent, o un cuerpo JSON literal
 * `null`, que la RPC guardaria como SQL NULL): el llamador libera el claim en vez de completarlo con cuerpo vacio.
 */
internal fun OutgoingContent.toStoredResponse(status: HttpStatusCode, call: ApplicationCall): StoredIdempotentResponse? {
    val body = when (this) {
        is TextContent -> text.takeUnless { it.trim() == "null" } ?: return null
        is ByteArrayContent -> String(bytes(), Charsets.UTF_8).takeUnless { it.trim() == "null" } ?: return null
        is OutgoingContent.NoContent -> null
        else -> return null
    }
    val headers = buildMap {
        call.response.headers[HttpHeaders.Location]?.let { put(HttpHeaders.Location, it) }
        (contentType ?: call.response.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
            ?.let { put(HttpHeaders.ContentType, it.toString()) }
    }
    return StoredIdempotentResponse(status.value, body, headers)
}

private suspend fun ApplicationCall.respondStoredIdempotent(stored: StoredIdempotentResponse) {
    response.header(IdempotencyHeaders.REPLAYED, "true")
    stored.headers[HttpHeaders.Location]?.let { response.header(HttpHeaders.Location, it) }
    val status = HttpStatusCode.fromValue(stored.status)
    val body = stored.body
    if (body == null) {
        respond(status)
    } else {
        val contentType = stored.headers[HttpHeaders.ContentType]?.let(ContentType::parse) ?: ContentType.Application.Json
        respond(TextContent(body, contentType, status))
    }
}

/** El scope de una ruta, por si un servicio necesita pasarlo a su propia RPC. */
fun idempotencyScope(method: String, routeTemplate: String): String = "${method.uppercase()} $routeTemplate"

/** Para un servicio que pase la clave a su RPC de negocio (patron WALLET_IDEMPOTENCY_*). */
fun ApplicationCall.idempotencyKeyOrNull(): IdempotencyKey? =
    IdempotencyKey.parse(request.headers[IdempotencyHeaders.KEY], required = false)

/** Tipo de contenido del cuerpo: JSON obligatorio para comandos canonicos. */
internal fun ApplicationCall.requireJsonContentIfBody(body: ByteArray) {
    if (body.isEmpty()) return
    val type = request.contentType()
    if (!type.match(ContentType.Application.Json)) throw CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE.exception()
}
