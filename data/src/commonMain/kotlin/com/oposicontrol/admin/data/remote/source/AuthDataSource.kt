package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session

interface AuthDataSource {
    suspend fun login(credentials: AuthCredentials): AppResult<Session>
    suspend fun refreshSession(refreshToken: String): AppResult<Session>
    suspend fun logout(accessToken: String?): AppResult<Unit>
}
