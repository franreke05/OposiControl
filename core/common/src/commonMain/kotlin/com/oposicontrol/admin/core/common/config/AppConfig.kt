package com.oposicontrol.admin.core.common.config

data class AppConfig(
    val environment: Environment,
    val baseUrl: String,
    val backendToken: String,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val supabaseBucketExamResources: String,
    val featureFlags: FeatureFlags,
    val appName: String,
    val appVersion: String,
    /** DEV-only explicit flag (`MEDIA_ALLOW_INSECURE_DEV`) that lets [MediaUrlPolicy] accept http media; never set outside DEV. */
    val allowInsecureDevMedia: Boolean = false,
)
