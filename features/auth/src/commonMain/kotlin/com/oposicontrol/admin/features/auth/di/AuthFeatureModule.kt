package com.oposicontrol.admin.features.auth.di

import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import com.oposicontrol.admin.domain.usecase.auth.LogoutUseCase
import com.oposicontrol.admin.domain.usecase.auth.ObserveSessionUseCase
import com.oposicontrol.admin.domain.usecase.auth.RefreshSessionUseCase
import org.koin.dsl.module

val authFeatureModule = module {
    factory { LoginUseCase(get()) }
    factory { LogoutUseCase(get()) }
    factory { ObserveSessionUseCase(get()) }
    factory { RefreshSessionUseCase(get()) }
    factory { AuthViewModel(get(), get(), get(), get(), get(), get()) }
}
