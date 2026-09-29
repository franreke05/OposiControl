package com.oposicontrol.backend

import kotlinx.serialization.json.Json

internal data class OposiBotsAuthConfiguration(
    val supabaseUrl: String,
    val publishableKey: String,
    val secretKey: String,
    val appEnvironment: String,
    val passwordRecoveryRedirectUrl: String?,
    val appleClientId: String?,
    val appleClientSecret: String?,
    val captchaRequired: Boolean,
    val trustProxyHeaders: Boolean,
    val trustedProxyHosts: Set<String>,
    val forwardClientIpToSupabase: Boolean,
    val rateLimitMode: String,
    val rateLimitHmacSecret: String,
    val valkeyUrl: String?,
) {
    val development: Boolean
        get() = appEnvironment.lowercase() in setOf("dev", "development", "local", "test")

    companion object {
        fun fromEnvironment(required: Boolean): OposiBotsAuthConfiguration? {
            val enabled = required ||
                System.getenv("OPOSIBOTS_AUTH_ENABLED")?.toBooleanStrictOrNull() == true
            if (!enabled) return null

            val environment = System.getenv("APP_ENV").orEmpty().ifBlank { "dev" }
            val serviceKey = System.getenv("SUPABASE_SECRET_KEY")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("SUPABASE_SERVICE_ROLE_KEY")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: error("SUPABASE_SECRET_KEY es obligatorio para Auth consumer.")
            val publishableKey = System.getenv("SUPABASE_PUBLISHABLE_KEY")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("SUPABASE_ANON_KEY")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: if (environment.lowercase() in setOf("dev", "development", "local", "test")) {
                    serviceKey
                } else {
                    error("SUPABASE_PUBLISHABLE_KEY es obligatorio fuera de desarrollo.")
                }
            val rateLimitMode = System.getenv("AUTH_RATE_LIMIT_MODE")
                ?.trim()
                ?.lowercase()
                ?.takeIf(String::isNotBlank)
                ?: error("AUTH_RATE_LIMIT_MODE debe ser 'valkey' o, solo en desarrollo, 'memory'.")
            val hmacSecret = System.getenv("AUTH_RATE_LIMIT_HMAC_SECRET")
                ?.takeIf { it.length >= 32 }
                ?: error("AUTH_RATE_LIMIT_HMAC_SECRET debe tener al menos 32 caracteres.")
            val configuration = OposiBotsAuthConfiguration(
                supabaseUrl = requiredEnvironment("SUPABASE_URL").trimEnd('/'),
                publishableKey = publishableKey,
                secretKey = serviceKey,
                appEnvironment = environment,
                passwordRecoveryRedirectUrl = optionalEnvironment("OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL"),
                appleClientId = optionalEnvironment("APPLE_CLIENT_ID"),
                appleClientSecret = optionalEnvironment("APPLE_CLIENT_SECRET"),
                captchaRequired = System.getenv("AUTH_CAPTCHA_REQUIRED")
                    ?.toBooleanStrictOrNull()
                    ?: environment.lowercase().let { it !in setOf("dev", "development", "local", "test") },
                trustProxyHeaders = System.getenv("TRUST_PROXY_HEADERS")
                    ?.toBooleanStrictOrNull()
                    ?: false,
                trustedProxyHosts = optionalEnvironment("TRUSTED_PROXY_HOSTS")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    .orEmpty(),
                forwardClientIpToSupabase = System.getenv("SUPABASE_AUTH_FORWARD_CLIENT_IP")
                    ?.toBooleanStrictOrNull()
                    ?: false,
                rateLimitMode = rateLimitMode,
                rateLimitHmacSecret = hmacSecret,
                valkeyUrl = optionalEnvironment("VALKEY_URL"),
            )
            require(configuration.supabaseUrl.startsWith("https://") || configuration.development) {
                "SUPABASE_URL debe usar HTTPS fuera de desarrollo."
            }
            require(rateLimitMode == "valkey" || (rateLimitMode == "memory" && configuration.development)) {
                "El rate limiter en memoria solo está permitido en desarrollo."
            }
            require(!configuration.trustProxyHeaders || configuration.trustedProxyHosts.isNotEmpty()) {
                "TRUSTED_PROXY_HOSTS es obligatorio cuando TRUST_PROXY_HEADERS=true."
            }
            if (rateLimitMode == "valkey") require(!configuration.valkeyUrl.isNullOrBlank()) {
                "VALKEY_URL es obligatorio cuando AUTH_RATE_LIMIT_MODE=valkey."
            }
            return configuration
        }

        private fun optionalEnvironment(name: String): String? =
            System.getenv(name)?.trim()?.takeIf(String::isNotBlank)

        private fun requiredEnvironment(name: String): String =
            optionalEnvironment(name) ?: error("$name es obligatorio.")
    }
}

internal class OposiBotsAuthRuntime(
    val configuration: OposiBotsAuthConfiguration,
    val gateway: OposiBotsAuthGateway,
    val rateLimiter: OposiBotsAuthRateLimiter,
    val json: Json,
    /** SLICE 1: perfil canonico, borrado de cuenta y adaptadores legacy. `null` solo en pruebas de rutas de auth aisladas. */
    val canonical: CanonicalProfileRuntime? = null,
) {
    companion object {
        fun fromEnvironment(json: Json, required: Boolean, jwtVerifier: SupabaseJwtVerifier? = null): OposiBotsAuthRuntime? {
            val configuration = OposiBotsAuthConfiguration.fromEnvironment(required) ?: return null
            val gateway = SupabaseOposiBotsAuthGateway(configuration, json)
            return OposiBotsAuthRuntime(
                configuration = configuration,
                gateway = gateway,
                rateLimiter = OposiBotsAuthRateLimiterFactory.create(configuration),
                json = json,
                canonical = canonicalProfileRuntime(
                    configuration = configuration,
                    authProvider = gateway,
                    verifier = jwtVerifier ?: SupabaseJwtVerifier(configuration.supabaseUrl, json),
                    json = json,
                ),
            )
        }

        /**
         * El servicio canonico de perfil con la clave de servicio (`SupabaseRequestTransport`) y el mismo
         * `SupabaseJwtVerifier` que el resto del backend. Identidad = solo el `sub` del JWT.
         */
        internal fun canonicalProfileRuntime(
            configuration: OposiBotsAuthConfiguration,
            authProvider: AuthProviderClient,
            verifier: SupabaseJwtVerifier,
            json: Json,
        ): CanonicalProfileRuntime {
            val transport = SupabaseRequestTransport(configuration.supabaseUrl, SupabaseRequestAuthPolicy(configuration.secretKey))
            val upstream = TransportCanonicalUpstream(transport)
            val profiles = SupabaseCanonicalProfileService(upstream, authProvider, json)
            return CanonicalProfileRuntime(
                resolver = CanonicalIdentityResolver(
                    verifySubject = verifier::subFrom,
                    directory = PostgrestCanonicalProfileDirectory(transport),
                    verifyClaims = verifier::claimsFrom,
                ),
                profiles = profiles,
                deletion = AccountDeletionService(upstream, authProvider, json = json),
                legacy = LegacyProfileAdapter(profiles, PostgrestSpecialtyResolver(upstream, json)),
            )
        }
    }
}
