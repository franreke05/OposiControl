package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.remote.api.UserDetailApi

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/UserDetailApi.kt"

/** Filas del registrador (R4-REC-B): UserDetailApi. */
internal val ROWS_B_USER_DETAIL_API: List<CallRow> = listOf(
    CallRow(FILE, "getUserDetail", get("/api/v1/admin/usuarios/{}/detalle")) {
        UserDetailApi(client, appConfig, session).getUserDetail("ID0")
    },
)
