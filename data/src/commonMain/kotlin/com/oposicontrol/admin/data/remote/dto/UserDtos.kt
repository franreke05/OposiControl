package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    /** `!es_invitado` legacy; null si el backend no lo informa (nunca se deriva de `accountStatus`). */
    val isActive: Boolean?,
    /** Instante ISO; null si el backend no lo informa (no se fabrica una fecha). */
    val createdAt: String?,
    val ticketsBalance: Int? = null,
    /** Wire `AccountStatus` (activa|bloqueada|eliminacion_pendiente); null si el backend no lo emite. */
    val accountStatus: String? = null,
    val username: String? = null,
    val authProviders: List<String> = emptyList(),
    val onboardingCompleted: Boolean? = null,
)
