package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.data.remote.source.UsersDataSource
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.flow.Flow

class UsersRepositoryImpl(
    private val remote: UsersDataSource,
) : UsersRepository {
    override fun observeUsers(): Flow<AppResult<List<User>>> = remote.observeUsers()

    override fun observeUsersWithRole(roleCode: String): Flow<AppResult<List<User>>> = remote.observeUsersWithRole(roleCode)

    override suspend fun searchUsers(query: String, limit: Int): AppResult<List<User>> = remote.searchUsers(query, limit)

    override suspend fun getUser(userId: String) = remote.getUser(userId)

    override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = remote.upsertUser(user, previous)

    override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = remote.getUserDetail(userId)
}
