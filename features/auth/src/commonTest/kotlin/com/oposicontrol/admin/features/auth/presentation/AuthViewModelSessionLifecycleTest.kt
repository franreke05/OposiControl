package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/** MASTER6-AUTH-003 (restore no borra la sesion en fallos transitorios) y MASTER6-AUTH-002 (aviso de logout). */
class AuthViewModelSessionLifecycleTest {
    private val stored = Session(
        userId = "admin-1",
        displayName = "Admin",
        accessToken = "a",
        refreshToken = "r",
        expiresAt = Clock.System.now(),
        roleIds = listOf("admin"),
    )

    /** Imita al repositorio real: un rechazo definitivo del refresh ya limpia la sesion. */
    private inner class Repo(
        private val refreshResult: AppResult<Session>,
        private val logoutResult: AppResult<Unit> = AppResult.Success(Unit),
    ) : AuthRepository {
        val session = MutableStateFlow<Session?>(stored)
        var logoutCalls = 0

        override fun observeSession(): Flow<Session?> = session
        override suspend fun login(credentials: AuthCredentials): AppResult<Session> = error("unused")
        override suspend fun refreshSession(): AppResult<Session> {
            if (refreshResult is AppResult.Failure && refreshResult.error is AppError.Authentication) session.value = null
            return refreshResult
        }
        override suspend fun logout(): AppResult<Unit> {
            logoutCalls++
            session.value = null
            return logoutResult
        }
    }

    private fun TestScope.viewModel(repo: AuthRepository) = AuthViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        loginUseCase = LoginUseCase(repo),
        authRepository = repo,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun restoreKeepsTheSessionWhenTheRefreshFailsTransiently() = runTest {
        for (transient in listOf(
            AppError.Network("Unable to reach the server"),
            AppError.Network("El servidor está tardando demasiado.", 408),
            AppError.Network("Server response failed", 503),
            AppError.Network("Client request failed", 429),
        )) {
            val repo = Repo(AppResult.Failure(transient))
            val vm = viewModel(repo)
            advanceUntilIdle()

            assertEquals(0, repo.logoutCalls, "$transient no debe cerrar sesion")
            assertEquals(stored, repo.session.value)
            assertTrue(vm.state.value.isAuthenticated, "$transient")
            assertFalse(vm.state.value.isRestoring)
        }
    }

    @Test
    fun restoreSignsOutOnADefinitiveRejectionWithoutCallingLogout() = runTest {
        val repo = Repo(AppResult.Failure(AppError.Authentication("Tu sesión ha caducado.")))
        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals(0, repo.logoutCalls, "el refresh token ya fue rechazado: no hay nada que revocar")
        assertFalse(vm.state.value.isAuthenticated)
        assertFalse(vm.state.value.isRestoring)
    }

    @Test
    fun logoutWhoseServerRevocationFailedStillSignsOutButWarns() = runTest {
        val repo = Repo(AppResult.Success(stored), logoutResult = AppResult.Failure(AppError.Network("x")))
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.dispatch(AuthIntent.Logout)
        advanceUntilIdle()

        assertFalse(vm.state.value.isAuthenticated)
        val warning = assertIs<UiAsync.Error>(vm.state.value.submitState)
        assertEquals(LOGOUT_NOT_CONFIRMED_MESSAGE, warning.error.message)
    }
}
