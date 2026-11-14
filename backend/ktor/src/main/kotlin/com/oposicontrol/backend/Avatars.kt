package com.oposicontrol.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// =====================================================================
// FOTO DE PERFIL (decision del propietario 2026-11-08, FA42; migracion 20261108100000_v1_avatar)
//
//   D1-14 PUT    /api/v1/app/me/avatar   bytes crudos image/jpeg | image/png, 1..AVATAR_MAX_BYTES -> 200 AvatarResponse
//   D1-15 DELETE /api/v1/app/me/avatar   -> 204, idempotente
//
// Flujo: los bytes llegan AQUI (nunca a Storage directamente) -> ForoImageSanitizer (bytes magicos, MIME declarado = real, sin APNG ni
// multi-frame, dimensiones de cabecera antes de decodificar, pool aparte con tiempo maximo) -> orientacion EXIF aplicada, recorte cuadrado
// centrado, <= 512 px, JPEG SIN metadatos -> objeto `avatars/{uid}/{sha256}.jpg` del bucket PRIVADO `avatars` (mismos bytes = misma ruta)
// -> app_set_avatar guarda la ruta y devuelve la anterior (solo si es un objeto propio) -> se borra la anterior (mejor esfuerzo).
//
// Lectura (propia, ranking, foro): SOLO se firma una ruta propiedad del servidor (`avatars/{uid}/{sha256}.jpg`); una URL externa de OAuth que
// handle_new_auth_user copio a perfiles_usuario.avatar_url nunca se firma ni se reenvia. La base decide que avatar puede viajar (cuenta activa,
// ranking publico, puertas del foro) y lo entrega como `avatarPath`; Ktor lo convierte en `avatarUrl` firmada y nunca reenvia la ruta.
//
// AVATARES PREDEFINIDOS (decision del propietario 2026-11-14, FA53; migracion 20261114100000_v1_avatar_preset):
//   D1-14 PUT con `application/json` {"avatarId":1..10} -> app_set_avatar_preset (guarda el predefinido y BORRA la foto) -> 200 {"avatarId":N}.
//   Fuera de 1..10 -> 422 AVATAR_ID_INVALID. La foto (bytes crudos) solo con AVATAR_PHOTO_UPLOADS_ENABLED=true; apagada (por defecto) -> 415.
//   La base entrega `avatarId` junto a `avatarPath` (mismas puertas); un perfil tiene predefinido O foto, nunca ambos.
// =====================================================================

/** 2 MB decimales: el mismo numero que `request_body max_size 2MB` de Caddy (unidades SI) y por debajo del tope global de Ktor (2 MiB). */
internal const val AVATAR_MAX_BYTES = 2_000_000

/** FA53: los 10 avatares predefinidos (`perfiles_usuario.avatar_id`, CHECK 1..10). */
internal val AVATAR_PRESET_RANGE = 1..10

internal object AvatarPaths {
    const val BUCKET = "avatars"
    private val ANY_OWNED = Regex("^avatars/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{64})\\.jpg$")

    /** Valor de `perfiles_usuario.avatar_url` para el objeto de [userId] con contenido [sha256]. */
    fun columnValue(userId: String, sha256: String): String = "$BUCKET/${userId.lowercase()}/$sha256.jpg"

    /** Ruta del objeto DENTRO del bucket para un valor de columna propiedad del servidor; `null` para cualquier otra cosa. */
    fun objectPath(stored: String?): String? = stored?.let { ANY_OWNED.matchEntire(it) }?.let { "${it.groupValues[1]}/${it.groupValues[2]}.jpg" }

    /** Igual que [objectPath] pero exigiendo que el objeto sea de [userId] (lectura propia: `/me`). */
    fun ownObjectPath(userId: String, stored: String?): String? {
        val match = stored?.let { ANY_OWNED.matchEntire(it) } ?: return null
        return if (match.groupValues[1] == userId.lowercase()) "${match.groupValues[1]}/${match.groupValues[2]}.jpg" else null
    }
}

internal interface AvatarStorage {
    fun put(objectPath: String, bytes: ByteArray, contentType: String)
    fun delete(objectPath: String): Boolean
    fun signedUrls(objectPaths: Collection<String>, expiresInSeconds: Int): Map<String, String?>
}

internal class TransportAvatarStorage(private val transport: SupabaseRequestTransport) : AvatarStorage {
    override fun put(objectPath: String, bytes: ByteArray, contentType: String) {
        transport.storageEnsureBucket(AvatarPaths.BUCKET, public = false)   // privado: solo URLs firmadas, como el foro
        transport.storagePut(AvatarPaths.BUCKET, objectPath, bytes, contentType, upsert = true)
    }

    override fun delete(objectPath: String): Boolean = transport.storageDelete(AvatarPaths.BUCKET, objectPath)

    override fun signedUrls(objectPaths: Collection<String>, expiresInSeconds: Int): Map<String, String?> =
        transport.storageSignedUrls(AvatarPaths.BUCKET, objectPaths, expiresInSeconds)
}

