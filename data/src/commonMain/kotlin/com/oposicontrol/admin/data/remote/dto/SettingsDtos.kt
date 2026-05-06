package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SettingsDto(
    val displayName: String,
    val email: String,
    val receiveReleaseNotes: Boolean,
    val defaultEnvironment: String,
    val preferFakeData: Boolean,
    val enableVerboseLogs: Boolean,
)
