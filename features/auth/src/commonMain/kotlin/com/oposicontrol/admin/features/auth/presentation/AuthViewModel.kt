package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthViewModel(
    dispatcherProvider: DispatcherProvider,
    private val loginUseCase: LoginUseCase,
    private val authRepository: AuthRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<AuthState, AuthIntent>(
    initialState = AuthState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var restoreAttempted = false

    init {
        scope.launch {
            authRepository.observeSession().collectLatest { session ->
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
                authRepository.logout()
                updateState { it.copy(submitState = UiAsync.Idle, isRestoring = false) }
            }

            AuthIntent.RestoreSession -> if (!currentState().isSubmitting) restoreSession()
            AuthIntent.Submit -> submit()
            is AuthIntent.UpdateEmail -> updateState {
                it.copy(
                    email = intent.value,
                    submitState = if (it.submitState is UiAsync.Error) UiAsync.Idle else it.submitState,
                )
            }
            is AuthIntent.UpdatePassword -> updateState {
                it.copy(
                    password = intent.value,
                    submitState = if (it.submitState is UiAsync.Error) UiAsync.Idle else it.submitState,
                )
            }
        }
    }

    private suspend fun restoreSession() {
        updateState { it.copy(isRestoring = true) }
        when (val result = authRepository.refreshSession()) {
            is AppResult.Failure -> {
                authRepository.logout()
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
        if (currentState().isSubmitting) return
        updateState { it.copy(isSubmitting = true, submitState = UiAsync.Loading, isRestoring = false) }
        analyticsTracker.trackAction("auth_submit")
        when (
            val result = loginUseCase(
                AuthCredentials(
                    email = currentState().email,
                    password = currentState().password,
                ),
            )
        ) {
            is AppResult.Failure -> updateState { it.copy(isSubmitting = false, submitState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    isSubmitting = false,
                    currentSession = result.value,
                    isAuthenticated = true,
                    submitState = UiAsync.Success(result.value),
                )
            }
        }
    }
}
