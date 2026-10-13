package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow

interface RolesDataSource {
    fun observeRoles(): Flow<AppResult<List<Role>>>
}
