package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import com.oposicontrol.admin.domain.usecase.auth.LogoutUseCase
import com.oposicontrol.admin.domain.usecase.auth.ObserveSessionUseCase
import com.oposicontrol.admin.domain.usecase.auth.RefreshSessionUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @Test
    fun `submit authenticates when repository returns session`() = runTest {
        val repository = FakeRepository()
        val viewModel = createViewModel(repository)
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(AuthIntent.UpdateEmail("admin@oposicontrol.local"))
        viewModel.dispatch(AuthIntent.UpdatePassword("password-admin"))
        viewModel.dispatch(AuthIntent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthEffect.Authenticated, effect.await())
    }

    @Test
    fun `double submit performs one login`() = runTest {
        val repository = FakeRepository()
        val viewModel = createViewModel(repository)

        viewModel.dispatch(AuthIntent.UpdateEmail("admin@oposicontrol.local"))
        viewModel.dispatch(AuthIntent.UpdatePassword("password-admin"))
        viewModel.dispatch(AuthIntent.Submit)
        viewModel.dispatch(AuthIntent.Submit)
        advanceUntilIdle()

        assertEquals(1, repository.loginCalls)
    }

    @Test
    fun `logout clears privileged session and password`() = runTest {
        val repository = FakeRepository()
        val viewModel = createViewModel(repository)

        viewModel.dispatch(AuthIntent.UpdateEmail("admin@oposicontrol.local"))
        viewModel.dispatch(AuthIntent.UpdatePassword("password-admin"))
        viewModel.dispatch(AuthIntent.Submit)
        advanceUntilIdle()
        viewModel.dispatch(AuthIntent.Logout)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isAuthenticated)
        assertNull(viewModel.state.value.currentSession)
        assertEquals("", viewModel.state.value.password)
        assertEquals(1, repository.logoutCalls)
    }

    @Test
    fun `stored session is refreshed before authentication effect`() = runTest {
        val repository = FakeRepository(initialSession = sessionFixture())
        val viewModel = createViewModel(repository)
        val effect = async { viewModel.effects.first() }

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthEffect.Authenticated, effect.await())
    }

    private fun kotlinx.coroutines.test.TestScope.createViewModel(repository: FakeRepository) = AuthViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        loginUseCase = LoginUseCase(repository),
        logoutUseCase = LogoutUseCase(repository),
        observeSessionUseCase = ObserveSessionUseCase(repository),
        refreshSessionUseCase = RefreshSessionUseCase(repository),
        analyticsTracker = NoOpAnalyticsTracker,
    )

    private class FakeRepository(
        initialSession: Session? = null,
    ) : AuthRepository {
        private val session = MutableStateFlow(initialSession)
        var loginCalls = 0
            private set
        var logoutCalls = 0
            private set

        override fun observeSession(): Flow<Session?> = session

        override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
            loginCalls += 1
            val newSession = sessionFixture()
            session.value = newSession
            return AppResult.Success(newSession)
        }

        override suspend fun refreshSession(): AppResult<Session> = session.value?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Authentication("No hay sesion."))

        override suspend fun logout(): AppResult<Unit> {
            logoutCalls += 1
            session.value = null
            return AppResult.Success(Unit)
        }
    }
}

private fun sessionFixture() = Session(
    userId = "usr-1",
    displayName = "Admin",
    accessToken = "token",
    refreshToken = "refresh",
    expiresAt = Clock.System.now(),
    roleIds = listOf("super-admin"),
)
