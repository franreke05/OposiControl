package com.oposicontrol.admin.core.testing

import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.SafeLogField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryLoggerTest {
    @Test
    fun `captures the redacted production representation`() {
        val logger = InMemoryLogger()

        logger.log(
            LogEvent(
                level = LogLevel.INFO,
                tag = LogTag.NETWORK,
                event = "request.success",
                screen = "users",
                layer = LogLayer.NETWORK,
                operation = "Bearer secret-token user@example.com",
                correlationId = "correlation-123",
                requestId = "request-456",
                fields = mapOf(
                    SafeLogField.ROUTE to "/api/admin/v1/users?token=private",
                    SafeLogField.RESULT to "success",
                ),
            ),
        )

        val entry = logger.entries.single()
        assertEquals("OC-NET", entry.tag)
        assertTrue("event=request.success" in entry.message)
        assertTrue("correlationId=correlation-123" in entry.message)
        assertTrue("requestId=request-456" in entry.message)
        assertTrue("REDACTED" in entry.message)
        assertFalse("secret-token" in entry.message)
        assertFalse("user@example.com" in entry.message)
        assertFalse("private" in entry.message)
    }

    @Test
    fun `delivers one throwable once for one error event`() {
        val logger = InMemoryLogger(includeStackTraces = true)
        val failure = IllegalStateException("sensitive detail")

        logger.log(
            LogEvent(
                level = LogLevel.ERROR,
                tag = LogTag.REPOSITORY,
                event = "operation.failure",
                layer = LogLayer.REPOSITORY,
            ),
            failure,
        )

        assertEquals(1, logger.entries.size)
        assertEquals("IllegalStateException", logger.entries.single().throwableType)
        assertEquals(1, logger.entries.sumOf { it.stackTraceCount })
        assertFalse("sensitive detail" in logger.entries.single().message)
        assertEquals(1, "errorType=".toRegex().findAll(logger.entries.single().message).count())
    }

    @Test
    fun `honors release-style level filtering and can be cleared`() {
        val logger = InMemoryLogger(minimumLevel = LogLevel.WARN)

        logger.log(LogEvent(LogLevel.DEBUG, LogTag.UI, "screen.open", layer = LogLayer.UI))
        logger.log(LogEvent(LogLevel.WARN, LogTag.UI, "screen.retry", layer = LogLayer.UI))

        assertEquals(listOf(LogLevel.WARN), logger.entries.map { it.level })
        logger.clear()
        assertTrue(logger.entries.isEmpty())
    }
}
