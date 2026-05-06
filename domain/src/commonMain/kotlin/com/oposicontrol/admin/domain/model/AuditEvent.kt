package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class AuditEvent(
    val id: String,
    val actorName: String,
    val action: String,
    val target: String,
    val occurredAt: Instant,
    val metadata: Map<String, String>,
)
