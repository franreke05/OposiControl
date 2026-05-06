package com.oposicontrol.admin.features.settings.presentation

sealed interface SettingsIntent {
    data class UpdateDisplayName(val value: String) : SettingsIntent
    data class UpdateEmail(val value: String) : SettingsIntent
    data class ToggleReleaseNotes(val value: Boolean) : SettingsIntent
    data class TogglePreferFakeData(val value: Boolean) : SettingsIntent
    data class ToggleVerboseLogs(val value: Boolean) : SettingsIntent
    data object Save : SettingsIntent
    data object Refresh : SettingsIntent
}
