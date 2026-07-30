package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.repository.SettingsRepository
import com.oposicontrol.admin.domain.usecase.settings.ObserveSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.RefreshSettingsUseCase
import com.oposicontrol.admin.domain.usecase.settings.UpdateSettingsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `double save persists every editable setting once`() = runTest {
        val repository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeSettingsUseCase = ObserveSettingsUseCase(repository),
            refreshSettingsUseCase = RefreshSettingsUseCase(repository),
            updateSettingsUseCase = UpdateSettingsUseCase(repository),
            appConfig = testConfig(),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(SettingsIntent.UpdateDisplayName("Administradora"))
        viewModel.dispatch(SettingsIntent.TogglePreferFakeData(false))
        viewModel.dispatch(SettingsIntent.ToggleVerboseLogs(false))
        viewModel.dispatch(SettingsIntent.Save)
        viewModel.dispatch(SettingsIntent.Save)
        advanceUntilIdle()

        val saved = repository.settings.value
        assertEquals(1, repository.updateCalls)
        assertEquals("Administradora", saved.displayName)
        assertFalse(saved.preferFakeData)
        assertFalse(saved.enableVerboseLogs)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.actionState)
        viewModel.clear()
    }
}

private class FakeSettingsRepository : SettingsRepository {
    val settings = MutableStateFlow(
        AdminSettings(
            displayName = "Admin",
            email = "admin@oposicontrol.local",
            receiveReleaseNotes = true,
            defaultEnvironment = Environment.DEV,
            preferFakeData = true,
            enableVerboseLogs = true,
        ),
    )
    var updateCalls = 0
        private set

    override fun observeSettings(): Flow<AdminSettings> = settings

    override suspend fun refreshSettings(): AppResult<AdminSettings> {
        return AppResult.Success(settings.value)
    }

    override suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings> {
        updateCalls += 1
        this.settings.value = settings
        return AppResult.Success(settings)
    }
}

private fun testConfig(): AppConfig {
    return AppConfig(
        environment = Environment.DEV,
        baseUrl = "https://dev.api.oposicontrol.local",
        supabaseUrl = "",
        supabasePublishableKey = "",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(
            useFakeData = true,
            enableLogs = false,
            enableAnalytics = false,
            enableVerboseNetworkLogs = false,
            fakeLatencyMs = 0L,
            fakeErrorRate = 0.0,
        ),
        appName = "Test",
        appVersion = "1.0.0",
    )
}
