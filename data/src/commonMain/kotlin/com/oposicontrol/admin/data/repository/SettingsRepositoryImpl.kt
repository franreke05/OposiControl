package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.onSuccess
import com.oposicontrol.admin.data.local.SettingsLocalDataSource
import com.oposicontrol.admin.data.remote.source.SettingsDataSource
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class SettingsRepositoryImpl(
    private val remote: SettingsDataSource,
    private val local: SettingsLocalDataSource,
) : SettingsRepository {
    override fun observeSettings(): Flow<AdminSettings> = local.observeSettings()

    override suspend fun refreshSettings() = remote.getSettings().onSuccess(local::save)

    override suspend fun updateSettings(settings: AdminSettings) = remote
        .updateSettings(settings)
        .onSuccess(local::save)
}
