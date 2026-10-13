package com.oposicontrol.admin.features.auth.di

import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import com.oposicontrol.admin.features.auth.presentation.AccessRequestViewModel
import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import org.koin.dsl.module

val authFeatureModule = module {
    factory { LoginUseCase(get()) }
    factory { AuthViewModel(get(), get(), get(), get()) }
    // Formulario publico «Solicitar acceso» de la pantalla de acceso.
    factory { AccessRequestViewModel(get(), get()) }
}
