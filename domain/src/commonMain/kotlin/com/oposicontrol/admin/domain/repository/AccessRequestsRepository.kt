package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission

/**
 * Solicitudes de acceso al panel.
 *
 * [submitRequest] es publica (sin sesion): el servidor crea la cuenta sin rol y SIN ACTIVAR (no puede iniciar sesion en
 * ningun sitio hasta que se apruebe) y guarda la solicitud, y responde LO MISMO
 * exista ya el correo o no. Las otras tres son del superadministrador: un administrador normal recibe
 * `AppError.Authorization` (403 `SUPERADMIN_REQUIRED`), y con esa respuesta de [listRequests] decide la pantalla si la
 * pestana existe.
 */
interface AccessRequestsRepository {
    suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit>

    /** Pendientes primero. [status] `null` = todas. */
    suspend fun listRequests(status: AccessRequestStatus? = null): AppResult<List<AccessRequest>>

    /** Activa la cuenta, concede el rol `admin` (lo decide el servidor) y marca la solicitud aprobada. Repetirla es idempotente. */
    suspend fun approve(requestId: String): AppResult<AccessRequest>

    /** Marca la solicitud rechazada; la cuenta se queda sin activar y sin rol. [reason] es opcional. */
    suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest>
}
