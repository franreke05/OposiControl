package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.AuthApi
import com.oposicontrol.admin.data.remote.dto.RefreshSessionRequestDto
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session

class RealAuthDataSource(
    private val api: AuthApi,
    private val adapter: NetworkResultAdapter,
) : AuthDataSource {
    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        return adapter.safeCall(operation = "auth.login") {
            api.login(credentials.toDto()).toDomain()
        }
    }

    override suspend fun refreshSession(refreshToken: String): AppResult<Session> {
        return adapter.safeCall(operation = "auth.refresh") {
            api.refresh(RefreshSessionRequestDto(refreshToken)).toDomain()
        }
    }

    override suspend fun logout(accessToken: String?): AppResult<Unit> {
        return adapter.safeCall(operation = "auth.logout") {
            api.logout(accessToken)
        }
    }
}
