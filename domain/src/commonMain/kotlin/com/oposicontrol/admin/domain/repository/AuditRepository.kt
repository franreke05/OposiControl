package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditPage

interface AuditRepository {
    /** Una página de movimientos con [filter] aplicado en el servidor. [cursor] `null` = la primera. */
    suspend fun fetchMovements(filter: AuditFilter, cursor: String? = null): AppResult<AuditPage>

    /** El antes y el después de UN movimiento. Se pide aparte: la lista no los descarga. */
    suspend fun fetchDetail(id: String): AppResult<AuditDetail>
}
