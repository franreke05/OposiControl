package com.oposicontrol.admin.features.examaccess.di

import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import org.koin.dsl.module

val examAccessFeatureModule = module {
    single { ExamAccessViewModel(get(), get(), get()) }
}
