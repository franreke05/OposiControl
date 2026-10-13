package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditPage

class RealAuditDataSource(
    private val api: AuditApi,
    private val adapter: NetworkResultAdapter,
) : AuditDataSource {
    override suspend fun fetchMovements(filter: AuditFilter, cursor: String?): AppResult<AuditPage> =
        adapter.safeCall { api.getMovements(filter, cursor).toDomain() }

    override suspend fun fetchDetail(id: String): AppResult<AuditDetail> =
        adapter.safeCall { api.getMovementDetail(id).toDomain() }
}
