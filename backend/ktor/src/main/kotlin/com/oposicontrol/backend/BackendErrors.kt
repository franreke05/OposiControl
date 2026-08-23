package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode

class BackendDomainException(
    val errorCode: String,
    val status: HttpStatusCode,
    override val message: String,
) : RuntimeException(message)

private data class DomainErrorDefinition(
    val status: HttpStatusCode,
    val message: String,
)

private val SUPABASE_DOMAIN_ERRORS = mapOf(
    "RECORD_ANSWER_INVALID_ATTEMPT" to DomainErrorDefinition(HttpStatusCode.NotFound, "El intento no existe o no pertenece al usuario."),
    "RECORD_ANSWER_QUESTION_NOT_IN_ATTEMPT" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La pregunta no pertenece al intento."),
    "RECORD_ANSWER_OPTION_NOT_IN_QUESTION" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La opción no pertenece a la pregunta."),
    "RECORD_ANSWER_MISSING_SOLUTION" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La pregunta no tiene solución configurada."),
    "EVENT_IDEMPOTENCY_CONFLICT" to DomainErrorDefinition(HttpStatusCode.Conflict, "La clave de idempotencia ya fue utilizada con otro evento."),
    "EVENT_VERSION_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La versión del evento no es válida."),
    "EVENT_IDEMPOTENCY_KEY_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La clave de idempotencia es obligatoria."),
    "WALLET_INVALID_USER" to DomainErrorDefinition(HttpStatusCode.NotFound, "El usuario de la cartera no existe."),
    "WALLET_AMOUNT_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La cantidad de tickets no puede ser cero."),
    "WALLET_TYPE_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "El tipo de operación de cartera no es válido."),
    "WALLET_DESCRIPTION_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La descripción de la operación es obligatoria."),
    "WALLET_IDEMPOTENCY_KEY_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La clave de idempotencia es obligatoria."),
    "WALLET_GRANT_AMOUNT_MUST_BE_POSITIVE" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "Las recompensas de reporte deben ser positivas."),
    "WALLET_IDEMPOTENCY_CONFLICT" to DomainErrorDefinition(HttpStatusCode.Conflict, "La clave de idempotencia ya fue utilizada con otros datos."),
    "WALLET_INSUFFICIENT_BALANCE" to DomainErrorDefinition(HttpStatusCode.Conflict, "El saldo de tickets no puede quedar negativo."),
)

fun mapSupabaseDomainError(httpStatus: Int, body: String): BackendDomainException? {
    val code = SUPABASE_DOMAIN_ERRORS.keys.firstOrNull { body.contains(it, ignoreCase = true) } ?: return null
    val definition = SUPABASE_DOMAIN_ERRORS.getValue(code)
    return BackendDomainException(code, definition.status, definition.message)
}
