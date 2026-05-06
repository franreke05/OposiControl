package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.SettingsApi
import com.oposicontrol.admin.domain.model.AdminSettings

class RealSettingsDataSource(
    private val api: SettingsApi,
    private val adapter: NetworkResultAdapter,
) : SettingsDataSource {
    override suspend fun getSettings(): AppResult<AdminSettings> {
        return adapter.safeCall {
            api.getSettings().toDomain()
        }
    }

    override suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings> {
        return adapter.safeCall {
            api.updateSettings(settings.toDto()).toDomain()
        }
    }
}
