package com.oposicontrol.backend

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// =====================================================================
// E2 (2026-10-15): `Idempotency-Key` OPCIONAL en escrituras admin que
// duplicaban al reintentar (respuesta de soporte, advertencia de moderacion).
// Mismo plugin y mismo almacen que noticias (SLICE 0, `idem_begin` y familia).
// =====================================================================

/** Sin almacen configurado: una peticion CON clave falla cerrada (500) en vez de ejecutarse sin deduplicar. */
private object MisconfiguredAdminIdempotencyStore : IdempotencyStore {
    private fun fail(): Nothing =
        throw CanonicalErrorCode.INTERNAL.exception(cause = IllegalStateException("admin idempotency store not configured"))

    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = fail()
    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) = fail()
    override suspend fun release(claim: IdempotencyClaim) = fail()
}

/**
 * Con `Idempotency-Key` (UUID) la repeticion devuelve la respuesta 2xx guardada sin volver a ejecutar (y la misma clave
 * con otro cuerpo es 422 IDEMPOTENCY_KEY_REUSED); sin clave, el comportamiento de siempre (el panel actual no la envia).
 * Actor = `sub` del JWT: el token estatico (no es una persona) recibe 403 si manda clave.
 */
internal fun Route.adminOptionalIdempotency(routeTemplate: String, store: IdempotencyStore?) {
    install(CanonicalIdempotency) {
        this.routeTemplate = routeTemplate
        required = false
        afterAuthentication = true
        this.store = store ?: MisconfiguredAdminIdempotencyStore
        actor = { call ->
            actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name) ?: throw CanonicalErrorCode.FORBIDDEN.exception()
        }
    }
}

@PublishedApi
internal val adminIdempotentBodyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * El cuerpo de un handler con [adminOptionalIdempotency]: si el plugin ya lo leyo (habia clave) se decodifica de esa
 * copia; si no, el `receive` de siempre (mismos 400/415 que antes).
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveAdminBody(): T {
    val cached = cachedCanonicalRawBodyOrNull() ?: return receive<T>()
    requireJsonContentIfBody(cached)
    return try {
        adminIdempotentBodyJson.decodeFromString<T>(String(cached, Charsets.UTF_8))
    } catch (bad: SerializationException) {
        throw CanonicalErrorCode.MALFORMED_BODY.exception()
    } catch (bad: IllegalArgumentException) {
        throw CanonicalErrorCode.MALFORMED_BODY.exception()
    }
}
