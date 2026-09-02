package com.oposicontrol.admin.features.specialties.presentation

sealed interface SpecialtiesIntent {
    data object CreateNew : SpecialtiesIntent
    data object Refresh : SpecialtiesIntent
    data object Save : SpecialtiesIntent
    data object DeleteSelected : SpecialtiesIntent
    data class Select(val id: String) : SpecialtiesIntent
    data class UpdateSearch(val value: String) : SpecialtiesIntent
    data class UpdateName(val value: String) : SpecialtiesIntent
    data class UpdateDescription(val value: String) : SpecialtiesIntent
    data class UpdateSortOrder(val value: String) : SpecialtiesIntent
    data class ToggleActive(val value: Boolean) : SpecialtiesIntent

    // Capacidades: que modos existen realmente para esta especialidad. Se editan
    // y se guardan aparte del resto del formulario porque van a otra tabla y a
    // otro endpoint, y porque cambiarlas altera lo que ve el usuario final.
    data class ToggleCapability(val capability: SpecialtyCapabilityToggle, val value: Boolean) : SpecialtiesIntent
    data class UpdateLimitationMessage(val value: String) : SpecialtiesIntent
    data object SaveCapabilities : SpecialtiesIntent
}

/** Los cinco interruptores de `capacidades_especialidad`. */
enum class SpecialtyCapabilityToggle {
    RAFAGAS,
    RAZONADOS,
    INMERSIVOS,
    DEMOS,
    OPOSIMARKET,
}
