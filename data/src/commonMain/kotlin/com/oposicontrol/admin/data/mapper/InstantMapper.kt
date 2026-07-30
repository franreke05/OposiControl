package com.oposicontrol.admin.data.mapper

import kotlinx.datetime.Instant
import kotlin.time.Clock

internal fun String.toInstantOrNow(): Instant {
    if (isBlank()) return Clock.System.now()
    return runCatching { Instant.parse(this) }.getOrElse { Clock.System.now() }
}
