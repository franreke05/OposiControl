package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeSession(): Flow<Session?>
    suspend fun login(credentials: AuthCredentials): AppResult<Session>
    suspend fun refreshSession(): AppResult<Session>
    suspend fun logout(): AppResult<Unit>
}
