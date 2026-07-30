package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealUsersDataSource(
    private val api: UsersApi,
    private val adapter: NetworkResultAdapter,
) : UsersDataSource {
    private val users = MutableStateFlow<List<User>>(emptyList())
    private val initialLoadMutex = Mutex()
    private var initialLoadCompleted = false

    override fun observeUsers(): Flow<List<User>> = users.onStart {
        initialLoadMutex.withLock {
            if (initialLoadCompleted) return@withLock
            when (val result = adapter.safeCall { api.getUsers().map { it.toDomain() } }) {
                is AppResult.Failure -> Unit
                is AppResult.Success -> {
                    users.value = result.value
                    initialLoadCompleted = true
                }
            }
        }
    }

    override suspend fun getUser(userId: String): AppResult<User> {
        return adapter.safeCall {
            api.getUser(userId).toDomain()
        }
    }

    override suspend fun upsertUser(user: User): AppResult<User> {
        val result = adapter.safeCall {
            api.upsertUser(user.toDto()).toDomain()
        }
        if (result is AppResult.Success) {
            users.update { current ->
                current
                    .filterNot { it.id == result.value.id }
                    .plus(result.value)
            }
        }
        return result
    }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        val result = adapter.safeCall {
            api.deleteUser(userId)
        }
        if (result is AppResult.Success) {
            users.update { current -> current.filterNot { it.id == userId } }
        }
        return result
    }
}
