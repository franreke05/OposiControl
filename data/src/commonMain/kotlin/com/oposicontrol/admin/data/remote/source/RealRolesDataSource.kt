package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealRolesDataSource(
    private val api: RolesApi,
    private val adapter: NetworkResultAdapter,
) : RolesDataSource {
    private val roles = MutableStateFlow<List<Role>>(emptyList())
    private val initialLoadMutex = Mutex()
    private var initialLoadCompleted = false

    override fun observeRoles(): Flow<List<Role>> = roles.onStart {
        initialLoadMutex.withLock {
            if (initialLoadCompleted) return@withLock
            when (val result = adapter.safeCall { api.getRoles().map { it.toDomain() } }) {
                is AppResult.Failure -> Unit
                is AppResult.Success -> {
                    roles.value = result.value
                    initialLoadCompleted = true
                }
            }
        }
    }

    override suspend fun upsertRole(role: Role): AppResult<Role> {
        val result = adapter.safeCall {
            api.upsertRole(role.toDto()).toDomain()
        }
        if (result is AppResult.Success) {
            roles.update { current ->
                current
                    .filterNot { it.id == result.value.id }
                    .plus(result.value)
            }
        }
        return result
    }
}
