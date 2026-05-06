package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.LoginRequestDto
import com.oposicontrol.admin.data.remote.dto.SessionDto
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import kotlinx.datetime.Instant

fun AuthCredentials.toDto(): LoginRequestDto {
    return LoginRequestDto(
        email = email,
        password = password,
    )
}

fun SessionDto.toDomain(): Session {
    return Session(
        userId = userId,
        displayName = displayName,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.parse(expiresAt),
        roleIds = roleIds,
    )
}
