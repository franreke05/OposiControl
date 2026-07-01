package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.domain.model.AdminSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsLocalDataSource(
    private val database: AdminDatabase,
    appConfig: AppConfig,
) {
    private val state = MutableStateFlow(readSettings() ?: defaultSettings(appConfig))

    init {
        if (readSettings() == null) {
            save(state.value)
        }
    }

    fun observeSettings(): Flow<AdminSettings> = state.asStateFlow()

    fun currentSettings(): AdminSettings = state.value

    fun save(settings: AdminSettings) {
        database.appSettingsQueries.upsertSettings(
            display_name = settings.displayName,
            email = settings.email,
            receive_release_notes = settings.receiveReleaseNotes.toLong(),
            default_environment = settings.defaultEnvironment.name,
            prefer_fake_data = 0L,
            enable_verbose_logs = settings.enableVerboseLogs.toLong(),
        )
        state.value = settings
    }

    private fun readSettings(): AdminSettings? {
        return database.appSettingsQueries.selectSettings(
            mapper = { _, displayName, email, receiveReleaseNotes, defaultEnvironment, _, enableVerboseLogs ->
                AdminSettings(
                    displayName = displayName,
                    email = email,
                    receiveReleaseNotes = receiveReleaseNotes == 1L,
                    defaultEnvironment = com.oposicontrol.admin.core.common.config.Environment.from(defaultEnvironment),
                    enableVerboseLogs = enableVerboseLogs == 1L,
                )
            },
        ).executeAsOneOrNull()
    }

    private fun defaultSettings(appConfig: AppConfig): AdminSettings {
        return AdminSettings(
            displayName = "Admin Principal",
            email = "admin@oposicontrol.local",
            receiveReleaseNotes = true,
            defaultEnvironment = appConfig.environment,
            enableVerboseLogs = appConfig.featureFlags.enableLogs,
        )
    }

    private fun Boolean.toLong(): Long = if (this) 1 else 0
}
