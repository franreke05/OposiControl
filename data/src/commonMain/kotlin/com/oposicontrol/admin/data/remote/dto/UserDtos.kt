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
)
