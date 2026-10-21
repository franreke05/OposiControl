package com.oposicontrol.backend

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Metadatos y acceso firmado de un recurso de pregunta (imagen, audio, video). Una sola implementacion para los intentos (D2-08, D2-66) y para el reto diario
 * (D3-16, D3-19): la AUTORIZACION (quien puede ver el recurso) la decide cada contrato; aqui solo forma y firma. `ruta` y `bucket` NUNCA salen al cliente.
 */
internal object QuestionResources {
    /** La ruta que exige el CHECK de Q3: `preguntas/<pregunta>/<recurso>.<ext>`. Nada fuera de esta forma se firma. */
    private val RESOURCE_PATH = Regex(
        "^preguntas/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{2,4}$",
    )
    private val MIME = Regex("^[a-z]+/[a-z0-9.+-]+$")

    fun dto(resource: RevisionResource): AttemptResourceDto? {
        if (resource.tipo !in AttemptService.RESOURCE_TYPES || !resource.id.isUuid()) return null
        return AttemptResourceDto(
            id = resource.id,
            tipo = resource.tipo,
            titulo = resource.titulo.orEmpty(),
            mimeType = mimeString(resource),
            version = version(resource),
            sizeBytes = longOrNull(resource.bytes)?.takeIf { it >= 0 },
            durationMs = longOrNull(resource.durationMs)?.takeIf { it >= 0 },
            envelope = if (resource.tipo == "audio") AudioEnvelope.fromStored(resource.envelope) else null,
        )
    }

    /** El recurso ya autorizado por el contrato -> URL firmada de vida corta; bucket, ruta o tipo fuera del contrato = 404 RESOURCE_NOT_FOUND (falla cerrado). */
    fun access(resource: RevisionResource?, resourceId: String, now: Instant, sign: (String, String, Int) -> String): AttemptResourceAccess {
        val path = resource?.ruta
        if (resource == null || resource.tipo !in AttemptService.RESOURCE_TYPES || resource.bucket != AttemptService.RESOURCES_BUCKET || path == null ||
            !RESOURCE_PATH.matches(path) || path.split('/')[2].substringBefore('.') != resourceId
        ) {
            throw CanonicalErrorCode.RESOURCE_NOT_FOUND.exception()
        }
        val url = try {
            sign(AttemptService.RESOURCES_BUCKET, path, AttemptService.RESOURCE_URL_TTL_SECONDS)
        } catch (failure: BackendDomainException) {
            throw failure
        } catch (failure: Exception) {
            throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(retryAfterSeconds = 2, cause = failure)
        }
        return AttemptResourceAccess(
            url = url,
            expiresAt = now.plusSeconds(AttemptService.RESOURCE_URL_TTL_SECONDS.toLong()).toString(),
            mimeType = mimeString(resource),
            version = version(resource),
        )
    }

    private fun mimeString(resource: RevisionResource): String? =
        (resource.mime as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { MIME.matches(it) }

    /** sha256(id|ruta|mime|bytes), 16 hex: cambia si cambia el objeto o sus metadatos; estable mientras no. */
    private fun version(resource: RevisionResource): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${resource.id}|${resource.ruta.orEmpty()}|${mimeString(resource).orEmpty()}|${longOrNull(resource.bytes) ?: ""}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun longOrNull(element: JsonElement?): Long? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
}
