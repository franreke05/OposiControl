package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.AuditMovementDetailDto
import com.oposicontrol.admin.data.remote.dto.AuditMovementDto
import com.oposicontrol.admin.data.remote.dto.AuditMovementsPageDto
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditMovement
import com.oposicontrol.admin.domain.model.AuditPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun AuditMovementsPageDto.toDomain(): AuditPage = AuditPage(
    items = items.map { it.toDomain() },
    nextCursor = nextCursor?.takeIf { it.isNotBlank() },
)

fun AuditMovementDto.toDomain(): AuditMovement = AuditMovement(
    id = id,
    // Un texto en blanco no es un dato: se trata igual que su ausencia, para que la pantalla no enseñe un hueco.
    actorId = actorId?.takeIf { it.isNotBlank() },
    actorName = actorNombre?.takeIf { it.isNotBlank() },
    action = accion,
    table = tabla?.takeIf { it.isNotBlank() },
    recordId = registroId?.takeIf { it.isNotBlank() },
    occurredAt = creadoEn,
)

fun AuditMovementDetailDto.toDomain(): AuditDetail = AuditDetail(
    id = id,
    before = antes.toAuditFields(),
    after = despues.toAuditFields(),
)

/**
 * Aplana un JSON libre a pares clave → valor, en el orden en que venían.
 *
 * Aquí solo se decide la ESTRUCTURA; cómo se llama cada clave en pantalla lo decide la pantalla.
 * - `null` o JSON `null` → `null`: ese lado no se guardó.
 * - objeto dentro de objeto → clave con punto (`perfil.nombre`).
 * - lista de valores sueltos → un solo par con los valores separados por comas (`roleIds` → «a, b»); lista vacía → valor vacío.
 * - lista de objetos → un par por elemento y campo, numerados desde 1 (`tarifas.1.precio`).
 * - algo que no es un objeto en la raíz → un único par con la clave vacía.
 */
internal fun JsonElement?.toAuditFields(): List<AuditField>? {
    if (this == null || this is JsonNull) return null
    val out = mutableListOf<AuditField>()
    flattenInto(prefix = "", element = this, out = out)
    return out
}

private fun flattenInto(prefix: String, element: JsonElement, out: MutableList<AuditField>) {
    when (element) {
        is JsonNull -> out += AuditField(prefix, null)
        is JsonPrimitive -> out += AuditField(prefix, element.content)
        is JsonObject -> element.forEach { (key, value) -> flattenInto(if (prefix.isEmpty()) key else "$prefix.$key", value, out) }
        is JsonArray -> {
            if (element.all { it is JsonPrimitive }) {
                out += AuditField(prefix, element.joinToString(", ") { (it as JsonPrimitive).takeIf { v -> v !is JsonNull }?.content.orEmpty() })
            } else {
                element.forEachIndexed { index, value -> flattenInto(if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}", value, out) }
            }
        }
    }
}
