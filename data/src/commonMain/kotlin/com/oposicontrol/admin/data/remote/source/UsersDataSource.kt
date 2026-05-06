package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UsersDataSource {
    fun observeUsers(): Flow<List<User>>
    suspend fun getUser(userId: String): AppResult<User>
    suspend fun upsertUser(user: User): AppResult<User>
    suspend fun deleteUser(userId: String): AppResult<Unit>
}
