package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditPage

interface AuditDataSource {
    suspend fun fetchMovements(filter: AuditFilter, cursor: String?): AppResult<AuditPage>
    suspend fun fetchDetail(id: String): AppResult<AuditDetail>
}
