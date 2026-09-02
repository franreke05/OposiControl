package com.oposicontrol.admin.features.specialties.di

import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.SaveSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesViewModel
import org.koin.dsl.module

val specialtiesFeatureModule = module {
    factory { ObserveSpecialtiesUseCase(get()) }
    factory { RefreshSpecialtiesUseCase(get()) }
    factory { UpsertSpecialtyUseCase(get()) }
    factory { DeleteSpecialtyUseCase(get()) }
    factory { GetSpecialtyCapabilitiesUseCase(get()) }
    factory { SaveSpecialtyCapabilitiesUseCase(get()) }
    factory {
        SpecialtiesViewModel(
            dispatcherProvider = get(),
            observeSpecialtiesUseCase = get(),
            refreshSpecialtiesUseCase = get(),
            upsertSpecialtyUseCase = get(),
            deleteSpecialtyUseCase = get(),
            getSpecialtyCapabilitiesUseCase = get(),
            saveSpecialtyCapabilitiesUseCase = get(),
            analyticsTracker = get(),
        )
    }
}
