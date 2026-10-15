package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorModerationRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorModerationRepositoryImpl.kt"
private const val R = "/api/v1/admin/reportes"

private fun Env.repo() = KtorModerationRepositoryImpl(client, appConfig, session, adapter)

/**
 * Filas del registrador (R5-REC-C2): KtorModerationRepositoryImpl (6 sitios). `listPendingReports` delega en `listReports`
 * (mismo sitio): la fila de `listReports` ejecuta los dos puntos de entrada, declara las dos llamadas y los nombra en `covers`.
 */
internal val ROWS_C2_MODERATION_REPO: List<CallRow> = listOf(
    CallRow(FILE, "listReports(pending,todos)", listOf(get(R), get("$R?estado")), covers = setOf("listReports", "listPendingReports")) {
        val r = repo()
        r.listPendingReports(); r.listReports(includeResolved = true)
    },
    // Pagina keyset de Informes (D5-25): con `estado=todos`, `limit` y el `cursor` de la pagina anterior.
    CallRow(FILE, "listReportsPage", get("$R?estado&limit&cursor")) { repo().listReportsPage(includeResolved = true, cursor = "c1") },
    CallRow(FILE, "getReport", get("$R/{}")) { repo().getReport("ID0") },
    CallRow(FILE, "resolveReport", post("$R/{}/resolver")) { repo().resolveReport("ID0", "descartar", null, 0) },
    CallRow(FILE, "grantTickets", post("/api/v1/admin/usuarios/{}/tickets")) { repo().grantTickets("ID0", 1, "m", "k") },
    CallRow(FILE, "warnUser", post("/api/v1/admin/moderacion/actions")) { repo().warnUser("ID0", "m") },
    CallRow(FILE, "setAccountBlocked", post("/api/v1/admin/usuarios/{}/estado-cuenta")) { repo().setAccountBlocked("ID0", true, "m") },
)
