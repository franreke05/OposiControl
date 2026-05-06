package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuditEventDto(
    val id: String,
    val actorName: String,
    val action: String,
    val target: String,
    val occurredAt: String,
    val metadata: Map<String, String>,
)
