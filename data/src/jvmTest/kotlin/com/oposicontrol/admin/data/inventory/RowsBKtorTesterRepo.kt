package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorTesterRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorTesterRepositoryImpl.kt"

/** Filas del registrador (R4-REC-B): KtorTesterRepositoryImpl. */
internal val ROWS_B_TESTER_REPO: List<CallRow> = listOf(
    CallRow(FILE, "listReports", get("/api/v1/admin/feedback/preguntas")) {
        KtorTesterRepositoryImpl(client, appConfig, session, adapter).listReports(status = null, cursor = null, type = null, pageSize = 25)
    },
    CallRow(FILE, "getReport", get("/api/v1/admin/feedback/preguntas/{}")) {
        KtorTesterRepositoryImpl(client, appConfig, session, adapter).getReport("ID0")
    },
    CallRow(FILE, "resolveReport", post("/api/v1/admin/feedback/preguntas/{}/resolver")) {
        KtorTesterRepositoryImpl(client, appConfig, session, adapter).resolveReport("ID0", "resuelto", null)
    },
)
