package com.oposicontrol.admin.core.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StructuredLoggerTest {
    @Test
    fun `redacts credentials emails and query values`() {
        val sink = RecordingSink()
        val logger = StructuredLogger(
            minimumLevel = LogLevel.DEBUG,
            includeStackTraces = false,
            sink = sink,
        )

        logger.log(
            LogEvent(
                level = LogLevel.ERROR,
                tag = LogTag.NETWORK,
                event = "request.failure",
                layer = LogLayer.NETWORK,
                operation = "Bearer abc.def.ghi user@example.com https://host/path?token=secret",
            ),
        )

        val line = sink.messages.single()
        assertFalse("user@example.com" in line)
        assertFalse("secret" in line)
        assertFalse("abc.def.ghi" in line)
        assertTrue("REDACTED" in line)
    }

    @Test
    fun `respects minimum level`() {
        val sink = RecordingSink()
        val logger = StructuredLogger(
            minimumLevel = LogLevel.WARN,
            includeStackTraces = false,
            sink = sink,
        )

        logger.log(LogEvent(LogLevel.DEBUG, LogTag.UI, "screen.open", layer = LogLayer.UI))
        logger.log(LogEvent(LogLevel.WARN, LogTag.UI, "screen.warning", layer = LogLayer.UI))

        assertEquals(1, sink.messages.size)
        assertTrue("screen.warning" in sink.messages.single())
    }

    @Test
    fun `renders only allowlisted field keys and redacts every field value`() {
        val sink = RecordingSink()
        val logger = StructuredLogger(
            minimumLevel = LogLevel.DEBUG,
            includeStackTraces = false,
            sink = sink,
        )

        logger.log(
            LogEvent(
                level = LogLevel.INFO,
                tag = LogTag.SUPABASE,
                event = "rpc.result",
                layer = LogLayer.SUPABASE,
                fields = SafeLogField.entries.associateWith {
                    "Bearer token.value.signature admin@example.com password=unsafe"
                },
            ),
        )

        val line = sink.messages.single()
        SafeLogField.entries.forEach { field ->
            assertTrue("${field.name.lowercase()}=" in line)
        }
        assertFalse("token.value.signature" in line)
        assertFalse("admin@example.com" in line)
        assertFalse("unsafe" in line)
        assertTrue("REDACTED" in line)
    }

    @Test
    fun `forwards one throwable once for one error`() {
        val sink = RecordingSink()
        val logger = StructuredLogger(
            minimumLevel = LogLevel.DEBUG,
            includeStackTraces = true,
            sink = sink,
        )
        val failure = IllegalStateException("private failure detail")

        logger.log(
            LogEvent(
                level = LogLevel.ERROR,
                tag = LogTag.BACKEND,
                event = "request.failure",
                layer = LogLayer.BACKEND,
            ),
            failure,
        )

        assertEquals(1, sink.messages.size)
        assertEquals(1, sink.throwables.size)
        assertSame(failure, sink.throwables.single())
        assertFalse("private failure detail" in sink.messages.single())
        assertEquals(1, "errorType=".toRegex().findAll(sink.messages.single()).count())
    }

    private class RecordingSink : LogSink {
        val messages = mutableListOf<String>()
        val throwables = mutableListOf<Throwable>()

        override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            messages += "$tag $message"
            throwable?.let(throwables::add)
        }
    }
}
