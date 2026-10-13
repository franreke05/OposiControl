package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Ajustes de la vista estrecha y del panel compacto: quién ha iniciado sesión, en qué entorno, y cerrar sesión.
 *
 * Ya no depende de ningún almacenamiento local: «Preferencias» guardaba un nombre y un correo que no eran los de nadie
 * (arrancaban como «Admin Principal») y dos interruptores que ningún otro código consultaba.
 */
class SettingsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val authRepository: AuthRepository,
    appConfig: AppConfig,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<SettingsState, SettingsIntent>(
    initialState = SettingsState(environmentLabel = appConfig.environment.name),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            authRepository.observeSession().collectLatest(::applySession)
        }
    }

    override suspend fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Logout -> logout()
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
}
