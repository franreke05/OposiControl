package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class RoleDto(
    val id: String,
    val name: String,
    val permissions: List<PermissionDto>,
)

@Serializable
data class PermissionDto(
    val key: String,
    val label: String,
)
