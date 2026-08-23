package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import io.ktor.client.plugins.ClientRequestException
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
            is IOException -> AppError.Network("Unable to reach the server")
            else -> AppError.Network("Respuesta inesperada del servidor: ${throwable::class.simpleName}: ${throwable.message?.take(120)}")
        }
    }

    private fun mapBackendFailure(message: String?): AppError {
        val status = Regex("^backend\\s+(\\d{3})\\b").find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            status == 401 -> AppError.Authentication("Tu sesión ha caducado. Vuelve a iniciar sesión.")
            status == 403 -> AppError.Authorization("No tienes permisos para realizar esta acción.")
            status == 404 -> AppError.NotFound("El recurso solicitado no existe.")
            status != null && status in 500..599 -> AppError.Network("Ha ocurrido un problema en el servidor.", status)
            else -> AppError.Validation(message ?: "Configuración incompleta")
        }
    }
}
