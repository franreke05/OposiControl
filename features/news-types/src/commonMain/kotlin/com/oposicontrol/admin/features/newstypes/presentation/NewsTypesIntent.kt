package com.oposicontrol.admin.features.newstypes.presentation

sealed interface NewsTypesIntent {
    data object CreateNew : NewsTypesIntent
    data object Refresh : NewsTypesIntent
    data object Save : NewsTypesIntent
    data class Select(val id: String) : NewsTypesIntent
    data class UpdateSearch(val value: String) : NewsTypesIntent
    data class UpdateName(val value: String) : NewsTypesIntent
    data class UpdateSlug(val value: String) : NewsTypesIntent
    data class ToggleActive(val value: Boolean) : NewsTypesIntent
}
