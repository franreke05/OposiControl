package com.oposicontrol.admin.domain.usecase.settings

import com.oposicontrol.admin.domain.repository.SettingsRepository

class ObserveSettingsUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke() = repository.observeSettings()
}
