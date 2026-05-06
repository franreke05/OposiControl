package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.data.remote.dto.SettingsDto
import com.oposicontrol.admin.domain.model.AdminSettings

fun SettingsDto.toDomain(): AdminSettings {
    return AdminSettings(
        displayName = displayName,
        email = email,
        receiveReleaseNotes = receiveReleaseNotes,
        defaultEnvironment = Environment.from(defaultEnvironment),
        preferFakeData = preferFakeData,
        enableVerboseLogs = enableVerboseLogs,
    )
}

fun AdminSettings.toDto(): SettingsDto {
    return SettingsDto(
        displayName = displayName,
        email = email,
        receiveReleaseNotes = receiveReleaseNotes,
        defaultEnvironment = defaultEnvironment.name.lowercase(),
        preferFakeData = preferFakeData,
        enableVerboseLogs = enableVerboseLogs,
    )
}
