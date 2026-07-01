package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Session

data class AuthState(
    val email: String = "",
    val password: String = "",
    val submitState: UiAsync<Session> = UiAsync.Idle,
    val currentSession: Session? = null,
    val isAuthenticated: Boolean = false,
    val isRestoring: Boolean = true,
    val isSubmitting: Boolean = false,
)
