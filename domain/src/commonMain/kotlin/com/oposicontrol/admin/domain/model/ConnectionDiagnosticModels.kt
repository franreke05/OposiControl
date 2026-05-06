package com.oposicontrol.admin.domain.model

data class ConnectionDiagnosticReport(
    val checks: List<ConnectionDiagnosticCheck>,
)

data class ConnectionDiagnosticCheck(
    val name: String,
    val ok: Boolean,
    val detail: String,
)
