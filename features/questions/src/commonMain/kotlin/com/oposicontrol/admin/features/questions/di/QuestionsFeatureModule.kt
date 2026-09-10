package com.oposicontrol.admin.features.questions.di

import com.oposicontrol.admin.features.questions.presentation.QuestionsViewModel
import org.koin.dsl.module

val questionsFeatureModule = module {
    factory { QuestionsViewModel(get(), get(), get()) }
}
