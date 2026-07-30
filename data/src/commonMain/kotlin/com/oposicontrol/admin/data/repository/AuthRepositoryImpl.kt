package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.api.AdminApiDevelopment
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.result.onSuccess
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepositoryImpl(
    private val remote: AuthDataSource,
    private val local: SessionLocalDataSource,
) : AuthRepository {
    private val sessionMutex = Mutex()

    override fun observeSession(): Flow<Session?> = local.observeSession()

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        return sessionMutex.withLock {
            remote.login(credentials).onSuccess(local::save)
        }
    }

    override suspend fun refreshSession(): AppResult<Session> {
        val requestedSession = local.currentSession()
            ?: return AppResult.Failure(AppError.Authentication("No hay sesion activa para refrescar."))
        if (requestedSession.accessToken == AdminApiDevelopment.OPEN_ACCESS_TOKEN) {
            return AppResult.Success(requestedSession)
        }
        return sessionMutex.withLock {
            val latestSession = local.currentSession()
                ?: return@withLock AppResult.Failure(
                    AppError.Authentication("No hay sesion activa para refrescar."),
                )
            if (latestSession.refreshToken != requestedSession.refreshToken) {
                return@withLock AppResult.Success(latestSession)
            }
            remote.refreshSession(latestSession.refreshToken)
                .onSuccess(local::save)
                .also { result ->
                    if (result is AppResult.Failure && result.error is AppError.Authentication) {
                        local.clear()
                    }
                }
        }
    }

    override suspend fun logout(): AppResult<Unit> {
        return sessionMutex.withLock {
            if (local.currentSession()?.accessToken == AdminApiDevelopment.OPEN_ACCESS_TOKEN) {
                local.clear()
                return@withLock AppResult.Success(Unit)
            }
            val result = remote.logout(local.currentSession()?.accessToken)
            local.clear()
            result
        }
    }
}
