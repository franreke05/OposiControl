package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSettings

interface SettingsDataSource {
    suspend fun getSettings(): AppResult<AdminSettings>
    suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings>
}
