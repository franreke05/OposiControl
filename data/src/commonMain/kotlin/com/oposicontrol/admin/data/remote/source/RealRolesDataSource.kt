package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RealRolesDataSource(
    private val api: RolesApi,
    private val adapter: NetworkResultAdapter,
) : RolesDataSource {
    override fun observeRoles(): Flow<List<Role>> = flow {
        when (val result = adapter.safeCall { api.getRoles().map { it.toDomain() } }) {
            is AppResult.Failure -> emit(emptyList())
            is AppResult.Success -> emit(result.value)
        }
    }

    override suspend fun upsertRole(role: Role): AppResult<Role> {
        return adapter.safeCall {
            api.upsertRole(role.toDto()).toDomain()
        }
    }
}
