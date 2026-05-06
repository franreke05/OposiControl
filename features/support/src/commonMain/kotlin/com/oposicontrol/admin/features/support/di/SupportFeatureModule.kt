package com.oposicontrol.admin.features.support.di

import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import org.koin.dsl.module

val supportFeatureModule = module {
    factory { SupportViewModel(get(), get(), get()) }
}
