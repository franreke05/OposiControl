package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.UserDto
import com.oposicontrol.admin.domain.model.User

fun UserDto.toDomain(): User {
    return User(
        id = id,
        fullName = fullName,
        email = email,
        roleIds = roleIds,
        isActive = isActive,
        createdAt = createdAt.toInstantOrNow(),
    )
}

fun User.toDto(): UserDto {
    return UserDto(
        id = id,
        fullName = fullName,
        email = email,
        roleIds = roleIds,
        isActive = isActive,
        createdAt = createdAt.toString(),
    )
}
