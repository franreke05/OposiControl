package com.oposicontrol.backend

import io.ktor.http.ContentType

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType

/**
 * MASTER2-SEC-002: tope global del cuerpo de una peticion NO multipart.
 *
 * Antes solo `canonicalRawBody` (1 MB) y `receiveAuthJson` (16 KB) acotaban lo que se leia; el resto de
 * `call.receive()` (admin y app) bufferizaba cualquier tamano. El tope se aplica sobre `Content-Length` ANTES de que
 * ninguna ruta lea el cuerpo: 413 con el sobre canonico (`PAYLOAD_TOO_LARGE`). Un cuerpo `chunked` no declara longitud,
 * asi que el ingress (Caddy `request_body { max_size }`, ver `ops/caddy/body-limit.caddy`) es el segundo cinturon.
 *
 * Las subidas multipart (portadas 10 MB, recursos 50 MB, imports) quedan fuera: se acotan con lectura limitada en su ruta.
 */
internal const val REQUEST_BODY_MAX_BYTES: Long = 2L * 1024 * 1024

internal val RequestBodyLimit = createApplicationPlugin("RequestBodyLimit") {
    onCall { call ->
        val declared = call.request.contentLength() ?: return@onCall
        if (declared <= REQUEST_BODY_MAX_BYTES) return@onCall
        if (call.request.contentType().match(ContentType.MultiPart.Any)) return@onCall
        throw PayloadTooLargeException(REQUEST_BODY_MAX_BYTES)
    }
}

internal fun Application.installRequestBodyLimit() {
    install(RequestBodyLimit)
}
