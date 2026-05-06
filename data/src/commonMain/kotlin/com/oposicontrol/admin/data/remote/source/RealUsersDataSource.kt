package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RealUsersDataSource(
    private val api: UsersApi,
    private val adapter: NetworkResultAdapter,
) : UsersDataSource {
    override fun observeUsers(): Flow<List<User>> = flow {
        when (val result = adapter.safeCall { api.getUsers().map { it.toDomain() } }) {
            is AppResult.Failure -> emit(emptyList())
            is AppResult.Success -> emit(result.value)
        }
    }

    override suspend fun getUser(userId: String): AppResult<User> {
        return adapter.safeCall {
            api.getUser(userId).toDomain()
        }
    }

    override suspend fun upsertUser(user: User): AppResult<User> {
        return adapter.safeCall {
            api.upsertUser(user.toDto()).toDomain()
        }
    }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        return adapter.safeCall {
            api.deleteUser(userId)
        }
    }
}
