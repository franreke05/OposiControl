package com.oposicontrol.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MASTER-ARCH-006: comandos INTERNOS de perfil y UN solo mapeo hacia el `p_patch` de `app_actualizar_perfil`.
 *
 * Antes el servicio recibia los DTOs del cable y armaba el JSON de la RPC con los nombres camelCase de la API HTTP: un
 * renombrado del cable habria exigido una migracion SQL. Ahora el DTO HTTP se traduce UNA vez ([toCommand]) y solo
 * [ProfileRpcContract] conoce los nombres de la RPC; `ProfileRpcContractTest` los compara con la lista permitida de la
 * migracion, asi que cualquier deriva falla en test.
 */
internal data class ProfileDetailUpdate(
    val edad: Int? = null,
    val ciudad: String? = null,
    val estadoLaboral: String? = null,
    val centroTrabajo: String? = null,
    val tiempoEstudioMin: Int? = null,
    val franja: String? = null,
    val fechaExamenObjetivo: String? = null,
)

internal data class ProfileUpdateCommand(
    val username: String? = null,
    val nombre: String? = null,
    val apellidos: String? = null,
    val especialidadId: String? = null,
    val comunidadPrincipalId: String? = null,
    val detail: ProfileDetailUpdate? = null,
    val targetCommunityIds: List<String>? = null,
    val introSeen: Boolean? = null,
)

/** Frontera HTTP -> dominio: el unico sitio donde el servicio de perfil toca el DTO del cable. */
internal fun ProfilePatchRequest.toCommand() = ProfileUpdateCommand(
    username = username,
    nombre = nombre,
    apellidos = apellidos,
    especialidadId = especialidadId,
    comunidadPrincipalId = comunidadPrincipalId,
    detail = detail?.let {
        ProfileDetailUpdate(
            edad = it.edad,
            ciudad = it.ciudad,
            estadoLaboral = it.estadoLaboral,
            centroTrabajo = it.centroTrabajo,
            tiempoEstudioMin = it.tiempoEstudioMin,
            franja = it.franja,
            fechaExamenObjetivo = it.fechaExamenObjetivo,
        )
    },
    targetCommunityIds = targetCommunityIds,
    introSeen = introSeen,
)

/** Nombres de clave del `p_patch` de `app_actualizar_perfil` (contrato de la RPC, independiente del cable HTTP). */
internal object ProfileRpcContract {
    val PATCH_KEYS = setOf(
        "username", "nombre", "apellidos", "especialidadId", "comunidadPrincipalId", "detail", "targetCommunityIds", "introSeen",
    )
    val DETAIL_KEYS = setOf(
        "edad", "ciudad", "estadoLaboral", "centroTrabajo", "tiempoEstudioMin", "franja", "fechaExamenObjetivo",
    )

    fun patch(command: ProfileUpdateCommand): JsonObject = buildJsonObject {
        command.username?.let { put("username", it) }
        command.nombre?.let { put("nombre", it.trim()) }
        command.apellidos?.let { put("apellidos", it.trim()) }
        command.especialidadId?.let { put("especialidadId", it.lowercase()) }
        command.comunidadPrincipalId?.let { put("comunidadPrincipalId", it.lowercase()) }
        command.detail?.let { d ->
            put(
                "detail",
                buildJsonObject {
                    d.edad?.let { put("edad", it) }
                    d.ciudad?.let { put("ciudad", it) }
                    d.estadoLaboral?.let { put("estadoLaboral", it) }
                    d.centroTrabajo?.let { put("centroTrabajo", it) }
                    d.tiempoEstudioMin?.let { put("tiempoEstudioMin", it) }
                    d.franja?.let { put("franja", it) }
                    d.fechaExamenObjetivo?.let { put("fechaExamenObjetivo", it) }
                },
            )
        }
        command.targetCommunityIds?.let { ids -> put("targetCommunityIds", JsonArray(ids.map { JsonPrimitive(it.lowercase()) })) }
        command.introSeen?.let { put("introSeen", it) }
    }
}
