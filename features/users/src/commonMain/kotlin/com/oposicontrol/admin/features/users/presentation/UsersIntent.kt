package com.oposicontrol.admin.features.users.presentation

sealed interface UsersIntent {
    data object CreateNew : UsersIntent
    data class SelectUser(val userId: String) : UsersIntent
    data class UpdateName(val value: String) : UsersIntent
    data class UpdateEmail(val value: String) : UsersIntent
    data class UpdateRoleIds(val value: String) : UsersIntent
    data class ToggleActive(val value: Boolean) : UsersIntent
    data object Save : UsersIntent
    data object DeleteSelected : UsersIntent
}
