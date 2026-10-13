package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorAccessRequestsRepositoryImpl
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAccessRequestsRepositoryImpl.kt"
private const val REVIEW = "/api/v1/admin/solicitudes-acceso"

private fun Env.repo() = KtorAccessRequestsRepositoryImpl(client, appConfig, session, adapter)

/** Filas del registrador: KtorAccessRequestsRepositoryImpl, solicitudes de acceso al panel (4 sitios). */
internal val ROWS_D_ACCESS_REQUESTS_REPO: List<CallRow> = listOf(
    // Publica (plano de auth): la unica llamada del cliente a `/v1/auth/...` del backend propio.
    CallRow(FILE, "submitRequest", post("/v1/auth/admin-access-requests")) {
        repo().submitRequest(AccessRequestSubmission(name = "n", email = "e@example.com", password = "p", reason = "r"))
    },
    // Con filtro: la fila tiene que grabar cada `parameter("x"` que la funcion puede enviar.
    CallRow(FILE, "listRequests", get("$REVIEW?estado&limit")) { repo().listRequests(AccessRequestStatus.PENDING) },
    CallRow(FILE, "approve", post("$REVIEW/{}/aprobar")) { repo().approve("ID0") },
    CallRow(FILE, "reject", post("$REVIEW/{}/rechazar")) { repo().reject("ID0", "motivo") },
)
