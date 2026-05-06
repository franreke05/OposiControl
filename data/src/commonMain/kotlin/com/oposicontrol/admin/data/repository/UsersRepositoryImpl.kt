package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.data.remote.source.UsersDataSource
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.AuditRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

class UsersRepositoryImpl(
    private val remote: UsersDataSource,
    private val auditRepository: AuditRepository,
) : UsersRepository {
    override fun observeUsers(): Flow<List<User>> = remote.observeUsers()

    override suspend fun getUser(userId: String) = remote.getUser(userId)

    override suspend fun upsertUser(user: User): AppResult<User> {
        return when (val result = remote.upsertUser(user)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                auditRepository.appendEvent(
                    AuditEvent(
                        id = "audit-user-${result.value.id}",
                        actorName = "admin-console",
                        action = "USER_UPSERT",
                        target = result.value.id,
                        occurredAt = Clock.System.now(),
                        metadata = mapOf("email" to result.value.email),
                    ),
                )
                result
            }
        }
    }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        return when (val result = remote.deleteUser(userId)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                auditRepository.appendEvent(
                    AuditEvent(
                        id = "audit-user-delete-$userId",
                        actorName = "admin-console",
                        action = "USER_DELETE",
                        target = userId,
                        occurredAt = Clock.System.now(),
                        metadata = emptyMap(),
                    ),
                )
                result
            }
        }
    }
}
