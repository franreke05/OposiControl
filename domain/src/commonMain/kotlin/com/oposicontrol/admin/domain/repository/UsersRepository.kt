package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import kotlinx.coroutines.flow.Flow

interface UsersRepository {
    fun observeUsers(): Flow<List<User>>
    suspend fun getUser(userId: String): AppResult<User>
    suspend fun upsertUser(user: User): AppResult<User>
    suspend fun deleteUser(userId: String): AppResult<Unit>

    /** Ficha completa: perfil, rendimiento, competitivo, economía y comunidad. */
    suspend fun getUserDetail(userId: String): AppResult<UserDetail>
}
