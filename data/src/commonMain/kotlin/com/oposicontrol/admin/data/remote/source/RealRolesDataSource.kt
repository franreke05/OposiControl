package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RealRolesDataSource(
    private val api: RolesApi,
    private val adapter: NetworkResultAdapter,
) : RolesDataSource {
    override fun observeRoles(): Flow<AppResult<List<Role>>> = flow {
        // Aquí había un `is AppResult.Failure -> emit(emptyList())`: un corte de red llegaba a la pantalla como un
        // catálogo vacío. Se emite el resultado tal cual y quien lo consume decide qué enseñar (mismo arreglo que
        // `RealUsersDataSource.observeUsers`).
        emit(adapter.safeCall { api.getRoles().map { it.toDomain() } })
    }
}
