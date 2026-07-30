package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.ApiErrorResponse
import com.oposicontrol.admin.core.api.ApiFieldError
import io.ktor.http.HttpStatusCode

internal open class ApiProblem(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val fieldErrors: List<ApiFieldError> = emptyList(),
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class BadRequestProblem(
    message: String = "La solicitud no es válida.",
    fieldErrors: List<ApiFieldError> = emptyList(),
) : ApiProblem(
    status = HttpStatusCode.BadRequest,
    code = "bad_request",
    message = message,
    fieldErrors = fieldErrors,
)

internal class UnauthorizedProblem :
    ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "Autenticación requerida.")

internal class ForbiddenProblem(permission: String? = null) : ApiProblem(
    status = HttpStatusCode.Forbidden,
    code = "forbidden",
    message = permission?.let { "No tienes el permiso requerido: $it." } ?: "Acceso denegado.",
)

internal class NotFoundProblem(resource: String = "Recurso") :
    ApiProblem(HttpStatusCode.NotFound, "not_found", "$resource no encontrado.")

internal class ConflictProblem(message: String = "El recurso cambió desde la última lectura.") :
    ApiProblem(HttpStatusCode.Conflict, "version_conflict", message)

internal class UpstreamProblem(
    message: String = "El servicio de datos no está disponible.",
    cause: Throwable? = null,
) : ApiProblem(
    status = HttpStatusCode.BadGateway,
    code = "upstream_unavailable",
    message = message,
    retryable = true,
    cause = cause,
)

internal fun ApiProblem.toResponse(requestId: String): ApiErrorResponse = ApiErrorResponse(
    code = code,
    message = message,
    requestId = requestId,
    fieldErrors = fieldErrors,
    retryable = retryable,
)
