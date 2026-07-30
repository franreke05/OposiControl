package com.oposicontrol.admin.core.common.logging

import platform.Foundation.NSLog

internal actual object PlatformLogSink : LogSink {
    override fun write(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // Kotlin/Native does not expose portable frames here. Keep the safe type
        // in the same log record and never forward Throwable.message.
        val suffix = throwable?.let {
            "\nstacktrace=${it::class.simpleName?.toSafeCodeToken() ?: "Throwable"}"
        }.orEmpty()
        NSLog("%@", "${level.name} $tag | $message$suffix")
    }
}

private fun String.toSafeCodeToken(): String {
    return filter { character ->
        character.isLetterOrDigit() || character in "._$<>/-"
    }.take(MAX_CODE_TOKEN_LENGTH).ifBlank { "Throwable" }
}

private const val MAX_CODE_TOKEN_LENGTH = 180
