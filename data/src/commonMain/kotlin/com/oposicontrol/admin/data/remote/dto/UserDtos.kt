package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    val isActive: Boolean,
    val createdAt: String,
    val ticketsBalance: Int = 0,
    /** Wire `AccountStatus` (activa|bloqueada|eliminacion_pendiente); null si el backend no lo emite. */
    val accountStatus: String? = null,
    val username: String? = null,
    val authProviders: List<String> = emptyList(),
    val onboardingCompleted: Boolean? = null,
)
