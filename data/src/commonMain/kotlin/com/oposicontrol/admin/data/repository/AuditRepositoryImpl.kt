package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.AuditDataSource
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditPage
import com.oposicontrol.admin.domain.repository.AuditRepository

class AuditRepositoryImpl(
    private val remote: AuditDataSource,
) : AuditRepository {
    override suspend fun fetchMovements(filter: AuditFilter, cursor: String?): AppResult<AuditPage> = remote.fetchMovements(filter, cursor)
    override suspend fun fetchDetail(id: String): AppResult<AuditDetail> = remote.fetchDetail(id)
}
