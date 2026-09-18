package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import kotlinx.coroutines.flow.Flow

interface UsersDataSource {
    /** Ver `UsersRepository.observeUsers`: el fallo viaja, no se convierte en lista vacía. */
    fun observeUsers(): Flow<AppResult<List<User>>>
    suspend fun getUser(userId: String): AppResult<User>
    suspend fun upsertUser(user: User): AppResult<User>
    suspend fun deleteUser(userId: String): AppResult<Unit>
    suspend fun getUserDetail(userId: String): AppResult<UserDetail>
}
