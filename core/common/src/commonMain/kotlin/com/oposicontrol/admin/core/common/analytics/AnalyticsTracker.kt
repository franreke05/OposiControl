package com.oposicontrol.admin.core.common.analytics

import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.SafeLogField

interface AnalyticsTracker {
    fun trackScreen(screenName: String)
    fun trackAction(actionName: String, metadata: Map<String, String> = emptyMap())
}

class ConsoleAnalyticsTracker(
    private val logger: Logger,
) : AnalyticsTracker {
    override fun trackScreen(screenName: String) {
        logger.log(
            LogEvent(
                level = LogLevel.INFO,
                tag = LogTag.UI,
                event = "analytics.screen",
                screen = screenName,
                layer = LogLayer.UI,
            ),
        )
    }

    override fun trackAction(actionName: String, metadata: Map<String, String>) {
        logger.log(
            LogEvent(
                level = LogLevel.INFO,
                tag = LogTag.UI,
                event = "analytics.action",
                layer = LogLayer.UI,
                operation = actionName,
                fields = mapOf(SafeLogField.COUNT to metadata.size.toString()),
            ),
        )
    }
}

data object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun trackScreen(screenName: String) = Unit
    override fun trackAction(actionName: String, metadata: Map<String, String>) = Unit
}
