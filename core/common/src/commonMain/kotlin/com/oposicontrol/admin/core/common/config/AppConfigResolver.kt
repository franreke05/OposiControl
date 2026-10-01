package com.oposicontrol.admin.core.common.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object AppConfigResolver {
    fun resolve(rawSource: RuntimeConfigSource): AppConfig {
        // MASTER5-OC-005: un valor en blanco (p. ej. `BASE_URL=` en .env) es AUSENTE, no "configurado": ni pasa la
        // validacion de DEV ni impide el defecto. Se normaliza aqui para TODAS las fuentes (JVM, Android, tests).
        val source = object : RuntimeConfigSource {
            override fun read(key: String): String? = rawSource.read(key)?.trim()?.ifBlank { null }
        }
        val environment = Environment.from(source.read(KEY_APP_ENV))
        // STAGING/PROD fallan cerrado: sin BASE_URL explicito no se inventa un host (los *.local no enrutan y
        // esconderian una mala configuracion hasta la primera llamada). Solo DEV tiene defecto (localhost).
        val baseUrl = source.read(KEY_BASE_URL)
            ?: if (environment == Environment.DEV) {
                DEV_DEFAULT_BASE_URL
            } else {
                throw IllegalArgumentException("BASE_URL es obligatorio en ${environment.name.lowercase()}.")
            }
        requireSecureUrl("BASE_URL", baseUrl, environment)
        val supabaseUrl = source.read(KEY_SUPABASE_URL).orEmpty().trimEnd('/')
        if (supabaseUrl.isNotBlank()) {
            requireSecureUrl("SUPABASE_URL", supabaseUrl, environment)
        }
        // Mismo criterio que el guard de build (composeApp/build.gradle.kts), pero sobre el valor EN RUNTIME: el
        // fallback de escritorio (-Doposi.*, variable de entorno, .env) llega sin pasar por Gradle. Falla cerrado y
        // el mensaje no repite el valor.
        val anonKey = source.read(KEY_SUPABASE_ANON_KEY).orEmpty()
        require(!isSecretSupabaseKey(anonKey)) {
            "$KEY_SUPABASE_ANON_KEY no puede ser una clave secreta (sb_secret_ / service_role)."
        }
        return AppConfig(
            environment = environment,
            baseUrl = baseUrl,
            backendToken = source.read(KEY_BACKEND_TOKEN).orEmpty(),
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = anonKey,
            supabaseBucketExamResources = source.read(KEY_SUPABASE_BUCKET_EXAM_RESOURCES)
                ?.ifBlank { DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES }
                ?: DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES,
            featureFlags = FeatureFlags(
                enableLogs = source.read(KEY_ENABLE_LOGS)?.toBooleanStrictOrNull() ?: environment != Environment.PROD,
                enableAnalytics = source.read(KEY_ENABLE_ANALYTICS)?.toBooleanStrictOrNull() ?: environment != Environment.DEV,
            ),
            appName = source.read(KEY_APP_NAME) ?: "OposiControl Admin",
            appVersion = source.read(KEY_APP_VERSION) ?: "1.0.0",
        )
    }

    private fun requireSecureUrl(key: String, value: String, environment: Environment) {
        if (environment == Environment.DEV) return
        require(value.startsWith("https://", ignoreCase = true)) { "$key debe usar HTTPS en ${environment.name.lowercase()}." }
        require(!value.contains("localhost", ignoreCase = true)) { "$key no puede apuntar a localhost en ${environment.name.lowercase()}." }
    }

    /** `sb_secret_` keys and JWTs whose payload carries `"role":"service_role"`; a malformed key is not a secret key. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun isSecretSupabaseKey(key: String): Boolean {
        if (key.startsWith("sb_secret_", ignoreCase = true)) return true
        val parts = key.split('.')
        if (parts.size != 3) return false
        val payload = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString()
        }.getOrNull() ?: return false
        return payload.filterNot { it.isWhitespace() }.contains("\"role\":\"service_role\"")
    }

    private const val DEV_DEFAULT_BASE_URL = "http://localhost:8080"

    const val KEY_APP_ENV = "APP_ENV"
    const val KEY_BASE_URL = "BASE_URL"
    const val KEY_BACKEND_TOKEN = "BACKEND_TOKEN"
    const val KEY_SUPABASE_URL = "SUPABASE_URL"
    const val KEY_SUPABASE_ANON_KEY = "SUPABASE_ANON_KEY"
    const val KEY_SUPABASE_BUCKET_EXAM_RESOURCES = "SUPABASE_BUCKET_EXAM_RESOURCES"
    const val KEY_ENABLE_LOGS = "ENABLE_LOGS"
    const val KEY_ENABLE_ANALYTICS = "ENABLE_ANALYTICS"
    const val KEY_APP_NAME = "APP_NAME"
    const val KEY_APP_VERSION = "APP_VERSION"

    private const val DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES = "exam-resources"
}
