package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.User

data class UsersState(
    val users: UiAsync<List<User>> = UiAsync.Loading,
    val selectedUserId: String? = null,
    val editorName: String = "",
    val editorEmail: String = "",
    val editorRoleIds: String = "",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
