package com.oposicontrol.admin.features.tags.presentation

sealed interface TagsIntent {
    data object CreateNew : TagsIntent
    data object Refresh : TagsIntent
    data object Save : TagsIntent
    data object DeleteSelected : TagsIntent
    data class Select(val id: String) : TagsIntent
    data class UpdateSearch(val value: String) : TagsIntent
    data class UpdateName(val value: String) : TagsIntent
    data class UpdateSlug(val value: String) : TagsIntent
}
