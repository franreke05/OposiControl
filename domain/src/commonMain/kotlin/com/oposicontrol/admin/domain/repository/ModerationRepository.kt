package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail

interface ModerationRepository {
    suspend fun listPendingReports(): AppResult<List<ForumReport>>
    /** [includeResolved] = true trae todos los estados; false replica [listPendingReports]. */
    suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>>
    suspend fun getReport(reportId: String): AppResult<ForumReportDetail>
    suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit>
    /** Concede tickets manualmente a un usuario. Devuelve el nuevo saldo. */
    suspend fun grantTickets(userId: String, amount: Int, reason: String): AppResult<Int>
    /** Registra una advertencia de moderación sobre un usuario. */
    suspend fun warnUser(userId: String, message: String): AppResult<Unit>
}
