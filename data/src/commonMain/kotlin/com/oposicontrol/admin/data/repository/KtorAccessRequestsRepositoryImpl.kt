package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Solicitudes de acceso al panel contra el backend Ktor.
 *
 * Lo que decide este fichero y por que:
 *  - [submitRequest] es PUBLICA: no hay sesion todavia, asi que NO lleva `Authorization` (ni el token de sesion ni el
 *    token estatico de desarrollo). Va al plano de auth (`/v1/auth/...`), que es el prefijo publico que el borde enruta.
 *  - La contrasena solo existe en el cuerpo de esa peticion (TLS). No se guarda, no se registra y no viaja en la URL; el
 *    cuerpo de la respuesta de error que se cita en un fallo es el del SERVIDOR, que nunca la repite.
 *  - El rol que concede aprobar NO se envia: lo decide el servidor (siempre `admin`).
 *  - Los cuerpos se construyen a mano como [JsonObject]: son dos o cuatro claves y asi no hay un DTO con contrasena.
 */
class KtorAccessRequestsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : AccessRequestsRepository {

    override suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit> = adapter.safeCall {
        val response = client.post(url(PUBLIC_REQUEST)) {
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf(
                        "nombre" to JsonPrimitive(submission.name),
                        "email" to JsonPrimitive(submission.email),
                        "password" to JsonPrimitive(submission.password),
                        "motivo" to JsonPrimitive(submission.reason),
                    ),
                ).toString(),
            )
            expectSuccess = false
        }
        response.requireSuccess(PUBLIC_REQUEST)
    }

    override suspend fun listRequests(status: AccessRequestStatus?): AppResult<List<AccessRequest>> = adapter.safeCall {
        val response = client.get(url(REVIEW)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            parameter("limit", PAGE_SIZE)
            status?.let { parameter("estado", it.wire) }
            expectSuccess = false
        }
        response.requireSuccess(REVIEW)
        response.body<SolicitudAccesoListDto>().items.mapNotNull { it.toDomain() }
    }

    override suspend fun approve(requestId: String): AppResult<AccessRequest> = adapter.safeCall {
        val endpoint = "$REVIEW/$requestId/aprobar"
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<SolicitudAccesoDto>().toDomain() ?: error("El servidor devolvio una solicitud con un estado desconocido.")
    }

    override suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest> = adapter.safeCall {
        val endpoint = "$REVIEW/$requestId/rechazar"
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            if (cleanReason != null) {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("motivo" to JsonPrimitive(cleanReason))).toString())
            }
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<SolicitudAccesoDto>().toDomain() ?: error("El servidor devolvio una solicitud con un estado desconocido.")
    }

    private fun url(endpoint: String): String = BackendRoutes.url(appConfig.baseUrl, endpoint)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(300)}")
        }
    }

    companion object {
        const val PUBLIC_REQUEST = "/v1/auth/admin-access-requests"
        const val REVIEW = "/api/v1/admin/solicitudes-acceso"

        /** Tope del listado del servidor: una pagina es lo que la pestana ensena de golpe. */
        const val PAGE_SIZE = 200
    }
}

@Serializable
private data class SolicitudAccesoListDto(val items: List<SolicitudAccesoDto> = emptyList())

@Serializable
private data class SolicitudAccesoDto(
    val id: String,
    val usuarioId: String = "",
    val nombre: String = "",
    val email: String = "",
    val motivo: String = "",
    val estado: String = "",
    val creadoEn: String = "",
    val resueltoEn: String? = null,
    val resueltoPor: String? = null,
    val motivoResolucion: String? = null,
)

/** Un estado que este cliente no conoce no se pinta como pendiente (ofreceria aprobar algo que no se entiende): se omite. */
private fun SolicitudAccesoDto.toDomain(): AccessRequest? {
    val status = AccessRequestStatus.fromWire(estado) ?: return null
    return AccessRequest(
        id = id,
        userId = usuarioId,
        name = nombre,
        email = email,
        reason = motivo,
        status = status,
        createdAt = creadoEn,
        resolvedAt = resueltoEn,
        resolvedBy = resueltoPor,
        resolutionReason = motivoResolucion,
    )
}
