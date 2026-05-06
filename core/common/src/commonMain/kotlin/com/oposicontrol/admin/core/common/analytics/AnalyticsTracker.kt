package com.oposicontrol.admin.core.common.analytics

import com.oposicontrol.admin.core.common.logging.Logger

interface AnalyticsTracker {
    fun trackScreen(screenName: String)
    fun trackAction(actionName: String, metadata: Map<String, String> = emptyMap())
}

class ConsoleAnalyticsTracker(
    private val logger: Logger,
) : AnalyticsTracker {
    override fun trackScreen(screenName: String) {
        logger.info("analytics.screen name=$screenName")
    }

    override fun trackAction(actionName: String, metadata: Map<String, String>) {
        val payload = metadata.entries.joinToString { "${it.key}=${it.value}" }
        logger.info("analytics.action name=$actionName payload={$payload}")
    }
}

data object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun trackScreen(screenName: String) = Unit
    override fun trackAction(actionName: String, metadata: Map<String, String>) = Unit
}
