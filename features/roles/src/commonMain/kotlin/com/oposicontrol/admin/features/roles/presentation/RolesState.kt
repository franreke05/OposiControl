package com.oposicontrol.admin.features.roles.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Role

data class RolesState(
    val roles: UiAsync<List<Role>> = UiAsync.Loading,
    val selectedRoleId: String? = null,
    val editorName: String = "",
    val editorPermissions: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
