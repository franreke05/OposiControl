package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow

interface AuditRepository {
    fun observeEvents(): Flow<List<AuditEvent>>
    suspend fun appendEvent(event: AuditEvent): AppResult<Unit>
}
