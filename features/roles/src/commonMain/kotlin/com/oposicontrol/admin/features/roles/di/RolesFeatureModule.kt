package com.oposicontrol.admin.features.roles.di

import com.oposicontrol.admin.domain.usecase.roles.ObserveRolesUseCase
import com.oposicontrol.admin.domain.usecase.roles.UpsertRoleUseCase
import com.oposicontrol.admin.features.roles.presentation.RolesViewModel
import org.koin.dsl.module

val rolesFeatureModule = module {
    factory { ObserveRolesUseCase(get()) }
    factory { UpsertRoleUseCase(get()) }
    factory { RolesViewModel(get(), get(), get(), get()) }
}
