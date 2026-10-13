package com.oposicontrol.admin.features.users.di

import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import com.oposicontrol.admin.features.users.presentation.AccessRequestsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.dsl.module

val usersFeatureModule = module {
    factory { UpsertUserUseCase(get()) }
    single { UsersViewModel(get(), get(), get(), get(), get(), get()) }
    // Una sola instancia: el hub de Usuarios lee de ella si la pestana «Solicitudes de acceso» existe para esta sesion.
    single { AccessRequestsViewModel(get(), get(), get()) }
}
