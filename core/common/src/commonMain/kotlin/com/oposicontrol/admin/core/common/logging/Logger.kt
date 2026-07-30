package com.oposicontrol.admin.core.common.logging

import com.oposicontrol.admin.core.common.id.randomUuid

enum class LogLevel(val priority: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40),
}

enum class LogTag(val value: String) {
    UI("OC-UI"),
    VIEW_MODEL("OC-VM"),
    REPOSITORY("OC-REPO"),
    NETWORK("OC-NET"),
    DIAGNOSTICS("OC-DIAG"),
    UPLOAD("OC-UPLOAD"),
    BACKEND("OC-KTOR"),
    SUPABASE("OC-SUPA"),
}

enum class LogLayer {
    UI,
    VIEW_MODEL,
    DOMAIN,
    REPOSITORY,
    NETWORK,
    BACKEND,
    SUPABASE,
}

/**
 * Fields intentionally accepted by permanent logs.
 *
 * Business identifiers, request bodies and user-provided text are deliberately
 * absent. Add a field only after checking that it cannot contain personal data.
 */
enum class SafeLogField {
    ATTEMPT,
    COUNT,
    ENVIRONMENT,
    ERROR_TYPE,
    HTTP_METHOD,
    HTTP_STATUS,
    MIME_GROUP,
    RESULT,
    RETRYABLE,
    ROUTE,
}

data class OperationContext(
    val correlationId: String = randomUuid(),
    val requestId: String = randomUuid(),
)

data class LogEvent(
    val level: LogLevel,
    val tag: LogTag,
    val event: String,
    val screen: String? = null,
    val layer: LogLayer,
    val operation: String? = null,
    val correlationId: String? = null,
    val requestId: String? = null,
    val durationMs: Long? = null,
    val fields: Map<SafeLogField, String> = emptyMap(),
)

interface Logger {
    fun log(event: LogEvent, throwable: Throwable? = null)

    fun debug(message: String) {
        log(legacyEvent(LogLevel.DEBUG, message))
    }

    fun info(message: String) {
        log(legacyEvent(LogLevel.INFO, message))
    }

    fun warn(message: String) {
        log(legacyEvent(LogLevel.WARN, message))
    }

    fun error(message: String, throwable: Throwable? = null) {
        log(legacyEvent(LogLevel.ERROR, message), throwable)
    }
}

class ConsoleLogger(
    minimumLevel: LogLevel = LogLevel.DEBUG,
    includeStackTraces: Boolean = true,
) : Logger by StructuredLogger(
    minimumLevel = minimumLevel,
    includeStackTraces = includeStackTraces,
    sink = PlatformLogSink,
)

class StructuredLogger(
    private val minimumLevel: LogLevel,
    private val includeStackTraces: Boolean,
    private val sink: LogSink,
) : Logger {
    override fun log(event: LogEvent, throwable: Throwable?) {
        if (event.level.priority < minimumLevel.priority) return

        val safeEvent = LogRedactor.redact(event)
        val rendered = buildString {
            append("event=").append(safeEvent.event)
            append(" layer=").append(safeEvent.layer.name.lowercase())
            safeEvent.screen?.let { append(" screen=").append(it) }
            safeEvent.operation?.let { append(" operation=").append(it) }
            safeEvent.correlationId?.let { append(" correlationId=").append(it) }
            safeEvent.requestId?.let { append(" requestId=").append(it) }
            safeEvent.durationMs?.let { append(" durationMs=").append(it) }
            safeEvent.fields.forEach { (key, value) ->
                append(' ').append(key.name.lowercase()).append('=').append(value)
            }
            throwable?.let { append(" errorType=").append(it::class.simpleName ?: "Throwable") }
        }
        sink.write(
            level = safeEvent.level,
            tag = safeEvent.tag.value,
            message = rendered,
            throwable = throwable.takeIf { includeStackTraces },
        )
    }
}

fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

data object NoOpLogger : Logger {
    override fun log(event: LogEvent, throwable: Throwable?) = Unit
}

internal data object LogRedactor {
    private val bearerPattern = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+=*")
    private val jwtPattern = Regex("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val urlQueryPattern = Regex("([?&][^\\s=]+)=([^\\s&]+)")
    private val secretAssignmentPattern =
        Regex("(?i)(password|token|secret|apikey|api_key|cookie|authorization)=([^\\s]+)")

    fun redact(event: LogEvent): LogEvent {
        return event.copy(
            event = safeToken(event.event),
            screen = event.screen?.let(::safeToken),
            operation = event.operation?.let(::safeToken),
            correlationId = event.correlationId?.let(::safeIdentifier),
            requestId = event.requestId?.let(::safeIdentifier),
            fields = event.fields.mapValues { (_, value) -> safeValue(value) },
        )
    }

    private fun safeToken(value: String): String {
        return safeValue(value)
            .replace(Regex("[^A-Za-z0-9_.:/-]"), "_")
            .take(MAX_TOKEN_LENGTH)
    }

    private fun safeIdentifier(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9-]"), "").take(MAX_IDENTIFIER_LENGTH)
    }

    private fun safeValue(value: String): String {
        return value
            .replace(bearerPattern, REDACTED)
            .replace(jwtPattern, REDACTED)
            .replace(emailPattern, REDACTED)
            .replace(urlQueryPattern, "$1=$REDACTED")
            .replace(secretAssignmentPattern, "$1=$REDACTED")
            .take(MAX_VALUE_LENGTH)
    }

    private const val REDACTED = "[REDACTED]"
    private const val MAX_TOKEN_LENGTH = 96
    private const val MAX_IDENTIFIER_LENGTH = 64
    private const val MAX_VALUE_LENGTH = 120
}

private fun legacyEvent(level: LogLevel, message: String): LogEvent {
    val eventName = message.substringBefore(' ').ifBlank { "legacy" }
    return LogEvent(
        level = level,
        tag = LogTag.DIAGNOSTICS,
        event = eventName,
        layer = LogLayer.DOMAIN,
        operation = message.substringAfter(' ', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() },
    )
}

internal expect object PlatformLogSink : LogSink
