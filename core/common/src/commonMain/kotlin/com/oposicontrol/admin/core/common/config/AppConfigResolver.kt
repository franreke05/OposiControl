package com.oposicontrol.admin.core.common.config

object AppConfigResolver {
    fun resolve(source: RuntimeConfigSource): AppConfig {
        val environment = Environment.from(source.read(KEY_APP_ENV))
        val baseUrl = source.read(KEY_BASE_URL) ?: defaultBaseUrlFor(environment)
        requireSecureUrl("BASE_URL", baseUrl, environment)
        val supabaseUrl = source.read(KEY_SUPABASE_URL).orEmpty().trimEnd('/')
        if (supabaseUrl.isNotBlank()) {
            requireSecureUrl("SUPABASE_URL", supabaseUrl, environment)
        }
        return AppConfig(
            environment = environment,
            baseUrl = baseUrl,
            backendToken = source.read(KEY_BACKEND_TOKEN).orEmpty(),
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = source.read(KEY_SUPABASE_ANON_KEY).orEmpty(),
            supabaseBucketExamResources = source.read(KEY_SUPABASE_BUCKET_EXAM_RESOURCES)
                ?.ifBlank { DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES }
                ?: DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES,
            featureFlags = FeatureFlags(
                useFakeData = source.read(KEY_USE_FAKE_DATA)?.toBooleanStrictOrNull() ?: false,
                enableLogs = source.read(KEY_ENABLE_LOGS)?.toBooleanStrictOrNull() ?: environment != Environment.PROD,
                enableAnalytics = source.read(KEY_ENABLE_ANALYTICS)?.toBooleanStrictOrNull() ?: environment != Environment.DEV,
                enableVerboseNetworkLogs = source.read(KEY_ENABLE_VERBOSE_NETWORK_LOGS)?.toBooleanStrictOrNull() ?: environment == Environment.DEV,
                fakeLatencyMs = source.read(KEY_FAKE_LATENCY_MS)?.toLongOrNull() ?: 120L,
                fakeErrorRate = source.read(KEY_FAKE_ERROR_RATE)?.toDoubleOrNull() ?: 0.0,
            ),
            appName = source.read(KEY_APP_NAME) ?: "OposiControl Admin",
            appVersion = source.read(KEY_APP_VERSION) ?: "1.0.0",
        )
    }

    private fun requireSecureUrl(key: String, value: String, environment: Environment) {
        if (environment == Environment.DEV) return
        require(value.startsWith("https://")) { "$key debe usar HTTPS en ${environment.name.lowercase()}." }
        require(!value.contains("localhost", ignoreCase = true)) { "$key no puede apuntar a localhost en ${environment.name.lowercase()}." }
    }

    private fun defaultBaseUrlFor(environment: Environment): String {
        return when (environment) {
            Environment.DEV -> "http://localhost:8080"
            Environment.STAGING -> "https://staging.api.oposicontrol.local"
            Environment.PROD -> "https://api.oposicontrol.local"
        }
    }

    const val KEY_APP_ENV = "APP_ENV"
    const val KEY_BASE_URL = "BASE_URL"
    const val KEY_BACKEND_TOKEN = "BACKEND_TOKEN"
    const val KEY_SUPABASE_URL = "SUPABASE_URL"
    const val KEY_SUPABASE_ANON_KEY = "SUPABASE_ANON_KEY"
    const val KEY_SUPABASE_BUCKET_EXAM_RESOURCES = "SUPABASE_BUCKET_EXAM_RESOURCES"
    const val KEY_USE_FAKE_DATA = "USE_FAKE_DATA"
    const val KEY_ENABLE_LOGS = "ENABLE_LOGS"
    const val KEY_ENABLE_ANALYTICS = "ENABLE_ANALYTICS"
    const val KEY_ENABLE_VERBOSE_NETWORK_LOGS = "ENABLE_VERBOSE_NETWORK_LOGS"
    const val KEY_FAKE_LATENCY_MS = "FAKE_LATENCY_MS"
    const val KEY_FAKE_ERROR_RATE = "FAKE_ERROR_RATE"
    const val KEY_APP_NAME = "APP_NAME"
    const val KEY_APP_VERSION = "APP_VERSION"

    private const val DEFAULT_SUPABASE_BUCKET_EXAM_RESOURCES = "exam-resources"
}
