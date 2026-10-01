package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.remote.api.DashboardApi

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/DashboardApi.kt"

/** Filas del registrador (R4-REC-B): DashboardApi. */
internal val ROWS_B_DASHBOARD_API: List<CallRow> = listOf(
    CallRow(FILE, "getMetrics", get("/api/v1/admin/dashboard")) {
        DashboardApi(client, appConfig, session).getMetrics()
    },
)
