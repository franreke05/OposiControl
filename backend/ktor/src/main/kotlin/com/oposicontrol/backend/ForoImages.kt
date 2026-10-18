package com.oposicontrol.backend

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// V1 wave 5b (E1-V1-W5-FORO-CONTRACT.md, M-F7/M-F8, N-F1): imagenes del foro.
//
// Flujo: init (fila `subida`, ruta generada por el servidor) -> el cliente SUBE LOS BYTES AQUI (no a Storage: el original nunca llega a Storage) -> se comprueban tamano y BYTES MAGICOS (no el MIME ni la extension),
// la cabecera da las dimensiones ANTES de decodificar, se rechazan animadas y bombas de descompresion, se decodifica en un pool aparte con tiempo maximo y se RECODIFICA (fuera EXIF/GPS/ICC/miniaturas/polyglots)
// -> el objeto recodificado va al bucket PRIVADO -> `verificada`. Lectura: URL firmada corta DESPUES de la puerta de visibilidad de la publicacion; nunca se guarda ni se registra.
// Solo JPEG y PNG en V1: sin decodificador WebP en el JDK no se puede recodificar (y no se acepta lo que no se puede recodificar).

internal sealed interface SanitizedImage {
    class Ok(val bytes: ByteArray, val mime: String, val width: Int, val height: Int, val sha256: String) : SanitizedImage
    class Rejected(val reason: String) : SanitizedImage
}

internal object ForoImageSanitizer {
    const val MAX_BYTES = 3 * 1024 * 1024
    const val MAX_PIXELS = 12_000_000L
    const val MAX_SIDE = 8_000
    const val OUT_MAX_SIDE = 1_600
    const val DECODE_TIMEOUT_MS = 5_000L

    // pool APARTE de los hilos de peticion; dos decodificaciones a la vez como mucho en todo el proceso
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "foro-image-decode").also { it.isDaemon = true } }
    private val permits = Semaphore(2)

    fun sniff(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "image/png"
        else -> null
    }

    /** APNG: un chunk `acTL` antes del primer `IDAT` marca una animacion. */
    private fun isAnimatedPng(b: ByteArray): Boolean {
        var i = 8
        while (i + 8 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "acTL") return true
            if (type == "IDAT" || type == "IEND" || len < 0) return false
            i += 12 + len
        }
        return false
    }

    fun sanitize(input: ByteArray, declaredMime: String, timeoutMs: Long = DECODE_TIMEOUT_MS): SanitizedImage {
        if (input.isEmpty() || input.size > MAX_BYTES) return SanitizedImage.Rejected("tamano")
        val sniffed = sniff(input) ?: return SanitizedImage.Rejected("formato")
        if (sniffed != declaredMime) return SanitizedImage.Rejected("mime")
        if (sniffed == "image/png" && isAnimatedPng(input)) return SanitizedImage.Rejected("animada")
        if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return SanitizedImage.Rejected("ocupado")
        try {
            val future = pool.submit<SanitizedImage> { decodeAndReencode(input, sniffed) }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                SanitizedImage.Rejected("tiempo")
            } catch (e: ExecutionException) {
                SanitizedImage.Rejected("decodificacion")
            }
        } finally {
            permits.release()
        }
    }

    private fun decodeAndReencode(input: ByteArray, mime: String): SanitizedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return SanitizedImage.Rejected("formato")
            val reader = readers.next()
            try {
                reader.setInput(stream, true, true)
                // dimensiones de la CABECERA, antes de decodificar nada
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) return SanitizedImage.Rejected("dimensiones")
                if (reader.getNumImages(false) > 1) return SanitizedImage.Rejected("animada")
                val decoded = reader.read(0)
                val scale = minOf(1.0, OUT_MAX_SIDE.toDouble() / maxOf(w, h))
                val ow = maxOf(1, (w * scale).toInt())
                val oh = maxOf(1, (h * scale).toInt())
                val png = mime == "image/png"
                val out = BufferedImage(ow, oh, if (png) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                val g = out.createGraphics()
                try {
                    if (!png) { g.color = java.awt.Color.WHITE; g.fillRect(0, 0, ow, oh) }
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(decoded, 0, 0, ow, oh, null)
                } finally {
                    g.dispose()
                }
                val bytes = ByteArrayOutputStream().use { sink ->
                    val writers = ImageIO.getImageWritersByMIMEType(mime)
                    if (!writers.hasNext()) return SanitizedImage.Rejected("codificacion")
                    val writer = writers.next()
                    try {
                        ImageIO.createImageOutputStream(sink).use { ios ->
                            writer.output = ios
                            val param = writer.defaultWriteParam
                            if (!png && param.canWriteCompressed()) {
                                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                                param.compressionQuality = 0.85f
                            }
                            writer.write(null, IIOImage(out, null, null), param)   // sin metadatos: EXIF, GPS, ICC y miniaturas no sobreviven
                        }
                    } finally {
                        writer.dispose()
                    }
                    sink.toByteArray()
                }
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return SanitizedImage.Rejected("tamano")
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return SanitizedImage.Ok(bytes, mime, ow, oh, sha)
            } finally {
                reader.dispose()
            }
        }
    }
}

internal interface ForoImageStorage {
    fun put(path: String, bytes: ByteArray, contentType: String)
    fun delete(path: String): Boolean
    fun signedUrl(path: String, expiresInSeconds: Int): String
}

internal class TransportForoImageStorage(private val transport: SupabaseRequestTransport) : ForoImageStorage {
    private val bucket = BUCKET

    override fun put(path: String, bytes: ByteArray, contentType: String) {
        transport.storageEnsureBucket(bucket, public = false)   // privado: la visibilidad de un bucket es una decision, nunca un default
        transport.storagePut(bucket, path, bytes, contentType, upsert = true)
    }

