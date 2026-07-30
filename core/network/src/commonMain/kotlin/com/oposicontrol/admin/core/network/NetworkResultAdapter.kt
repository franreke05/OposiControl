package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.logging.SafeLogField
import com.oposicontrol.admin.core.common.logging.withOperationContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlin.time.TimeSource

class NetworkResultAdapter(
    private val logger: Logger,
) {
    suspend fun <T> safeCall(block: suspend () -> T): AppResult<T> {
        return safeCall(operation = "unspecified", block = block)
    }

    suspend fun <T> safeCall(
        operation: String,
        context: OperationContext = OperationContext(),
        block: suspend () -> T,
    ): AppResult<T> {
        val startedAt = TimeSource.Monotonic.markNow()
        logger.log(
            LogEvent(
                level = LogLevel.DEBUG,
                tag = LogTag.NETWORK,
                event = "operation.start",
                layer = LogLayer.NETWORK,
                operation = operation,
                correlationId = context.correlationId,
                requestId = context.requestId,
            ),
        )
        return withOperationContext(context) {
            try {
                val value = block()
                logger.log(
                    LogEvent(
                        level = LogLevel.INFO,
                        tag = LogTag.NETWORK,
                        event = "operation.success",
                        layer = LogLayer.NETWORK,
                        operation = operation,
                        correlationId = context.correlationId,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                        fields = mapOf(SafeLogField.RESULT to "success"),
                    ),
                )
                AppResult.Success(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val error = mapThrowable(throwable)
                logger.log(
                    LogEvent(
                        level = LogLevel.ERROR,
                        tag = LogTag.NETWORK,
                        event = "operation.failure",
                        layer = LogLayer.NETWORK,
                        operation = operation,
                        correlationId = context.correlationId,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                        fields = buildMap {
                            put(SafeLogField.RESULT, "failure")
                            put(SafeLogField.ERROR_TYPE, throwable::class.simpleName ?: "Throwable")
                            (error as? AppError.Network)?.code?.let {
                                put(SafeLogField.HTTP_STATUS, it.toString())
                            }
                        },
                    ),
                    throwable,
                )
                AppResult.Failure(error)
            }
        }
    }

    private fun mapThrowable(throwable: Throwable): AppError {
        return when (throwable) {
            is BackendHttpException -> mapStatus(throwable.statusCode)
            is RedirectResponseException -> mapStatus(throwable.response.status.value)
            is ClientRequestException -> mapStatus(throwable.response.status.value)
            is ServerResponseException -> mapStatus(throwable.response.status.value)
            is ResponseException -> mapStatus(throwable.response.status.value)
            is SerializationException -> AppError.Network("Respuesta del servidor no valida (parseo JSON fallido)")
            is IllegalArgumentException -> AppError.Validation(throwable.message ?: "Peticion invalida")
            is IllegalStateException -> AppError.Validation(throwable.message ?: "Configuracion incompleta")
            is IOException -> AppError.Network("Unable to reach the server")
            else -> AppError.Network("Respuesta inesperada del servidor")
        }
    }

    private fun mapStatus(statusCode: Int): AppError = when (statusCode) {
        400, 422 -> AppError.Validation("La solicitud contiene datos no validos.")
        401 -> AppError.Authentication("La sesion no es valida o ha caducado.")
        403 -> AppError.Authorization("No tienes permiso para realizar esta accion.")
        404 -> AppError.NotFound("No se encontro el recurso solicitado.")
        409 -> AppError.Conflict("Los datos cambiaron en el servidor. Actualiza y vuelve a intentarlo.")
        429 -> AppError.RateLimited("Demasiadas solicitudes. Intentalo de nuevo mas tarde.")
        else -> AppError.Network("El servidor no pudo completar la solicitud.", statusCode)
    }
}

class BackendHttpException(
    val statusCode: Int,
) : IllegalStateException("Backend request failed with status $statusCode")
