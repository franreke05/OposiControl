package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviEffectViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import com.oposicontrol.admin.domain.usecase.auth.LogoutUseCase
import com.oposicontrol.admin.domain.usecase.auth.ObserveSessionUseCase
import com.oposicontrol.admin.domain.usecase.auth.RefreshSessionUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthViewModel(
    dispatcherProvider: DispatcherProvider,
    private val loginUseCase: LoginUseCase,
    private val logoutUseCase: LogoutUseCase,
    observeSessionUseCase: ObserveSessionUseCase,
    private val refreshSessionUseCase: RefreshSessionUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviEffectViewModel<AuthState, AuthIntent, AuthEffect>(
    initialState = AuthState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var restoreAttempted = false

    init {
        scope.launch {
            observeSessionUseCase().collectLatest { session ->
                updateState {
                    it.copy(
                        currentSession = session,
                        isAuthenticated = session != null,
                        submitState = if (session == null) it.submitState else UiAsync.Success(session),
                    )
                }
                if (!restoreAttempted) {
                    restoreAttempted = true
                    if (session == null) {
                        updateState { it.copy(isRestoring = false) }
                    } else {
                        dispatch(AuthIntent.RestoreSession)
                    }
                }
            }
        }
    }

    override suspend fun handleIntent(intent: AuthIntent) {
        when (intent) {
            AuthIntent.Logout -> logout()
            AuthIntent.RestoreSession -> restoreSession()
            AuthIntent.Submit -> submit()
            is AuthIntent.UpdateEmail -> updateState {
                it.copy(email = intent.value, submitState = UiAsync.Idle)
            }

            is AuthIntent.UpdatePassword -> updateState {
                it.copy(password = intent.value, submitState = UiAsync.Idle)
            }
        }
    }

    private suspend fun logout() {
        when (val result = logoutUseCase()) {
            is AppResult.Failure -> updateState {
                it.copy(submitState = UiAsync.Error(result.error), isRestoring = false)
            }

            is AppResult.Success -> updateState {
                it.copy(
                    password = "",
                    submitState = UiAsync.Idle,
                    currentSession = null,
                    isAuthenticated = false,
                    isRestoring = false,
                )
            }
        }
    }

    private suspend fun restoreSession() {
        if (currentState().isRestoring && currentState().submitState is UiAsync.Loading) return
        updateState { it.copy(isRestoring = true) }
        when (val result = refreshSessionUseCase()) {
            is AppResult.Failure -> {
                logoutUseCase()
                updateState {
                    it.copy(
                        currentSession = null,
                        isAuthenticated = false,
                        isRestoring = false,
                        submitState = UiAsync.Error(result.error),
                    )
                }
            }

            is AppResult.Success -> {
                updateState {
                    it.copy(
                        currentSession = result.value,
                        isAuthenticated = true,
                        isRestoring = false,
                        submitState = UiAsync.Success(result.value),
                    )
                }
                emitEffect(AuthEffect.Authenticated)
            }
        }
    }

    private suspend fun submit() {
        val state = currentState()
        if (state.isAuthenticated || state.submitState is UiAsync.Loading) return
        val credentials = AuthCredentials(
            email = state.email.trim(),
            password = state.password,
        )
        updateState { it.copy(submitState = UiAsync.Loading, isRestoring = false) }
        analyticsTracker.trackAction("auth_submit")
        when (val result = loginUseCase(credentials)) {
            is AppResult.Failure -> updateState { it.copy(submitState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        password = "",
                        currentSession = result.value,
                        isAuthenticated = true,
                        submitState = UiAsync.Success(result.value),
                    )
                }
                emitEffect(AuthEffect.Authenticated)
            }
        }
    }
}
