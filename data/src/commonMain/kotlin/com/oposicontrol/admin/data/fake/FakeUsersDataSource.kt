package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.UsersDataSource
import com.oposicontrol.admin.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

class FakeUsersDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : UsersDataSource {
    override fun observeUsers(): Flow<List<User>> = store.users

    override suspend fun getUser(userId: String): AppResult<User> {
        val user = store.users.value.firstOrNull { it.id == userId }
            ?: return AppResult.Failure(AppError.NotFound("Usuario $userId no encontrado."))
        return AppResult.Success(user)
    }

    override suspend fun upsertUser(user: User): AppResult<User> {
        return behavior.execute("users.upsert") {
            val normalized = if (user.id.isBlank()) {
                user.copy(
                    id = "usr-${(store.users.value.size + 1).toString().padStart(3, '0')}",
                    createdAt = Instant.parse("2026-04-14T09:00:00Z"),
                )
            } else {
                user
            }
            val current = store.users.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == normalized.id }
            if (existingIndex >= 0) {
                current[existingIndex] = normalized
            } else {
                current += normalized
            }
            store.replaceUsers(current)
            normalized
        }
    }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        return behavior.execute("users.delete") {
            store.replaceUsers(store.users.value.filterNot { it.id == userId })
            Unit
        }
    }
}
