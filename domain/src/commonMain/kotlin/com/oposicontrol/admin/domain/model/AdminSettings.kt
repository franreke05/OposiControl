package com.oposicontrol.admin.domain.model

import com.oposicontrol.admin.core.common.config.Environment

data class AdminSettings(
    val displayName: String,
    val email: String,
    val receiveReleaseNotes: Boolean,
    val defaultEnvironment: Environment,
    val enableVerboseLogs: Boolean,
)
