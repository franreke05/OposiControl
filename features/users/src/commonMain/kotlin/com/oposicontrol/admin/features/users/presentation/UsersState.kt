package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User

data class UsersState(
    val users: UiAsync<List<User>> = UiAsync.Loading,
    val availableRoles: List<Role> = emptyList(),
    val selectedUserId: String? = null,
    val editorName: String = "",
    val editorEmail: String = "",
    val editorRoleIds: Set<String> = emptySet(),
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    // Quick-action dialogs
    val ticketsDialogOpen: Boolean = false,
    val ticketsInput: String = "",
    val warningDialogOpen: Boolean = false,
    val warningInput: String = "",
) {
    val isEmailValid: Boolean get() = editorEmail.isBlank() || EMAIL_REGEX.matches(editorEmail.trim())
    val canSave: Boolean get() = editorName.isNotBlank() && editorEmail.isNotBlank() && isEmailValid

    private companion object {
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
