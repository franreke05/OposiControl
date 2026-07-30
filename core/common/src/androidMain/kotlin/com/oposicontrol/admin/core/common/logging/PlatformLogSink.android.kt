package com.oposicontrol.admin.core.common.logging

import android.util.Log

internal actual object PlatformLogSink : LogSink {
    override fun write(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val safeMessage = throwable?.let {
            "$message\n${it.toSafeStackTrace()}"
        } ?: message
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, safeMessage)
            LogLevel.INFO -> Log.i(tag, safeMessage)
            LogLevel.WARN -> Log.w(tag, safeMessage)
            LogLevel.ERROR -> Log.e(tag, safeMessage)
        }
    }
}

private fun Throwable.toSafeStackTrace(): String {
    return buildString {
        var current: Throwable? = this@toSafeStackTrace
        var causeDepth = 0
        var remainingFrames = MAX_STACK_FRAMES
        while (current != null && causeDepth < MAX_CAUSE_DEPTH && remainingFrames > 0) {
            append(if (causeDepth == 0) "stacktrace=" else "\ncausedBy=")
            append(current.javaClass.name.toSafeCodeToken())
            val frames = current.stackTrace.take(
                minOf(remainingFrames, MAX_FRAMES_PER_THROWABLE),
            )
            frames.forEach { frame ->
                append("\n\tat ")
                append(frame.className.toSafeCodeToken())
                append('.')
                append(frame.methodName.toSafeCodeToken())
                if (frame.lineNumber >= 0) {
                    append(':').append(frame.lineNumber)
                }
            }
            remainingFrames -= frames.size
            current = current.cause
            causeDepth += 1
        }
        if (current != null) {
            append("\nstackTruncated=true")
        }
    }
}

private fun String.toSafeCodeToken(): String {
    return filter { character ->
        character.isLetterOrDigit() || character in "._$<>/-"
    }.take(MAX_CODE_TOKEN_LENGTH).ifBlank { "Throwable" }
}

private const val MAX_CAUSE_DEPTH = 4
private const val MAX_STACK_FRAMES = 32
private const val MAX_FRAMES_PER_THROWABLE = 16
private const val MAX_CODE_TOKEN_LENGTH = 180
