package com.oposicontrol.admin.features.communities.presentation

sealed interface CommunitiesIntent {
    data object CreateNew : CommunitiesIntent
    data object Refresh : CommunitiesIntent
    data object Save : CommunitiesIntent
    data object DeleteSelected : CommunitiesIntent
    data class Select(val id: String) : CommunitiesIntent
    data class UpdateSearch(val value: String) : CommunitiesIntent
    data class UpdateName(val value: String) : CommunitiesIntent
    data class UpdateCode(val value: String) : CommunitiesIntent
    data class UpdateSlug(val value: String) : CommunitiesIntent
    data class UpdateSortOrder(val value: String) : CommunitiesIntent
    data class ToggleActive(val value: Boolean) : CommunitiesIntent
}
