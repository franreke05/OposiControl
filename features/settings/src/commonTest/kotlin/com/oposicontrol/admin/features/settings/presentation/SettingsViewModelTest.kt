package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsViewModelTest {
    @Test
    fun `profile uses authenticated session and human role`() = runTest {
        val auth = FakeAuthRepository(
            Session(
                userId = "not-rendered",
                displayName = "Arturo G",
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                roleIds = listOf("admin"),
            ),
        )
        val viewModel = newViewModel(testScheduler, auth)

        advanceUntilIdle()

        val profile = assertIs<UiAsync.Success<SettingsProfile>>(viewModel.state.value.profile)
        assertEquals("Arturo G", profile.value.displayName)
        assertEquals("Administrador", profile.value.roleLabel)
    }

    @Test
    fun `logout delegates to real auth repository and exposes success`() = runTest {
        val auth = FakeAuthRepository()
        val viewModel = newViewModel(testScheduler, auth)

        viewModel.dispatch(SettingsIntent.Logout)
        advanceUntilIdle()

        assertEquals(1, auth.logoutCalls)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.logoutState)
    }

    private fun newViewModel(scheduler: TestCoroutineScheduler, auth: AuthRepository) = SettingsViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
        settingsRepository = FakeSettingsRepository(),
        authRepository = auth,
        appConfig = AppConfig(
            environment = Environment.DEV,
            baseUrl = "http://localhost",
            backendToken = "",
            supabaseUrl = "",
            supabaseAnonKey = "",
            supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
            appName = "OposiControl",
            appVersion = "test",
        ),
        analyticsTracker = NoOpAnalyticsTracker,
    )
}

private class FakeSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(
        AdminSettings(
            displayName = "",
            email = "",
            receiveReleaseNotes = false,
            defaultEnvironment = Environment.DEV,
            enableVerboseLogs = false,
        ),
    )

    override fun observeSettings(): Flow<AdminSettings> = settings
    override suspend fun refreshSettings(): AppResult<AdminSettings> = AppResult.Success(settings.value)
    override suspend fun updateSettings(settings: AdminSettings): AppResult<AdminSettings> {
        this.settings.value = settings
        return AppResult.Success(settings)
    }
}

private class FakeAuthRepository(initial: Session? = null) : AuthRepository {
    private val session = MutableStateFlow(initial)
    var logoutCalls: Int = 0

    override fun observeSession(): Flow<Session?> = session
    override suspend fun login(credentials: AuthCredentials): AppResult<Session> = error("not used")
    override suspend fun refreshSession(): AppResult<Session> = error("not used")
    override suspend fun logout(): AppResult<Unit> {
        logoutCalls += 1
        session.value = null
        return AppResult.Success(Unit)
    }
}

