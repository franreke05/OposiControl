package com.oposicontrol.backend

import io.ktor.http.ContentType

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.path

/**
 * MASTER2-SEC-002: tope global del cuerpo de una peticion NO multipart.
 *
 * Antes solo `canonicalRawBody` (1 MB) y `receiveAuthJson` (16 KB) acotaban lo que se leia; el resto de
 * `call.receive()` (admin y app) bufferizaba cualquier tamano. El tope se aplica sobre `Content-Length` ANTES de que
 * ninguna ruta lea el cuerpo: 413 con el sobre canonico (`PAYLOAD_TOO_LARGE`). Un cuerpo `chunked` no declara longitud,
 * asi que el ingress (Caddy `request_body { max_size }`, ver `ops/caddy/body-limit.caddy`) es el segundo cinturon.
 *
 * Las subidas multipart (portadas 10 MB, recursos 50 MB, imports) quedan fuera: se acotan con lectura limitada en su ruta.
 *
 * MASTER5-API-007: el plano `/v1/auth` tambien queda fuera. Tiene su propio tope, mas estricto (`receiveAuthJson`:
 * 16 KB sobre `Content-Length` Y sobre lo leido, nunca bufferiza mas), y su contrato congelado (`openapi/auth`) solo
 * declara 400 VALIDATION para un cuerpo inaceptable. Con este tope delante, 16 KB..2 MB daba 400 y > 2 MB un 413 no
 * declarado: ahora cualquier cuerpo demasiado grande en `/v1/auth` es el mismo 400 declarado.
 */
internal const val REQUEST_BODY_MAX_BYTES: Long = 2L * 1024 * 1024

/** Prefijo del plano de auth de OposiBots, que acota su cuerpo en `receiveAuthJson` (MASTER5-API-007). */
internal const val AUTH_PLANE_PATH_PREFIX = "/v1/auth/"

internal val RequestBodyLimit = createApplicationPlugin("RequestBodyLimit") {
    onCall { call ->
        val declared = call.request.contentLength() ?: return@onCall
        if (declared <= REQUEST_BODY_MAX_BYTES) return@onCall
        if (call.request.contentType().match(ContentType.MultiPart.Any)) return@onCall
        if (isAuthPlanePath(call.request.path())) return@onCall
        throw PayloadTooLargeException(REQUEST_BODY_MAX_BYTES)
    }
}

/**
 * Solo una ruta literal del plano de auth: sin segmentos `.`/`..` ni escapes `%`, que el enrutado podria normalizar
 * hacia OTRA ruta (p. ej. `/v1/auth/../../api/v1/app/eventos`) saltandose asi el tope global. Las rutas de auth reales
 * no llevan ninguno de los dos.
 */
internal fun isAuthPlanePath(path: String): Boolean =
    path.startsWith(AUTH_PLANE_PATH_PREFIX) && '%' !in path && path.split('/').none { it == "." || it == ".." }

internal fun Application.installRequestBodyLimit() {
    install(RequestBodyLimit)
}
