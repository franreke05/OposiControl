package com.oposicontrol.admin.features.moderation.di

import com.oposicontrol.admin.features.moderation.presentation.ForumModerationViewModel
import com.oposicontrol.admin.features.moderation.presentation.ModerationViewModel
import org.koin.dsl.module

val moderationFeatureModule = module {
    factory { ModerationViewModel(get(), get(), get()) }
    single { ForumModerationViewModel(get(), get(), get()) }
}
