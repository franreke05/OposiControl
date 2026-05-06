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
)
