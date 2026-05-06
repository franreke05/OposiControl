package com.oposicontrol.admin.domain.usecase.settings

import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.repository.SettingsRepository

class UpdateSettingsUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(settings: AdminSettings) = repository.updateSettings(settings)
}
