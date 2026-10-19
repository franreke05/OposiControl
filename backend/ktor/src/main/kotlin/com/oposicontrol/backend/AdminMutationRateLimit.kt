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

internal const val ADMIN_RATE_LIMIT_MODE_ENV = "ADMIN_RATE_LIMIT_MODE"
internal const val ADMIN_RATE_LIMIT_HMAC_SECRET_ENV = "ADMIN_RATE_LIMIT_HMAC_SECRET"
internal const val ADMIN_RATE_LIMIT_VALKEY_URL_ENV = "ADMIN_RATE_LIMIT_VALKEY_URL"

/**
 * SEC-V1-02: limitador propio del plano admin. El perfil `control` no construye el runtime de auth de consumidor, asi
 * que sin esto el plugin recibia `null` y no limitaba nada en produccion. Variables PROPIAS de control (F-09b: control no
 * recibe `AUTH_RATE_LIMIT_HMAC_SECRET` ni `VALKEY_URL` del consumidor), mismas reglas que el limitador de auth
 * (OposiBotsAuthConfiguration.requireDeployable): `valkey` con URL; `memory` solo en desarrollo; secreto HMAC >= 32.
 * Sin [ADMIN_RATE_LIMIT_MODE_ENV]: `null` (sin limite) SOLO en desarrollo; fuera, el arranque falla.
 * [valkey] construye el cliente real (conecta al construirse: Valkey caido = arranque fallido, fail closed).
 */
internal fun adminMutationRateLimiterFromEnvironment(
    environment: (String) -> String? = System::getenv,
    valkey: (url: String, hasher: RateLimitKeyHasher) -> OposiBotsAuthRateLimiter = ::ValkeyOposiBotsAuthRateLimiter,
): OposiBotsAuthRateLimiter? {
    val development = AppEnvironment.isDevelopment(environment("APP_ENV"))
    val mode = environment(ADMIN_RATE_LIMIT_MODE_ENV)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    if (mode == null) {
        check(development) { "$ADMIN_RATE_LIMIT_MODE_ENV es obligatorio fuera de desarrollo (limite de mutaciones admin, DEBT-151)." }
        return null
    }
    val secret = environment(ADMIN_RATE_LIMIT_HMAC_SECRET_ENV)?.takeIf { it.length >= 32 }
        ?: error("$ADMIN_RATE_LIMIT_HMAC_SECRET_ENV debe tener al menos 32 caracteres.")
    val hasher = RateLimitKeyHasher(secret)
    return when (mode) {
        "valkey" -> valkey(
            environment(ADMIN_RATE_LIMIT_VALKEY_URL_ENV)?.trim()?.takeIf(String::isNotBlank)
                ?: error("$ADMIN_RATE_LIMIT_VALKEY_URL_ENV es obligatorio cuando $ADMIN_RATE_LIMIT_MODE_ENV=valkey."),
            hasher,
        )
        "memory" -> {
            check(development) { "El rate limiter en memoria solo está permitido en desarrollo." }
            InMemoryOposiBotsAuthRateLimiter(hasher = hasher)
        }
        else -> error("$ADMIN_RATE_LIMIT_MODE_ENV debe ser 'valkey' o, solo en desarrollo, 'memory'.")
    }
}

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
