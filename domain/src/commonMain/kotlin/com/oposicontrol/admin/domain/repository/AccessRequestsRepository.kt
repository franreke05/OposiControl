package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestCodeOutcome
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission

/**
 * Solicitudes de acceso al panel.
 *
 * [submitRequest], [verifyEmail] y [resendCode] son publicas (sin sesion): el servidor crea la cuenta sin rol y SIN
 * ACTIVAR, guarda la solicitud y manda un codigo de 6 cifras al correo, y responde LO MISMO exista ya el correo o no.
 * Las otras tres son del superadministrador: un administrador normal recibe `AppError.Authorization` (403
 * `SUPERADMIN_REQUIRED`), y con esa respuesta de [listRequests] decide la pantalla si la pestana existe.
 */
interface AccessRequestsRepository {
    suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit>

    /**
     * Comprueba el codigo del correo y fija la contrasena con la que la persona entrara al panel. [email] ya canonico,
     * [code] de 6 cifras y [password] con la regla de contrasena nueva ([com.oposicontrol.admin.domain.model.AccessRequestRules]).
     */
    suspend fun verifyEmail(email: String, code: String, password: String): AppResult<AccessRequestCodeOutcome>

    /** Pide otro codigo. La respuesta es la misma haya o no solicitud pendiente para ese correo. */
    suspend fun resendCode(email: String): AppResult<AccessRequestCodeOutcome>

    /** Pendientes primero. [status] `null` = todas. */
    suspend fun listRequests(status: AccessRequestStatus? = null): AppResult<List<AccessRequest>>

    /**
     * Activa la cuenta, concede el rol `admin` (lo decide el servidor) y marca la solicitud aprobada. Repetirla es
     * idempotente. Una solicitud sin verificar, caducada o ya rechazada es un 409 con el mensaje del servidor.
     */
    suspend fun approve(requestId: String): AppResult<AccessRequest>

    /** Marca la solicitud rechazada; la cuenta se queda sin activar y sin rol. [reason] es opcional. */
    suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest>
}
