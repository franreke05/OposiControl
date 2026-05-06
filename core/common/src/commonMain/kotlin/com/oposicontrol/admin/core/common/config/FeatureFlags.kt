package com.oposicontrol.admin.core.common.config

data class FeatureFlags(
    val useFakeData: Boolean,
    val enableLogs: Boolean,
    val enableAnalytics: Boolean,
    val enableVerboseNetworkLogs: Boolean,
    val fakeLatencyMs: Long,
    val fakeErrorRate: Double,
)
