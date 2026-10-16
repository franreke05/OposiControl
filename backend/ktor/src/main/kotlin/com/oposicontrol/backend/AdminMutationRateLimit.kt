package com.oposicontrol.backend

import io.ktor.http.HttpMethod
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin

/** DEBT-151: tope de mutaciones por administrador y minuto. Se configura con [ADMIN_MUTATION_RATE_LIMIT_ENV]. */
internal const val ADMIN_MUTATION_RATE_LIMIT_ENV = "ADMIN_MUTATION_RATE_LIMIT_PER_MINUTE"
internal const val ADMIN_MUTATION_RATE_LIMIT_DEFAULT = 120

/** Valor invalido, vacio o no positivo -> el valor por defecto (nunca "sin limite"). */
internal fun adminMutationRateLimit(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: ADMIN_MUTATION_RATE_LIMIT_DEFAULT

private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

internal class AdminMutationRateLimitConfig {
    var limiter: OposiBotsAuthRateLimiter? = null
    var limit: Int = ADMIN_MUTATION_RATE_LIMIT_DEFAULT
}

/**
 * Limite POR ADMINISTRADOR (clave = `sub` del JWT ya autenticado; token estatico: IP del par) sobre
 * `POST/PUT/PATCH/DELETE`. Las lecturas no se limitan. Se instala DESPUES de [AdminAuthorizationPlugin] para que
 * solo cuenten las llamadas ya autorizadas. Superado -> 429 RATE_LIMITED con `Retry-After` (sobre canonico).
 * Sin limitador (pruebas / desarrollo sin runtime de auth) no limita.
 */
internal val AdminMutationRateLimitPlugin = createRouteScopedPlugin(
    name = "AdminMutationRateLimit",
    createConfiguration = ::AdminMutationRateLimitConfig,
) {
    val limiter = pluginConfig.limiter
    val limit = pluginConfig.limit
    on(AuthenticationChecked) { call ->
        if (limiter == null || call.request.local.method !in MUTATING_METHODS) return@on
        val principal = call.principal<UserIdPrincipal>() ?: return@on
        val key = if (principal.name == STATIC_TOKEN_PRINCIPAL) {
            "ip:" + rateLimitClientBucket(call.request.origin.remoteAddress)
        } else {
            "sub:" + principal.name
        }
        val decision = limiter.checkSubject(OposiBotsAuthOperation.ADMIN_MUTATION, key, limit)
        if (!decision.allowed) {
            throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
        }
    }
}
