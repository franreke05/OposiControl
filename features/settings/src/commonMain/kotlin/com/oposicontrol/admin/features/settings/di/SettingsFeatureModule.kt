package com.oposicontrol.admin.features.settings.di

import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import org.koin.dsl.module

val settingsFeatureModule = module {
    single { SettingsViewModel(get(), get(), get(), get(), get()) }
}
