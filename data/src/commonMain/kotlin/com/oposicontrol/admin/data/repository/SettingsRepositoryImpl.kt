package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.SettingsLocalDataSource
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class SettingsRepositoryImpl(
    private val local: SettingsLocalDataSource,
) : SettingsRepository {
    override fun observeSettings(): Flow<AdminSettings> = local.observeSettings()

    override suspend fun refreshSettings(): AppResult<AdminSettings> = AppResult.Success(local.currentSettings())

    override suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings> {
        local.save(settings)
        return AppResult.Success(settings)
    }
}
