package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorConnectionDiagnosticsRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorConnectionDiagnosticsRepositoryImpl.kt"

private fun Env.repo() = KtorConnectionDiagnosticsRepositoryImpl(client, appConfig, session, adapter)

/** Los 7 GET de `runChecks` salen siempre en este orden; cada sitio del fichero es uno de ellos. */
private val CHECK_CHAIN = listOf(
    get("/health"), get("/v1/especialidades"), get("/v1/examenes"),
    get("/api/v1/admin/especialidades"), get("/api/v1/admin/examenes"),
    get("/api/v1/admin/feedback/preguntas"), get("/api/v1/admin/auditoria"),
)

/**
 * Filas del registrador (R5-REC-C2): KtorConnectionDiagnosticsRepositoryImpl (9 sitios). Los 7 de `runChecks` son GET
 * encadenados en UN metodo: una fila por sitio y las siete declaran la cadena completa (como Commerce); los 2 restantes
 * son los POST de reinicio (rutas legacy que siguen en routes.combined).
 */
internal val ROWS_C2_CONNECTION_DIAGNOSTICS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "runChecks(health)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(v1-especialidades)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(v1-examenes)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(admin-especialidades)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(admin-examenes)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(admin-feedback)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "runChecks(admin-auditoria)", CHECK_CHAIN) { repo().runChecks() },
    CallRow(FILE, "requestBackendRestart", post("/admin/backend/restart")) { repo().requestBackendRestart() },
    CallRow(FILE, "requestConnectionRestart", post("/admin/dev/restart-connection")) { repo().requestConnectionRestart() },
)
