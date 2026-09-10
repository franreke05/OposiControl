package com.oposicontrol.admin.features.imports.di

import com.oposicontrol.admin.features.imports.presentation.ImportViewModel
import org.koin.dsl.module

val importFeatureModule = module {
    factory { ImportViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
}
