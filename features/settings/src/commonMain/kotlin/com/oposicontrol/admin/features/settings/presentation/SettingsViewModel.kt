package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val appConfig: AppConfig,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<SettingsState, SettingsIntent>(
    initialState = SettingsState(environmentLabel = appConfig.environment.name),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            settingsRepository.observeSettings().collectLatest(::applySettings)
        }
        scope.launch {
            authRepository.observeSession().collectLatest(::applySession)
        }
        dispatch(SettingsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Refresh -> refresh()
            SettingsIntent.Save -> save()
            SettingsIntent.Logout -> logout()
            is SettingsIntent.ToggleReleaseNotes -> updateState { it.copy(receiveReleaseNotes = intent.value) }
            is SettingsIntent.ToggleVerboseLogs -> updateState { it.copy(enableVerboseLogs = intent.value) }
            is SettingsIntent.UpdateDisplayName -> updateState { it.copy(displayName = intent.value) }
            is SettingsIntent.UpdateEmail -> updateState { it.copy(email = intent.value) }
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("settings")
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = settingsRepository.refreshSettings()) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                applySettings(result.value)
                updateState { it.copy(actionState = UiAsync.Idle) }
            }
        }
    }

    private suspend fun save() {
        updateState { it.copy(actionState = UiAsync.Loading) }
        val settings = AdminSettings(
            displayName = currentState().displayName,
            email = currentState().email,
            receiveReleaseNotes = currentState().receiveReleaseNotes,
            defaultEnvironment = appConfig.environment,
            enableVerboseLogs = currentState().enableVerboseLogs,
        )
        when (val result = settingsRepository.updateSettings(settings)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                applySettings(result.value)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun logout() {
        analyticsTracker.trackAction("settings.logout")
        updateState { it.copy(logoutState = UiAsync.Loading) }
        when (val result = authRepository.logout()) {
            is AppResult.Failure -> updateState { it.copy(logoutState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(logoutState = UiAsync.Success(Unit)) }
        }
    }

    private fun applySession(session: Session?) {
        val displayName = session?.displayName?.trim().orEmpty()
        if (session == null || displayName.isBlank()) {
            updateState { it.copy(profile = UiAsync.Empty) }
            return
        }

        val normalizedRoles = session.roleIds.map { it.trim().lowercase() }
        val roleLabel = when {
            normalizedRoles.any { it == "superadmin" || it == "super-admin" || it == "admin" } -> "Administrador"
            normalizedRoles.any { it == "moderador" || it == "moderator" } -> "Moderador"
            else -> "Usuario"
        }
        updateState {
            it.copy(profile = UiAsync.Success(SettingsProfile(displayName, roleLabel)))
        }
    }

    private fun applySettings(settings: AdminSettings) {
        updateState {
            it.copy(
                settings = UiAsync.Success(settings),
                displayName = settings.displayName,
                email = settings.email,
                receiveReleaseNotes = settings.receiveReleaseNotes,
                enableVerboseLogs = settings.enableVerboseLogs,
                environmentLabel = appConfig.environment.name,
            )
        }
    }
}
