package com.oposicontrol.admin.domain.model

data class Role(
    val id: String,
    val name: String,
    val permissions: List<Permission>,
)

data class Permission(
    val key: String,
    val label: String,
)
