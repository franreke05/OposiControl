package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.AuditDataSource
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.repository.AuditRepository
import kotlinx.coroutines.flow.Flow

class AuditRepositoryImpl(
    private val remote: AuditDataSource,
) : AuditRepository {
    override fun observeEvents(): Flow<List<AuditEvent>> = remote.observeEvents()
    override suspend fun fetchEvents(): AppResult<List<AuditEvent>> = remote.fetchEvents()
}
