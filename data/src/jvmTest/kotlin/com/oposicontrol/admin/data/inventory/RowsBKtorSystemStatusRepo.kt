package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorSystemStatusRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorSystemStatusRepositoryImpl.kt"

/** Filas del registrador (R4-REC-B): KtorSystemStatusRepositoryImpl. */
internal val ROWS_B_SYSTEM_STATUS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "loadStatus", get("/api/v1/admin/estado-sistema")) {
        KtorSystemStatusRepositoryImpl(client, appConfig, session, adapter).loadStatus()
    },
)
