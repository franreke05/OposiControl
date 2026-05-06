package com.oposicontrol.admin.domain.usecase.settings

import com.oposicontrol.admin.domain.repository.SettingsRepository

class RefreshSettingsUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke() = repository.refreshSettings()
}
