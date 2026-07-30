package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.AuditEventDto
import com.oposicontrol.admin.domain.model.AuditEvent

fun AuditEventDto.toDomain(): AuditEvent {
    return AuditEvent(
        id = id,
        actorName = actorName,
        action = action,
        target = target,
        occurredAt = occurredAt.toInstantOrNow(),
        metadata = metadata,
    )
}

fun AuditEvent.toDto(): AuditEventDto {
    return AuditEventDto(
        id = id,
        actorName = actorName,
        action = action,
        target = target,
        occurredAt = occurredAt.toString(),
        metadata = metadata,
    )
}
