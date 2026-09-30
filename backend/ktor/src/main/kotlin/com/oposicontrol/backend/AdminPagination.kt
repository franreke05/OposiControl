package com.oposicontrol.backend

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/**
 * Parámetros de paginación ya saneados.
 *
 * Los valores llegan de la query string, así que no se pueden usar tal cual: un
 * `limit` enorme convertiría el endpoint en una descarga de la tabla entera, y
 * un `offset` negativo es sintaxis inválida para PostgREST. Se recortan en lugar
 * de rechazarse porque una lista es una lectura y un tope silencioso es mejor
 * respuesta que un error para quien sólo se equivocó en un número.
 */
data class AdminPageRequest private constructor(
    val limit: Int,
    val offset: Int,
) {
    companion object {
        const val DEFAULT_LIMIT = 50

        /**
         * Tope duro. Existe para que nadie pueda pedir `?limit=1000000` y arrastrar
         * la tabla completa a memoria a través del backend.
         */
        const val MAX_LIMIT = 200

        /** Tope de `offset` en los grids que validan estricto (D1-22): un offset gigante no se reenvia a PostgREST. */
        const val MAX_OFFSET = 100_000

        /**
         * D1-22: `limit`/`offset` estrictos. Ausente -> null (defecto); no entero, `limit` < 1, `offset` < 0 o
         * `offset` > [MAX_OFFSET] -> 400 VALIDATION OUT_OF_RANGE. Un `limit` por encima del tope se recorta despues en [of]
         * (misma indulgencia que el cursor). Ningun valor del cliente se repite en el mensaje.
         */
        fun parseStrict(limit: String?, offset: String?): Pair<Int?, Int?> {
            val issues = mutableListOf<ErrorIssue>()
            val parsedLimit = limit?.trim()?.toIntOrNull()?.takeIf { it >= 1 }
            if (limit != null && parsedLimit == null) issues += ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE)
            val parsedOffset = offset?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_OFFSET }
            if (offset != null && parsedOffset == null) issues += ErrorIssue("offset", CanonicalIssue.OUT_OF_RANGE)
            if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
            return parsedLimit to parsedOffset
        }

        /**
         * [maxLimit]: tope por contrato, nunca por encima de [MAX_LIMIT]
         * (`specs.pagination`: "per-contract lower max allowed").
         */
        fun of(limit: Int?, offset: Int?, maxLimit: Int = MAX_LIMIT): AdminPageRequest = AdminPageRequest(
            limit = when {
                limit == null || limit <= 0 -> DEFAULT_LIMIT.coerceAtMost(maxLimit.coerceIn(1, MAX_LIMIT))
                limit > maxLimit.coerceIn(1, MAX_LIMIT) -> maxLimit.coerceIn(1, MAX_LIMIT)
                else -> limit
            },
            offset = offset?.takeIf { it > 0 } ?: 0,
        )
    }
}

/**
 * Una página de usuarios.
 *
 * `total` es nullable a propósito: si el conteo no se puede obtener, se dice que
 * no se sabe en lugar de enviar un 0 que la UI pintaría como "no hay usuarios".
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminUsersPage(
    /** DEPRECATED: duplicado de [items] hasta que OC migre a `items` (specs.pagination). */
    @EncodeDefault val data: List<AdminV1UserDto> = emptyList(),
    val total: Int? = null,
    /** `limit` y `offset` son obligatorios en `OffsetPageEnvelope`: se emiten aunque valgan su defecto (con el Json del modulo se omitian). */
    @EncodeDefault val limit: Int = AdminPageRequest.DEFAULT_LIMIT,
    @EncodeDefault val offset: Int = 0,
    /** D1-22 (SLICE 1): AdminUserSummary. Un estado de cuenta no reconocido no se lista (nunca se presenta como activa). */
    @EncodeDefault val items: List<AdminUserSummaryDto> = emptyList(),
)

// =====================================================================
// PAGINACION CANONICA (SLICE 0, `specs.pagination`, FROZEN)
//
// CURSOR es el defecto para toda lista que crece con usuarios o tiempo y para
// todas las listas de la app. OFFSET solo para rejillas de OC que necesitan total
// y salto de pagina y ya son offset (D1-22, D2-20, D2-35, D2-37, D2-51, D2-55,
// D2-61; luego D7-17, D7-21): [OffsetPage] + [AdminPageRequest].
// =====================================================================

/** `{items![], nextCursor?}`. `nextCursor` ausente o null = ultima pagina. Sin total. */
@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

/** `{items![], total?, limit!, offset!}`. `total` null si el conteo fallo: nunca un 0 falso. */
@Serializable
data class OffsetPage<T>(
    val items: List<T>,
    val total: Int? = null,
    val limit: Int,
    val offset: Int,
)

