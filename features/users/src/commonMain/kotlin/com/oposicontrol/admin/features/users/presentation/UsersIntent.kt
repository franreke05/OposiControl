package com.oposicontrol.admin.features.users.presentation

sealed interface UsersIntent {
    data object CreateNew : UsersIntent
    data class SelectUser(val userId: String) : UsersIntent
    data class UpdateName(val value: String) : UsersIntent
    data class UpdateEmail(val value: String) : UsersIntent
    data class ToggleRole(val roleId: String) : UsersIntent
    data class ToggleActive(val value: Boolean) : UsersIntent
    data class GrantTickets(val amount: Int) : UsersIntent
    data object Save : UsersIntent
    data object DeleteSelected : UsersIntent
    // Quick-action dialogs
    data object OpenTicketsDialog : UsersIntent
    data object DismissTicketsDialog : UsersIntent
    data class UpdateTicketsInput(val value: String) : UsersIntent
    data object ConfirmTickets : UsersIntent
    data object OpenWarningDialog : UsersIntent
    data object DismissWarningDialog : UsersIntent
    data class UpdateWarningInput(val value: String) : UsersIntent
    data object ConfirmWarning : UsersIntent
    data class QuickToggleBlock(val userId: String) : UsersIntent
}
