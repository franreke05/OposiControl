package com.oposicontrol.admin.core.common.config

data class FeatureFlags(
    val enableLogs: Boolean,
    val enableAnalytics: Boolean,
    val enableVerboseNetworkLogs: Boolean,
)
