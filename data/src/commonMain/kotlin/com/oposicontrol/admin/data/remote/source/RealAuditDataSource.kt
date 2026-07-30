package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.mapper.toDto
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealAuditDataSource(
    private val api: AuditApi,
    private val adapter: NetworkResultAdapter,
) : AuditDataSource {
    private val events = MutableStateFlow<List<AuditEvent>>(emptyList())
    private val refreshMutex = Mutex()

    override fun observeEvents(): Flow<List<AuditEvent>> = events.onStart {
        refreshMutex.withLock {
            when (val result = adapter.safeCall { api.getEvents().map { it.toDomain() } }) {
                is AppResult.Failure -> Unit
                is AppResult.Success -> events.value = result.value
            }
        }
    }

    override suspend fun appendEvent(event: AuditEvent): AppResult<Unit> {
        val result = adapter.safeCall {
            api.appendEvent(event.toDto())
        }
        if (result is AppResult.Success) {
            events.update { current ->
                listOf(event) + current.filterNot { it.id == event.id }
            }
        }
        return result
    }
}
