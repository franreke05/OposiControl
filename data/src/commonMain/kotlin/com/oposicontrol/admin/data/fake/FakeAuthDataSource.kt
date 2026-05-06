package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import kotlinx.datetime.Instant

class FakeAuthDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : AuthDataSource {
    private val fakeExpiry: Instant = Instant.parse("2026-04-14T12:00:00Z")

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        val user = store.users.value.firstOrNull { it.email == credentials.email }
            ?: return AppResult.Failure(AppError.Authentication("Credenciales invalidas en modo fake."))

        if (credentials.password != "admin123") {
            return AppResult.Failure(AppError.Authentication("Usa la contrasena fake admin123."))
        }

        return behavior.execute("auth.login") {
            Session(
                userId = user.id,
                displayName = user.fullName,
                accessToken = "fake-access-${user.id}",
                refreshToken = "fake-refresh-${user.id}",
                expiresAt = fakeExpiry,
                roleIds = user.roleIds,
            ).also { store.session.value = it }
        }
    }

    override suspend fun refreshSession(refreshToken: String): AppResult<Session> {
        val currentSession = store.session.value
            ?: return AppResult.Failure(AppError.Authentication("No hay sesion fake activa."))

        return behavior.execute("auth.refresh") {
            currentSession.copy(
                accessToken = "fake-access-refreshed-${currentSession.userId}",
                refreshToken = refreshToken,
                expiresAt = fakeExpiry,
            ).also { store.session.value = it }
        }
    }

    override suspend fun logout(accessToken: String?): AppResult<Unit> {
        return behavior.execute("auth.logout") {
            store.session.value = null
            Unit
        }
    }
}
