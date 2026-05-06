package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow

interface RolesRepository {
    fun observeRoles(): Flow<List<Role>>
    suspend fun upsertRole(role: Role): AppResult<Role>
}
