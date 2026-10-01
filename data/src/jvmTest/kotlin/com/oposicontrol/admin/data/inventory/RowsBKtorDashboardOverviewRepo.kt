package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorDashboardOverviewRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorDashboardOverviewRepositoryImpl.kt"

/** Filas del registrador (R4-REC-B): KtorDashboardOverviewRepositoryImpl. */
internal val ROWS_B_DASHBOARD_OVERVIEW_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getOverview", get("/api/v1/admin/dashboard/overview")) {
        KtorDashboardOverviewRepositoryImpl(client, appConfig, session, adapter).getOverview(emptySet())
    },
)
