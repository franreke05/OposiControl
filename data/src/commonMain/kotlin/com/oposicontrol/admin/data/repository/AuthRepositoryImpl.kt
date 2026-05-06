package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.result.onSuccess
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow

class AuthRepositoryImpl(
    private val remote: AuthDataSource,
    private val local: SessionLocalDataSource,
) : AuthRepository {
    override fun observeSession(): Flow<Session?> = local.observeSession()

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        return remote.login(credentials).onSuccess(local::save)
    }

    override suspend fun refreshSession(): AppResult<Session> {
        val currentSession = local.currentSession()
            ?: return AppResult.Failure(AppError.Authentication("No hay sesion activa para refrescar."))
        return remote.refreshSession(currentSession.refreshToken).onSuccess(local::save)
    }

    override suspend fun logout(): AppResult<Unit> {
        val result = remote.logout(local.currentSession()?.accessToken)
        local.clear()
        return result
    }
}