    override fun delete(path: String): Boolean = transport.storageDelete(bucket, path)

    override fun signedUrl(path: String, expiresInSeconds: Int): String = transport.storageSignedUrl(bucket, path, expiresInSeconds)

    companion object {
        const val BUCKET = "oposired-imagenes"
    }
}

internal class ForoImageService(
    private val upstream: EconomyUpstream,
    private val storage: ForoImageStorage,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()   // 1 trabajo de imagen a la vez por usuario

    suspend fun init(user: CanonicalUserId, mime: String, bytes: Int): JsonElement {
        if (mime != "image/jpeg" && mime != "image/png") throw CanonicalErrorCode.IMAGE_REJECTED.exception()   // also keeps a NUL or any odd text out of the database
        val r = rpc("app_foro_image_init", buildJsonObject { put("p_usuario", user.value); put("p_mime", mime); put("p_bytes", bytes) }).jsonObject
        return buildJsonObject {
            put("imagenId", r["imagenId"] ?: JsonNull)
            put("mime", mime)
            put("expiraEn", clock().plusSeconds(600).toString())
        }
    }

    suspend fun upload(user: CanonicalUserId, imageId: String, contentType: String?, body: ByteArray): JsonElement {
        if (!imageId.isUuid()) throw validationError(ErrorIssue("id", CanonicalIssue.INVALID_FORMAT))
        val id = imageId.lowercase()
        val row = rpc("app_foro_image_status", buildJsonObject { put("p_usuario", user.value); put("p_id", id) }).jsonObject
        if (row["estado"]?.jsonPrimitive?.contentOrNull != "subida") throw CanonicalErrorCode.NOT_FOUND.exception()
        val mime = row["mime"]!!.jsonPrimitive.content
        val path = row["path"]!!.jsonPrimitive.content
        if (contentType?.substringBefore(';')?.trim()?.lowercase() != mime) throw CanonicalErrorCode.IMAGE_REJECTED.exception()
        if (!inFlight.add(user.value)) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 5)
        try {
            when (val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ForoImageSanitizer.sanitize(body, mime) }) {
                is SanitizedImage.Rejected -> {
                    rpc("app_foro_image_reject", buildJsonObject { put("p_usuario", user.value); put("p_id", id); put("p_motivo", result.reason) })
                    throw CanonicalErrorCode.IMAGE_REJECTED.exception()
                }
                is SanitizedImage.Ok -> {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storage.put(path, result.bytes, result.mime) }
                    rpc(
                        "app_foro_image_commit",
                        buildJsonObject {
                            put("p_usuario", user.value); put("p_id", id); put("p_sha256", result.sha256); put("p_ancho", result.width); put("p_alto", result.height); put("p_bytes", result.bytes.size)
                        },
                    )
                    return buildJsonObject { put("estado", "verificada") }
                }
            }
        } finally {
            inFlight.remove(user.value)
        }
    }

    suspend fun status(user: CanonicalUserId, imageId: String): JsonElement {
        if (!imageId.isUuid()) throw validationError(ErrorIssue("id", CanonicalIssue.INVALID_FORMAT))
        val row = rpc("app_foro_image_status", buildJsonObject { put("p_usuario", user.value); put("p_id", imageId.lowercase()) }).jsonObject
        return buildJsonObject { put("estado", row["estado"] ?: JsonNull) }   // nunca una URL ni la ruta
    }

    /** URL firmada de la imagen de una publicacion que el usuario PUEDE leer (la puerta va en la base); TTL corto; nunca se guarda ni se registra. */
    suspend fun signedUrl(user: CanonicalUserId, postId: String): JsonElement {
        if (!postId.isUuid()) throw validationError(ErrorIssue("id", CanonicalIssue.INVALID_FORMAT))
        val r = rpc("app_foro_image_path", buildJsonObject { put("p_usuario", user.value); put("p_post", postId.lowercase()) }).jsonObject
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storage.signedUrl(r["path"]!!.jsonPrimitive.content, SIGNED_TTL_SECONDS) }
        return buildJsonObject { put("url", url); put("expiraEn", clock().plusSeconds(SIGNED_TTL_SECONDS.toLong()).toString()); put("version", r["version"] ?: JsonNull) }
    }

    /** Barrido: borra de Storage los objetos de imagenes retiradas / rechazadas / huerfanas y lo confirma en la base. */
    suspend fun sweep(limit: Int = 50): Int {
        val pending = rpc("app_foro_images_to_purge", buildJsonObject { put("p_limite", limit) }).jsonArray
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (item in pending) {
            val o = item as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
            // a missing object (already deleted) is a success (storage.delete returns false, it does not throw); only a real failure counts an attempt
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storage.delete(path) } }.onSuccess { done += id }.onFailure { failed += id }
        }
        if (failed.isNotEmpty()) rpc("app_foro_images_purge_failed", buildJsonObject { put("p_ids", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } }) })
        if (done.isNotEmpty()) rpc("app_foro_images_purged", buildJsonObject { put("p_ids", buildJsonArray { done.forEach { add(JsonPrimitive(it)) } }) })
        return done.size
    }

    private suspend fun rpc(function: String, body: JsonObject): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body.toString()) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    companion object {
        const val SIGNED_TTL_SECONDS = 120

        fun fromEnvironment(environment: (String) -> String? = System::getenv): ForoImageService? {
            val transport = SupabaseRequestTransport.fromEnvironment(environment) ?: return null
            return ForoImageService(TransportEconomyUpstream(transport), TransportForoImageStorage(transport))
        }
    }
}
