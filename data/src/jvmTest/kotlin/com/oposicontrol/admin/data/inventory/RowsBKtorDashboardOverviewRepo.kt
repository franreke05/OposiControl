package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorDashboardOverviewRepositoryImpl
import com.oposicontrol.admin.domain.model.DashboardBlock

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorDashboardOverviewRepositoryImpl.kt"
private const val O = "/api/v1/admin/dashboard/overview"

/**
 * Filas del registrador (R4-REC-B): KtorDashboardOverviewRepositoryImpl. El unico sitio esta en el ayudante privado `get`
 * (`site`); la fila ejecuta `getOverview` sin secciones (carga inicial, sin consulta) y con una (`?sections`).
 */
internal val ROWS_B_DASHBOARD_OVERVIEW_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getOverview", listOf(get(O), get("$O?sections")), site = "get") {
        val r = KtorDashboardOverviewRepositoryImpl(client, appConfig, session, adapter)
        r.getOverview(emptySet())
        r.getOverview(setOf(DashboardBlock.SYSTEM))
    },
)
