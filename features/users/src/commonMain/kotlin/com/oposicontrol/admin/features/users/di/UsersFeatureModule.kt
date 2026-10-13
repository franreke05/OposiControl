package com.oposicontrol.admin.features.users.di

import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import com.oposicontrol.admin.features.users.presentation.AccessRequestsViewModel
import com.oposicontrol.admin.features.users.presentation.AdminsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.dsl.module

val usersFeatureModule = module {
    factory { UpsertUserUseCase(get()) }
    single { UsersViewModel(get(), get(), get(), get(), get(), get()) }
    // Una sola instancia: el hub de Sistema lee de ella el aviso de solicitudes pendientes, y la pestaña
    // «Administradores», si esta sesión puede revisarlas.
    single { AccessRequestsViewModel(get(), get(), get()) }
    // `factory`, como el de «Estado»: cada vez que se abre «Administradores» se lee la lista de nuevo, así que un rol
    // concedido o una baja hecha en Usuarios se ve al volver y nada de una sesión anterior se queda en memoria.
    factory { AdminsViewModel(get(), get(), get()) }
}
