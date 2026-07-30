package com.oposicontrol.backend

internal data class BackendConfiguration(
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val supabaseSecretKey: String,
    val jwtAudience: String,
    val appEnvironment: String,
    val serviceVersion: String,
    val bootstrapAdminEmail: String?,
    val developmentAdminUserId: Long,
    val oposibotsPasswordRecoveryRedirectUrl: String?,
    val appleClientId: String?,
    val appleClientSecret: String?,
    val trustProxyHeaders: Boolean,
) {
    val jwtIssuer: String = "$supabaseUrl/auth/v1"
    val production: Boolean =
        appEnvironment.equals("prod", ignoreCase = true) ||
            appEnvironment.equals("production", ignoreCase = true)
    val developmentOpenAccess: Boolean =
        appEnvironment.equals("dev", ignoreCase = true) ||
            appEnvironment.equals("development", ignoreCase = true) ||
            appEnvironment.equals("local", ignoreCase = true)

    companion object {
        fun fromEnvironment(): BackendConfiguration {
            val supabaseUrl = requiredEnvironment("SUPABASE_URL").trimEnd('/')
            val secretKey = requiredEnvironment("SUPABASE_SECRET_KEY")
            val appEnvironment = System.getenv("APP_ENV").orEmpty().ifBlank { "dev" }
            val production =
                appEnvironment.equals("prod", ignoreCase = true) ||
                    appEnvironment.equals("production", ignoreCase = true)
            val publishableKey = System.getenv("SUPABASE_PUBLISHABLE_KEY")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("SUPABASE_ANON_KEY")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: if (production) {
                    error("SUPABASE_PUBLISHABLE_KEY es obligatorio en producción.")
                } else {
                    // Local-only compatibility while legacy environments move
                    // from one server key to the split key model.
                    secretKey
                }
            if (
                production &&
                !supabaseUrl.startsWith("https://")
            ) {
                error("SUPABASE_URL debe usar HTTPS en producción.")
            }
            return BackendConfiguration(
                supabaseUrl = supabaseUrl,
                supabasePublishableKey = publishableKey,
                supabaseSecretKey = secretKey,
                jwtAudience = System.getenv("SUPABASE_JWT_AUDIENCE").orEmpty().ifBlank { "authenticated" },
                appEnvironment = appEnvironment,
                serviceVersion = System.getenv("APP_VERSION").orEmpty().ifBlank { "dev" },
                bootstrapAdminEmail = System.getenv("BOOTSTRAP_ADMIN_EMAIL")
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                developmentAdminUserId = System.getenv("DEV_ADMIN_USER_ID")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: 1L,
                oposibotsPasswordRecoveryRedirectUrl = optionalEnvironment(
                    "OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL",
                ),
                appleClientId = optionalEnvironment("APPLE_CLIENT_ID"),
                appleClientSecret = optionalEnvironment("APPLE_CLIENT_SECRET"),
                trustProxyHeaders = System.getenv("TRUST_PROXY_HEADERS")
                    ?.toBooleanStrictOrNull()
                    ?: production,
            )
        }

        private fun optionalEnvironment(name: String): String? =
            System.getenv(name)?.trim()?.takeIf(String::isNotBlank)

        private fun requiredEnvironment(name: String): String {
            return System.getenv(name)?.trim()?.takeIf(String::isNotBlank)
                ?: error("$name es obligatorio.")
        }
    }
}
