package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.AuditEventDto
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.datetime.Instant

fun AuditEventDto.toDomain(): AuditEvent {
    return AuditEvent(
        id = id,
        actorName = actorName,
        action = action,
        target = target,
        occurredAt = Instant.parse(occurredAt),
        metadata = metadata,
    )
}
