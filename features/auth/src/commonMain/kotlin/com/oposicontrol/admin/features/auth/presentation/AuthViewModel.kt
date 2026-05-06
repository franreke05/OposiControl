package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
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
) : MviViewModel<AuthState, AuthIntent>(
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
            AuthIntent.Logout -> {
                logoutUseCase()
                updateState { it.copy(submitState = UiAsync.Idle, isRestoring = false) }
            }

            AuthIntent.RestoreSession -> restoreSession()
            AuthIntent.Submit -> submit()
            is AuthIntent.UpdateEmail -> updateState { it.copy(email = intent.value) }
            is AuthIntent.UpdatePassword -> updateState { it.copy(password = intent.value) }
        }
    }

    private suspend fun restoreSession() {
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

            is AppResult.Success -> updateState {
                it.copy(
                    currentSession = result.value,
                    isAuthenticated = true,
                    isRestoring = false,
                    submitState = UiAsync.Success(result.value),
                )
            }
        }
    }

    private suspend fun submit() {
        updateState { it.copy(submitState = UiAsync.Loading, isRestoring = false) }
        analyticsTracker.trackAction("auth_submit")
        when (
            val result = loginUseCase(
                AuthCredentials(
                    email = currentState().email,
                    password = currentState().password,
                ),
            )
        ) {
            is AppResult.Failure -> updateState { it.copy(submitState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    currentSession = result.value,
                    isAuthenticated = true,
                    submitState = UiAsync.Success(result.value),
                )
            }
        }
    }
}
