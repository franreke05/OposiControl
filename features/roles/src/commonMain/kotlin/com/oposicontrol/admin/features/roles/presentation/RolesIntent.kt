package com.oposicontrol.admin.features.roles.presentation

sealed interface RolesIntent {
    data object CreateNew : RolesIntent
    data class SelectRole(val roleId: String) : RolesIntent
    data class UpdateName(val value: String) : RolesIntent
    data class UpdatePermissions(val value: String) : RolesIntent
    data object Save : RolesIntent
}
