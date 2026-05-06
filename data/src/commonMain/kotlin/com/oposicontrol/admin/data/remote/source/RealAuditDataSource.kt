package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RealAuditDataSource(
    private val api: AuditApi,
    private val adapter: NetworkResultAdapter,
) : AuditDataSource {
    override fun observeEvents(): Flow<List<AuditEvent>> = flow {
        when (val result = adapter.safeCall { api.getEvents().map { it.toDomain() } }) {
            is AppResult.Failure -> emit(emptyList())
            is AppResult.Success -> emit(result.value)
        }
    }

    override suspend fun appendEvent(event: AuditEvent): AppResult<Unit> {
        return adapter.safeCall {
            api.appendEvent(event.toDto())
        }
    }
}
