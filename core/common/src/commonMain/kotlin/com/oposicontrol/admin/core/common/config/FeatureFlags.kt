package com.oposicontrol.admin.core.common.config

data class FeatureFlags(
    val useFakeData: Boolean = false,
    val enableLogs: Boolean,
    val enableAnalytics: Boolean,
    val enableVerboseNetworkLogs: Boolean = false,
    val fakeLatencyMs: Long = 0L,
    val fakeErrorRate: Double = 0.0,
)
