package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `GET /api/v1/admin/auditoria/movimientos`. */
@Serializable
data class AuditMovementsPageDto(
    val items: List<AuditMovementDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class AuditMovementDto(
    val id: String = "",
    val actorId: String? = null,
    val actorNombre: String? = null,
    val accion: String = "",
    val tabla: String? = null,
    val registroId: String? = null,
    val creadoEn: String = "",
)

/** `GET /api/v1/admin/auditoria/{id}`. `antes`/`despues` son JSON libre: cada acción guarda lo suyo. */
@Serializable
data class AuditMovementDetailDto(
    val id: String = "",
    val antes: JsonElement? = null,
    val despues: JsonElement? = null,
)
