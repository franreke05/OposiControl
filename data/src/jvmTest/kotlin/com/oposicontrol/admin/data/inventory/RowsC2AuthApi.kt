package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.remote.api.AuthApi
import com.oposicontrol.admin.data.remote.dto.LoginRequestDto
import com.oposicontrol.admin.data.remote.dto.RefreshSessionRequestDto

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/AuthApi.kt"

/** POST directo a GoTrue (Supabase Auth): clase de host SUPABASE, identidad no negocio (AUTH_V1 en direct-supabase-allowlist.tsv). */
private fun supabasePost(spec: String) = call("POST", spec, HostClass.SUPABASE)

/**
 * Filas del registrador (R5-REC-C2): AuthApi, las 3 UNICAS llamadas directas a Supabase del cliente. No estan en
 * `routes.combined.txt` (no son rutas del backend): `rowViolations` las valida contra [SUPABASE_IDENTITY_ALLOWED]
 * (`POST /auth/v1/token` para password y refresh_token, `POST /auth/v1/logout`); la consulta se compara por NOMBRES (`grant_type`, `scope`).
 */
internal val ROWS_C2_AUTH_API: List<CallRow> = listOf(
    CallRow(FILE, "login", supabasePost("/auth/v1/token?grant_type")) {
        AuthApi(client, appConfig).login(LoginRequestDto(email = "e", password = "p"))
    },
    CallRow(FILE, "refresh", supabasePost("/auth/v1/token?grant_type")) {
        AuthApi(client, appConfig).refresh(RefreshSessionRequestDto(refreshToken = "r"))
    },
    CallRow(FILE, "logout", supabasePost("/auth/v1/logout?scope")) {
        AuthApi(client, appConfig).logout("t")
    },
)
