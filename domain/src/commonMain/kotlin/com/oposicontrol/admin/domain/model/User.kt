package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class User(
    val id: String,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    val isActive: Boolean,
    val createdAt: Instant,
)
