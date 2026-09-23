package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

class NetworkResultAdapter(
    private val logger: Logger,
) {
    suspend fun <T> safeCall(block: suspend () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            val error = mapThrowable(throwable)
            logger.error("network.failure message=${error.message}", throwable)
            AppResult.Failure(error)
        }
    }

    private fun mapThrowable(throwable: Throwable): AppError {
        return when (throwable) {
            is AuthCredentialsException -> AppError.Authentication(throwable.message ?: "No se pudo iniciar sesión. Inténtalo de nuevo.")
            is RedirectResponseException -> AppError.Network("Redirection error", throwable.response.status.value)
            is ClientRequestException -> AppError.Network("Client request failed", throwable.response.status.value)
            is ServerResponseException -> AppError.Network("Server response failed", throwable.response.status.value)
            is ResponseException -> AppError.Network("Unexpected response error", throwable.response.status.value)
            is SerializationException -> AppError.Network("Respuesta del servidor no valida (parseo JSON fallido): ${throwable.message?.take(120)}")
            is IllegalArgumentException -> AppError.Validation(throwable.message ?: "Peticion invalida")
            is IllegalStateException -> mapBackendFailure(throwable.message)
            // F-04: ANTES que `IOException`, de la que las dos heredan. Un timeout
            // no es "no hay conexión": el servidor recibió la petición y no ha
            // terminado de responder, y puede seguir escribiendo después de que el
            // cliente se rinda. Decir "no se pudo conectar" invita a reintentar lo
            // que quizá ya se hizo (un lote de importación, un examen nuevo).
            // `ConnectTimeoutException` NO entra aquí a propósito: ése sí es no
            // llegar a conectar.
            is HttpRequestTimeoutException, is SocketTimeoutException ->
                AppError.Network(SLOW_SERVER_MESSAGE, REQUEST_TIMEOUT_STATUS)
            is IOException -> AppError.Network("Unable to reach the server")
            else -> AppError.Network("Respuesta inesperada del servidor: ${throwable::class.simpleName}: ${throwable.message?.take(120)}")
        }
    }

    private fun mapBackendFailure(message: String?): AppError {
        val raw = message.orEmpty()
        val status = BACKEND_STATUS_PATTERN.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            status == 401 -> AppError.Authentication("Tu sesión ha caducado. Vuelve a iniciar sesión.")
            status == 403 -> AppError.Authorization("No tienes permisos para realizar esta acción.")
            status == 404 -> AppError.NotFound("El recurso solicitado no existe.")
            status != null && status in 500..599 -> AppError.Network(serverFailureMessage(raw), status)
            // Resto de 4xx: errores de negocio. El backend ya redacta un mensaje
            // humano en el cuerpo (`ErrorDto.message`), y hasta ahora se tiraba:
            // la cadena cruda `backend 409 /api/v1/admin/usuarios/<uuid>/roles:
            // {"error":...}` llegaba entera a la pantalla, con la ruta, el
            // identificador del usuario y el JSON dentro. Además de ilegible,
            // filtraba lo que §52/§55 prohíben mostrar.
            status != null -> AppError.Validation(
                message = backendMessage(raw) ?: "No se ha podido completar la operación.",
                // El código de dominio viaja junto al mensaje: es lo que permite a
                // un formulario marcar EL CAMPO culpable en vez de dejar una línea
                // roja al pie que no dice cuál es.
                code = backendErrorCode(raw),
            )
            // Sin prefijo `backend NNN` no viene de una respuesta HTTP: son los
            // `error(...)` que el propio cliente lanza —«Crear usuarios no está
            // disponible…»—, y ahí el texto ya es el mensaje bueno.
            else -> AppError.Validation(raw.ifBlank { "Configuración incompleta" })
        }
    }

    /**
     * Mensaje de un 5xx del backend.
     *
     * El cuerpo de un 5xx NO se enseña: puede venir de un proxy (una página HTML de
     * la pasarela) y no hay forma de saber desde aquí si su texto está pensado para
     * una persona. Dos excepciones, las dos con origen conocido:
     *
     * - **Rechazos transitorios de la base** (`database_deadlock`,
     *   `database_lock_timeout`, `database_statement_timeout`): su mensaje es una
     *   constante de `BackendErrors` que dice que se puede reintentar y cuándo. Es
     *   justo lo que el operador necesita leer.
     * - **La referencia** (F-12c): los 500 redactados de Ktor terminan en
     *   "Referencia: <request id>", y ese id es lo único que conecta la pantalla con
     *   el log del servidor. Se tiraba entero. Ahora se conserva SÓLO el id —con el
     *   mismo formato que Ktor acepta para un request id—, nunca el resto del texto.
     */
    private fun serverFailureMessage(raw: String): String {
        val body = backendMessage(raw)
        val code = backendErrorCode(raw)?.lowercase()
        val base = if (body != null && code != null && code in RETRYABLE_DATABASE_ERRORS) {
            body
        } else {
            GENERIC_SERVER_MESSAGE
        }
        val reference = body?.let { REFERENCE_PATTERN.find(it)?.groupValues?.getOrNull(1) }
        return if (reference == null) base else "$base Referencia: $reference"
    }

    /**
     * El `message` humano del cuerpo de error del backend.
     *
     * Se extrae con expresión regular y no deserializando para no acoplar este
     * módulo compartido a un DTO del backend: aquí llega una cadena ya
     * concatenada por quien lanzó el error, no una respuesta viva.
     */
    private fun backendMessage(raw: String): String? =
        BACKEND_MESSAGE_PATTERN
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * El CÓDIGO de dominio del cuerpo de error del backend (`ErrorDto.error`).
     *
     * Análogo a [backendMessage] y por la misma razón: se extrae con expresión
     * regular y no deserializando para no acoplar este módulo compartido a un DTO
     * del backend —aquí llega una cadena ya concatenada por quien lanzó el error,
     * no una respuesta viva—.
     *
     * Nunca se muestra: es un identificador de esquema, no texto para leer. Sirve
     * para que la pantalla decida QUÉ campo señalar. El mensaje humano sigue
     * siendo el que se pinta, y lo escribe el backend.
     */
    private fun backendErrorCode(raw: String): String? =
        BACKEND_ERROR_CODE_PATTERN
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val GENERIC_SERVER_MESSAGE = "Ha ocurrido un problema en el servidor."

        /** F-04: lo que se enseña cuando el cliente deja de esperar la respuesta. */
        const val SLOW_SERVER_MESSAGE = "El servidor está tardando demasiado."
        const val REQUEST_TIMEOUT_STATUS = 408

        /**
         * Los tres `error` de `BackendErrors` para rechazos transitorios de la base
         * (SQLSTATE 40P01, 55P03 y 57014), ya en minúsculas como los publica Ktor.
         */
        val RETRYABLE_DATABASE_ERRORS = setOf(
            "database_deadlock",
            "database_lock_timeout",
            "database_statement_timeout",
        )

        /**
         * Las tres expresiones que lee un error de backend, compiladas UNA vez.
         *
         * Estaban construidas dentro de las funciones. Todo fallo HTTP con prefijo
         * `backend NNN` recorre las tres, asi que eran tres `Regex` compiladas por
         * cada error. `Regex` es inmutable y `find` crea su propio matcher, de modo
         * que subirlas aqui no cambia nada de lo que hacen: es la misma decision que
         * este fichero ya habia tomado para [REFERENCE_PATTERN], y que en estas tres
         * se habia quedado a medias.
         */
        val BACKEND_STATUS_PATTERN = Regex("^backend\\s+(\\d{3})\\b")
        val BACKEND_MESSAGE_PATTERN = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val BACKEND_ERROR_CODE_PATTERN = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** Mismo alfabeto y longitudes que el `X-Request-Id` que Ktor acepta. */
        val REFERENCE_PATTERN = Regex("Referencia:\\s*([A-Za-z0-9._-]{8,128})")
    }
}
