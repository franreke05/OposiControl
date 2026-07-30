package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.usecase.settings.ObserveSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.RefreshSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.UpdateSettingsUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val refreshSettingsUseCase: RefreshSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val appConfig: AppConfig,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<SettingsState, SettingsIntent>(
    initialState = SettingsState(environmentLabel = appConfig.environment.name),
    dispatcherProvider = dispatcherProvider,
) {
    private var lastPersistedSettings: AdminSettings? = null

    init {
        scope.launch {
            observeSettingsUseCase().collectLatest(::applySettings)
        }
        dispatch(SettingsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Refresh -> refresh()
            SettingsIntent.Save -> save()
            is SettingsIntent.TogglePreferFakeData -> updateEditor { it.copy(preferFakeData = intent.value) }
            is SettingsIntent.ToggleReleaseNotes -> updateEditor { it.copy(receiveReleaseNotes = intent.value) }
            is SettingsIntent.ToggleVerboseLogs -> updateEditor { it.copy(enableVerboseLogs = intent.value) }
            is SettingsIntent.UpdateDisplayName -> updateEditor { it.copy(displayName = intent.value) }
            is SettingsIntent.UpdateEmail -> updateEditor { it.copy(email = intent.value) }
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("settings")
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = refreshSettingsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                applySettings(result.value)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun save() {
        val state = currentState()
        if (state.actionState == UiAsync.Loading) return
        val settings = AdminSettings(
            displayName = state.displayName.trim(),
            email = state.email.trim(),
            receiveReleaseNotes = state.receiveReleaseNotes,
            defaultEnvironment = appConfig.environment,
            preferFakeData = state.preferFakeData,
            enableVerboseLogs = state.enableVerboseLogs,
        )
        if (settings == lastPersistedSettings) return

        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = updateSettingsUseCase(settings)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                applySettings(result.value)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun applySettings(settings: AdminSettings) {
        lastPersistedSettings = settings
        updateState {
            it.copy(
                settings = UiAsync.Success(settings),
                displayName = settings.displayName,
                email = settings.email,
                receiveReleaseNotes = settings.receiveReleaseNotes,
                preferFakeData = settings.preferFakeData,
                enableVerboseLogs = settings.enableVerboseLogs,
                environmentLabel = appConfig.environment.name,
            )
        }
    }

    private fun updateEditor(transform: (SettingsState) -> SettingsState) {
        updateState { transform(it).copy(actionState = UiAsync.Idle) }
    }
}
