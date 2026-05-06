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
}
