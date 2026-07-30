package com.oposicontrol.admin.core.testing

import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogSink
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.StructuredLogger

/**
 * Safe, deterministic logger for multiplatform tests.
 *
 * Entries are captured after [StructuredLogger] applies the production redactor,
 * so assertions never need to retain credentials or user-provided payloads.
 * Tests that emit concurrently should use the same test dispatcher.
 */
class InMemoryLogger(
    minimumLevel: LogLevel = LogLevel.DEBUG,
    includeStackTraces: Boolean = true,
) : Logger {
    private val recordedEntries = mutableListOf<InMemoryLogEntry>()
    private val delegate = StructuredLogger(
        minimumLevel = minimumLevel,
        includeStackTraces = includeStackTraces,
        sink = LogSink { level, tag, message, throwable ->
            recordedEntries += InMemoryLogEntry(
                level = level,
                tag = tag,
                message = message,
                throwableType = throwable?.let {
                    it::class.simpleName ?: "Throwable"
                },
                stackTraceCount = if (throwable == null) 0 else 1,
            )
        },
    )

    val entries: List<InMemoryLogEntry>
        get() = recordedEntries.toList()

    override fun log(event: LogEvent, throwable: Throwable?) {
        delegate.log(event, throwable)
    }

    fun clear() {
        recordedEntries.clear()
    }
}

data class InMemoryLogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableType: String?,
    val stackTraceCount: Int,
)
