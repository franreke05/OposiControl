package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.RolesDataSource
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow

class FakeRolesDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : RolesDataSource {
    override fun observeRoles(): Flow<List<Role>> = store.roles

    override suspend fun upsertRole(role: Role): AppResult<Role> {
        return behavior.execute("roles.upsert") {
            val current = store.roles.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == role.id }
            if (existingIndex >= 0) {
                current[existingIndex] = role
            } else {
                current += role
            }
            store.replaceRoles(current)
            role
        }
    }
}
