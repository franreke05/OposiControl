package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.UserDetailApi
import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RealUsersDataSource(
    private val api: UsersApi,
    private val adapter: NetworkResultAdapter,
    private val detailApi: UserDetailApi,
) : UsersDataSource {
    override fun observeUsers(): Flow<AppResult<List<User>>> = flow {
        // Aquí había un `is AppResult.Failure -> emit(emptyList())`.
        //
        // Convertía cualquier fallo —servidor caído, sesión caducada, sin red— en
        // un censo vacío, y la pantalla lo pintaba como «Todavía no hay ningún
        // usuario registrado» con un botón para crear el primero. Un administrador
        // podía concluir que la base de datos se había vaciado.
        emit(adapter.safeCall { api.getUsers().map { it.toDomain() } })
    }

    override suspend fun getUser(userId: String): AppResult<User> {
        return adapter.safeCall {
            api.getUser(userId).toDomain()
        }
    }

    override suspend fun upsertUser(user: User): AppResult<User> {
        return adapter.safeCall {
            api.upsertUser(user.toDto()).toDomain()
        }
    }

    override suspend fun getUserDetail(userId: String): AppResult<UserDetail> =
        adapter.safeCall { detailApi.getUserDetail(userId) }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        return adapter.safeCall {
            api.deleteUser(userId)
        }
    }
}
