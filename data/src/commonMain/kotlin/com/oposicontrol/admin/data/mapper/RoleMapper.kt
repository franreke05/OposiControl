package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.PermissionDto
import com.oposicontrol.admin.data.remote.dto.RoleDto
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role

fun RoleDto.toDomain(): Role {
    return Role(
        id = id,
        name = name,
        permissions = permissions.map { it.toDomain() },
    )
}

fun PermissionDto.toDomain(): Permission {
    return Permission(
        key = key,
        label = label,
    )
}

fun Role.toDto(): RoleDto {
    return RoleDto(
        id = id,
        name = name,
        permissions = permissions.map { it.toDto() },
    )
}

fun Permission.toDto(): PermissionDto {
    return PermissionDto(
        key = key,
        label = label,
    )
}
