package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<AdminSettings>
    suspend fun refreshSettings(): AppResult<AdminSettings>
    suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings>
}
