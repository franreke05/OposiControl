package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.data.remote.source.RolesDataSource
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.repository.AuditRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

class RolesRepositoryImpl(
    private val remote: RolesDataSource,
    private val auditRepository: AuditRepository,
) : RolesRepository {
    override fun observeRoles(): Flow<List<Role>> = remote.observeRoles()

    override suspend fun upsertRole(role: Role): AppResult<Role> {
        return when (val result = remote.upsertRole(role)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                auditRepository.appendEvent(
                    AuditEvent(
                        id = "audit-role-${result.value.id}",
                        actorName = "admin-console",
                        action = "ROLE_UPSERT",
                        target = result.value.id,
                        occurredAt = Clock.System.now(),
                        metadata = mapOf("name" to result.value.name),
                    ),
                )
                result
            }
        }
    }
}
