package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticReport

interface ConnectionDiagnosticsRepository {
    suspend fun runChecks(): AppResult<ConnectionDiagnosticReport>
}
