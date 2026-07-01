package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow

interface AuditDataSource {
    fun observeEvents(): Flow<List<AuditEvent>>
    suspend fun fetchEvents(): AppResult<List<AuditEvent>>
}
