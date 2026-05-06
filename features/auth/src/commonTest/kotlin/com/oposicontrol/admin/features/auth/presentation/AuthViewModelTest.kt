package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import com.oposicontrol.admin.domain.usecase.auth.LogoutUseCase
import com.oposicontrol.admin.domain.usecase.auth.ObserveSessionUseCase
import com.oposicontrol.admin.domain.usecase.auth.RefreshSessionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertTrue

class AuthViewModelTest {
    @Test
    fun `submit authenticates when repository returns session`() = runTest {
        val repository = FakeRepository()
        val viewModel = AuthViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            loginUseCase = LoginUseCase(repository),
            logoutUseCase = LogoutUseCase(repository),
            observeSessionUseCase = ObserveSessionUseCase(repository),
            refreshSessionUseCase = RefreshSessionUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )

        viewModel.dispatch(AuthIntent.UpdateEmail("admin@oposicontrol.local"))
        viewModel.dispatch(AuthIntent.UpdatePassword("password-admin"))
        viewModel.dispatch(AuthIntent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
    }

    private class FakeRepository : AuthRepository {
        private val session = MutableStateFlow<Session?>(null)

        override fun observeSession(): Flow<Session?> = session

        override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
            val newSession = Session(
                userId = "usr-1",
                displayName = "Admin",
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = Clock.System.now(),
                roleIds = listOf("super-admin"),
            )
            session.value = newSession
            return AppResult.Success(newSession)
        }

        override suspend fun refreshSession(): AppResult<Session> = AppResult.Success(session.value ?: error("unused"))
        override suspend fun logout(): AppResult<Unit> = AppResult.Success(Unit)
    }
}
