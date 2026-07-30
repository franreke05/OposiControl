package com.oposicontrol.admin.core.common.config

data class AppConfig(
    val environment: Environment,
    val baseUrl: String,
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val supabaseBucketExamResources: String,
    val featureFlags: FeatureFlags,
    val appName: String,
    val appVersion: String,
) {
    /**
     * Kept only while the frozen News slice still compiles against its legacy name.
     * A publishable key is safe to ship; server secrets never enter AppConfig.
     */
    @Deprecated("Use supabasePublishableKey")
    val supabaseAnonKey: String
        get() = supabasePublishableKey

    /**
     * Compatibility shim for frozen legacy repositories. It can never expose a
     * configured backend credential and will be removed with those adapters.
     */
    @Deprecated("Static backend tokens are no longer supported")
    val backendToken: String
        get() = ""
}
