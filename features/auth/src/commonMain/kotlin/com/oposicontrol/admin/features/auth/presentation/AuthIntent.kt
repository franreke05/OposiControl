package com.oposicontrol.admin.features.auth.presentation

/** User actions accepted by the authentication state holder. */
sealed interface AuthIntent {
    data class UpdateEmail(val value: String) : AuthIntent
    data class UpdatePassword(val value: String) : AuthIntent
    data object RestoreSession : AuthIntent
    data object Submit : AuthIntent
    data object Logout : AuthIntent
}
