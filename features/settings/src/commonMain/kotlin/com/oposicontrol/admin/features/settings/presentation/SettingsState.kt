package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.AdminSettings

data class SettingsState(
    val settings: UiAsync<AdminSettings> = UiAsync.Loading,
    val displayName: String = "",
    val email: String = "",
    val receiveReleaseNotes: Boolean = false,
    val enableVerboseLogs: Boolean = false,
    val environmentLabel: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
