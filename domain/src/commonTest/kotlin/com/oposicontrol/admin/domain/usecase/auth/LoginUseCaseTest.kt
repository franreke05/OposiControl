package com.oposicontrol.admin.domain.usecase.auth

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertIs

class LoginUseCaseTest {
    @Test
    fun `returns validation error when password is too short`() = kotlinx.coroutines.test.runTest {
        val useCase = LoginUseCase(FakeAuthRepository())

        val result = useCase(AuthCredentials(email = "admin@oposi.dev", password = "123"))

        assertIs<AppResult.Failure>(result)
    }

    private class FakeAuthRepository : AuthRepository {
        override fun observeSession(): Flow<Session?> = flowOf(null)

        override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
            return AppResult.Success(
                Session(
                    userId = "admin-1",
                    displayName = "Admin",
                    accessToken = "token",
                    refreshToken = "refresh",
                    expiresAt = Clock.System.now(),
                    roleIds = listOf("super-admin"),
                ),
            )
        }

        override suspend fun refreshSession(): AppResult<Session> = error("Not used")
        override suspend fun logout(): AppResult<Unit> = AppResult.Success(Unit)
    }
}
