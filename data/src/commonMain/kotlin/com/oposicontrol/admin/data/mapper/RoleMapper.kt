package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.PermissionDto
import com.oposicontrol.admin.data.remote.dto.RoleDto
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role

// Solo DTO -> dominio: el catálogo de roles se lee, nunca se envía (los mapeadores de vuelta solo servían a un
// `upsertRole` que siempre lanzaba).

fun RoleDto.toDomain(): Role {
    return Role(
        id = id,
        name = name,
        code = code,
        permissions = permissions.map { it.toDomain() },
    )
}

fun PermissionDto.toDomain(): Permission {
    return Permission(
        key = key,
        label = label,
    )
}
