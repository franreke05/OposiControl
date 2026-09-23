package com.oposicontrol.admin.features.specialties.di

import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
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
            // Los dos casos de uso de exámenes se CONSTRUYEN aquí sobre el
            // repositorio, en vez de resolverse del contenedor: `examsFeatureModule`
            // ya los declara, y declararlos otra vez dejaría la misma clase con dos
            // definiciones en Koin. Lo que se resuelve es `ContentRepository`, que es
            // lo que este módulo ya usa para todo lo demás.
            observeAllExamsUseCase = ObserveAllExamsUseCase(get()),
            refreshSpecialtiesUseCase = get(),
            refreshAllExamsUseCase = RefreshAllExamsUseCase(get()),
            upsertSpecialtyUseCase = get(),
            deleteSpecialtyUseCase = get(),
            getSpecialtyCapabilitiesUseCase = get(),
            saveSpecialtyCapabilitiesUseCase = get(),
            analyticsTracker = get(),
        )
    }
}