internal data class SignedAvatar(val url: String, val expiresAt: Instant)

/**
 * Firma por LOTES las rutas de avatar (una peticion a Storage por respuesta). Las rutas son de contenido (sha256), asi que una URL firmada se
 * reutiliza mientras le quede al menos la mitad de su vida: un listado del foro no firma otra vez los mismos autores. Un fallo de Storage NUNCA
 * rompe la lectura: el avatar sale `null` (es decorativo).
 */
internal class AvatarUrlSigner(
    private val storage: AvatarStorage,
    private val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
    private val clock: () -> Instant = Instant::now,
) {
    private val cache = ConcurrentHashMap<String, SignedAvatar>()

    suspend fun sign(objectPaths: Collection<String>): Map<String, SignedAvatar?> {
        if (objectPaths.isEmpty()) return emptyMap()
        val now = clock()
        val fresh = HashMap<String, SignedAvatar?>()
        val missing = LinkedHashSet<String>()
        objectPaths.forEach { path ->
            val hit = cache[path]
            if (hit != null && hit.expiresAt.isAfter(now.plusSeconds(ttlSeconds / 2L))) fresh[path] = hit else missing += path
        }
        if (missing.isNotEmpty()) {
            val expiresAt = now.plusSeconds(ttlSeconds.toLong())
            val signed = try {
                withContext(Dispatchers.IO) { storage.signedUrls(missing, ttlSeconds) }
            } catch (failure: Exception) {
                AvatarLogger.warn("avatar signing failed ({} paths): {}", missing.size, failure::class.simpleName)
                emptyMap()
            }
            if (cache.size > MAX_CACHE) cache.clear()
            missing.forEach { path ->
                val url = signed[path]
                val entry = url?.let { SignedAvatar(it, expiresAt) }
                if (entry != null) cache[path] = entry
                fresh[path] = entry
            }
        }
        return fresh
    }

    suspend fun signOne(objectPath: String): SignedAvatar? = sign(listOf(objectPath))[objectPath]

    /** Lo que se borra deja de servirse desde la cache del proceso en el acto (las URLs ya entregadas mueren con el objeto). */
    fun forget(objectPath: String) {
        cache.remove(objectPath)
    }

    companion object {
        const val DEFAULT_TTL_SECONDS = 3_600
        private const val MAX_CACHE = 20_000
    }
}

/**
 * Sustituye en [element] cada clave `avatarPath` (la entrega la base) por `avatarUrl` (firmada o `null`). La ruta nunca sale de Ktor: sin
 * [signer] (Storage no configurado) o con una ruta que no es un objeto propiedad del servidor, `avatarUrl` es `null`.
 */
internal suspend fun rewriteAvatarPaths(element: JsonElement, signer: AvatarUrlSigner?): JsonElement {
    val paths = LinkedHashSet<String>()
    collectAvatarPaths(element, paths)
    val signed = if (signer != null && paths.isNotEmpty()) signer.sign(paths) else emptyMap()
    return replaceAvatarPaths(element, signed)
}

private fun collectAvatarPaths(element: JsonElement, into: MutableSet<String>) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key == AVATAR_PATH_KEY) {
                AvatarPaths.objectPath((value as? JsonPrimitive)?.takeIf { it.isString }?.content)?.let(into::add)
            } else {
                collectAvatarPaths(value, into)
            }
        }
        is JsonArray -> element.forEach { collectAvatarPaths(it, into) }
        else -> Unit
    }
}

