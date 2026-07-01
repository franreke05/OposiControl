package com.oposicontrol.admin.features.users.di

import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.dsl.module

val usersFeatureModule = module {
    factory { UpsertUserUseCase(get()) }
    single { UsersViewModel(get(), get(), get(), get(), get(), get()) }
}
