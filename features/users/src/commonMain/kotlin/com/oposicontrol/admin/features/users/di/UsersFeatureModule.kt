package com.oposicontrol.admin.features.users.di

import com.oposicontrol.admin.domain.usecase.users.DeleteUserUseCase
import com.oposicontrol.admin.domain.usecase.users.ObserveUsersUseCase
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.dsl.module

val usersFeatureModule = module {
    factory { ObserveUsersUseCase(get()) }
    factory { UpsertUserUseCase(get()) }
    factory { DeleteUserUseCase(get()) }
    factory { UsersViewModel(get(), get(), get(), get(), get()) }
}
