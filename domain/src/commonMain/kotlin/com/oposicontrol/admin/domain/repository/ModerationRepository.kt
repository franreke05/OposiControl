package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReport

interface ModerationRepository {
    suspend fun listPendingReports(): AppResult<List<ForumReport>>
    suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit>
    /** Concede tickets manualmente a un usuario. Devuelve el nuevo saldo. */
    suspend fun grantTickets(userId: String, amount: Int, reason: String): AppResult<Int>
}
