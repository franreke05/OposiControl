package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.data.remote.source.RolesDataSource
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.repository.RolesRepository
import kotlinx.coroutines.flow.Flow

class RolesRepositoryImpl(
    private val remote: RolesDataSource,
) : RolesRepository {
    override fun observeRoles(): Flow<List<Role>> = remote.observeRoles()

    override suspend fun upsertRole(role: Role): AppResult<Role> = remote.upsertRole(role)
}
