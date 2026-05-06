package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.SettingsDataSource
import com.oposicontrol.admin.domain.model.AdminSettings

class FakeSettingsDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : SettingsDataSource {
    override suspend fun getSettings(): AppResult<AdminSettings> {
        return behavior.execute("settings.get") { store.settings.value }
    }

    override suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings> {
        return behavior.execute("settings.update") {
            store.replaceSettings(settings)
            settings
        }
    }
}
