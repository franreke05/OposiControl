package com.oposicontrol.admin.features.settings.di

import com.oposicontrol.admin.domain.usecase.settings.ObserveSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.RefreshSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.UpdateSettingsUseCase
import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import org.koin.dsl.module

val settingsFeatureModule = module {
    factory { ObserveSettingsUseCase(get()) }
    factory { RefreshSettingsUseCase(get()) }
    factory { UpdateSettingsUseCase(get()) }
    single { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
}