private fun replaceAvatarPaths(element: JsonElement, signed: Map<String, SignedAvatar?>): JsonElement = when (element) {
    is JsonObject -> {
        if (element.keys.none { it == AVATAR_PATH_KEY } && element.values.none { it is JsonObject || it is JsonArray }) {
            element
        } else {
            JsonObject(
                element.entries.associate { (key, value) ->
                    if (key == AVATAR_PATH_KEY) {
                        val path = AvatarPaths.objectPath((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
                        AVATAR_URL_KEY to (path?.let { signed[it] }?.let { JsonPrimitive(it.url) } ?: JsonNull)
                    } else {
                        key to replaceAvatarPaths(value, signed)
                    }
                },
            )
        }
    }
    is JsonArray -> JsonArray(element.map { replaceAvatarPaths(it, signed) })
    else -> element
}

private const val AVATAR_PATH_KEY = "avatarPath"
private const val AVATAR_URL_KEY = "avatarUrl"

/** Puerto de los dos RPC de avatar (app_set_avatar / app_clear_avatar), con la clave de servicio. */
internal fun interface AvatarRpc {
    fun call(function: String, body: String): SupabaseRawResponse
}

internal class AvatarService(
    private val rpc: AvatarRpc,
    private val storage: AvatarStorage,
    private val signer: AvatarUrlSigner,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val sanitize: (ByteArray, String) -> SanitizedImage = { bytes, mime ->
        ForoImageSanitizer.sanitize(bytes, mime, output = ImageOutputSpec.AVATAR, maxBytes = AVATAR_MAX_BYTES)
    },
    /** FA53: la subida de foto (FA42) solo con `AVATAR_PHOTO_UPLOADS_ENABLED=true`; apagada responde 415 sin leer nada mas. */
    val photoUploadsEnabled: Boolean = false,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()   // una subida a la vez por usuario y proceso

    /** D1-14 con cuerpo JSON (FA53): elige el avatar predefinido [avatarId] (1..10) y borra la foto anterior, si era propia. */
    suspend fun choosePreset(identity: CanonicalUserIdentity, avatarId: Int): AvatarResponse {
        if (avatarId !in AVATAR_PRESET_RANGE) throw CanonicalErrorCode.AVATAR_ID_INVALID.exception()
        val userId = identity.userId.value.lowercase()
        val stored = call("app_set_avatar_preset", buildJsonObject { put("p_usuario_id", userId); put("p_avatar_id", avatarId) })
        AvatarPaths.ownObjectPath(userId, stored["previousPath"]?.jsonPrimitive?.contentOrNull)?.let { deleteBestEffort(it) }
        return AvatarResponse(avatarId = avatarId)
    }

    /** D1-14. [contentType] = cabecera `Content-Type` cruda; [body] = bytes ya leidos (la ruta acoto `Content-Length`). */
    suspend fun replace(identity: CanonicalUserIdentity, contentType: String?, body: ByteArray): AvatarResponse {
        if (!photoUploadsEnabled) throw CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE.exception()
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it == "image/jpeg" || it == "image/png" }
            ?: throw CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE.exception()
        if (body.isEmpty()) throw validationError(ErrorIssue("body", CanonicalIssue.REQUIRED))
        if (body.size > AVATAR_MAX_BYTES) throw CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
        val userId = identity.userId.value.lowercase()
        if (!inFlight.add(userId)) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 5)
        try {
            val image = when (val result = withContext(Dispatchers.IO) { sanitize(body, declared) }) {
                is SanitizedImage.Ok -> result
                is SanitizedImage.Rejected -> throw rejection(result.reason)
            }
            val column = AvatarPaths.columnValue(userId, image.sha256)
            val objectPath = AvatarPaths.objectPath(column) ?: throw CanonicalErrorCode.INTERNAL.exception()
            canonicalUpstreamIo { storage.put(objectPath, image.bytes, image.mime) }
            val stored = call("app_set_avatar", buildJsonObject { put("p_usuario_id", userId); put("p_path", column) })
            val previous = AvatarPaths.ownObjectPath(userId, stored["previousPath"]?.jsonPrimitive?.contentOrNull)
            if (previous != null && previous != objectPath) deleteBestEffort(previous)
            val signed = signer.signOne(objectPath) ?: throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
            return AvatarResponse(avatarUrl = signed.url, expiresAt = signed.expiresAt.toString())
        } finally {
            inFlight.remove(userId)
        }
    }

    /** D1-15: idempotente (sin avatar tambien es 204). */
    suspend fun delete(identity: CanonicalUserIdentity) {
        val userId = identity.userId.value.lowercase()
        val cleared = call("app_clear_avatar", buildJsonObject { put("p_usuario_id", userId) })
        AvatarPaths.ownObjectPath(userId, cleared["previousPath"]?.jsonPrimitive?.contentOrNull)?.let { deleteBestEffort(it) }
    }

    private suspend fun deleteBestEffort(objectPath: String) {
        signer.forget(objectPath)
        // el objeto anterior ya no esta referenciado: si Storage falla queda huerfano (sin URL que lo sirva), nunca rompe la respuesta
        runCatching { withContext(Dispatchers.IO) { storage.delete(objectPath) } }
            .onFailure { AvatarLogger.warn("avatar object delete failed: {}", it::class.simpleName) }
    }

    private suspend fun call(function: String, args: JsonObject): JsonObject {
        val response = canonicalUpstreamIo { rpc.call(function, args.toString()) }
        if (!response.ok) throw ProfileRpcErrors.failure(function, response)
        return try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (e: IllegalArgumentException) {
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = IllegalStateException("decode $function failed", e))
        }
    }

    private fun rejection(reason: String): BackendDomainException = when (reason) {
        "tamano" -> CanonicalErrorCode.PAYLOAD_TOO_LARGE.exception()
        "ocupado" -> CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 5)
        // formato / mime (los bytes no son lo que dice Content-Type) / animada / dimensiones / decodificacion / tiempo / codificacion
        else -> CanonicalErrorCode.IMAGE_REJECTED.exception()
    }

    companion object {
        fun fromTransport(transport: SupabaseRequestTransport, signer: AvatarUrlSigner, photoUploadsEnabled: Boolean = false): AvatarService =
            AvatarService(
                { function, body -> transport.rpcRaw(function, body) },
                TransportAvatarStorage(transport),
                signer,
                photoUploadsEnabled = photoUploadsEnabled,
            )
    }
}

private val AvatarLogger = LoggerFactory.getLogger("OC-KTOR-AVATAR")