/**
 * Lo que identifica una LISTA para el cursor: las claves de orden (el `id`
 * desempate va el ultimo) y los filtros efectivos. Su huella va dentro del
 * cursor (`f`): un cursor emitido para otros filtros u otro orden es
 * 400 INVALID_CURSOR, no una pagina de otra lista.
 *
 * Los filtros de propietario/autorizacion NO van aqui: se reaplican en cada
 * peticion desde el JWT, el cursor no los transporta.
 */
data class CursorScope(
    val sort: List<String>,
    val filters: Map<String, String?> = emptyMap(),
) {
    /** Primeros 16 hex de sha256(filtros normalizados + orden). Un filtro null = ausente. */
    fun fingerprint(): String {
        val normalizedFilters = filters.entries
            .filter { it.value != null }
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val normalized = "sort=${sort.joinToString(",")}\n$normalizedFilters"
        return sha256Hex(normalized.toByteArray(Charsets.UTF_8)).take(CURSOR_FINGERPRINT_HEX_LENGTH)
    }
}

/**
 * Peticion de pagina por cursor ya saneada. [after] son los valores de las
 * claves de orden de la ultima fila vista (+ id), o null en la primera pagina.
 * Se piden [fetchLimit] = limit + 1 filas para saber si hay siguiente pagina.
 */
data class CursorPageRequest internal constructor(
    val limit: Int,
    val after: List<JsonElement>?,
    val scope: CursorScope,
) {
    val fetchLimit: Int get() = limit + 1

    /**
     * Recorta [rows] (pedidas con [fetchLimit]) a [limit] y emite `nextCursor`
     * con las claves de la ultima fila devuelta si habia mas.
     */
    fun <T> page(rows: List<T>, keyOf: (T) -> List<JsonElement>): CursorPage<T> {
        if (rows.size <= limit) return CursorPage(items = rows, nextCursor = null)
        val items = rows.take(limit)
        return CursorPage(items = items, nextCursor = PageCursorCodec.encode(keyOf(items.last()), scope.fingerprint()))
    }

    companion object {
        const val APP_DEFAULT_LIMIT = 20
        const val APP_MAX_LIMIT = 50
        const val ADMIN_DEFAULT_LIMIT = 50
        const val ADMIN_MAX_LIMIT = 100

        /**
         * `limit` ausente -> [default]; no entero o < 1 -> 400 VALIDATION
         * (OUT_OF_RANGE); > [max] -> recortado a [max] (la misma indulgencia que
         * [AdminPageRequest]). Cursor indescifrable, `v` distinto o huella que no
         * casa con [scope] -> 400 INVALID_CURSOR.
         */
        fun of(
            cursor: String?,
            limit: String?,
            default: Int,
            max: Int,
            scope: CursorScope,
        ): CursorPageRequest {
            require(default in 1..max) { "default fuera de 1..max" }
            val parsedLimit = when {
                limit.isNullOrBlank() -> default
                else -> limit.trim().toIntOrNull()
                    ?.takeIf { it >= 1 }
                    ?.coerceAtMost(max)
                    ?: throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE))
            }
            val after = cursor?.takeIf { it.isNotEmpty() }?.let { PageCursorCodec.decode(it, scope.fingerprint()) }
            return CursorPageRequest(parsedLimit, after, scope)
        }
    }
}

/**
 * Cursor opaco: base64url(JSON `{v:1, k:[claves..., id], f:huella}`).
 *
 * NO va firmado a proposito: solo lleva claves de orden, y los filtros de
 * propietario y autorizacion se reaplican en cada peticion. Manipularlo solo
 * puede mover la ventana dentro de lo que el llamante ya puede ver.
 */
object PageCursorCodec {
    const val VERSION = 1
    private const val MAX_CURSOR_LENGTH = 2048
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(keys: List<JsonElement>, fingerprint: String): String {
        require(keys.isNotEmpty()) { "un cursor necesita al menos el id" }
        val payload = buildJsonObject {
            put("v", VERSION)
            put("k", JsonArray(keys))
            put("f", fingerprint)
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    /** Las claves del cursor, o 400 INVALID_CURSOR. Nunca otro error. */
    fun decode(raw: String, expectedFingerprint: String): List<JsonElement> {
        val invalid = { CanonicalErrorCode.INVALID_CURSOR.exception() }
        if (raw.length > MAX_CURSOR_LENGTH) throw invalid()
        val payload = try {
            val bytes = Base64.getUrlDecoder().decode(raw.trimEnd('='))
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw invalid()
        val version = (payload["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        val keys = payload["k"] as? JsonArray
        val fingerprint = (payload["f"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version != VERSION || keys == null || keys.isEmpty() || fingerprint != expectedFingerprint) throw invalid()
        if (keys.any { it !is JsonPrimitive }) throw invalid()
        return keys.toList()
    }
}

private const val CURSOR_FINGERPRINT_HEX_LENGTH = 16

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
