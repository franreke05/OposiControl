package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestCodeOutcome
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Solicitudes de acceso al panel contra el backend Ktor.
 *
 * Lo que decide este fichero y por que:
 *  - [submitRequest], [verifyEmail] y [resendCode] son PUBLICAS: no hay sesion todavia, asi que NO llevan `Authorization`
 *    (ni el token de sesion ni el token estatico de desarrollo). Van al plano de auth (`/v1/auth/...`), que es el prefijo
 *    publico que el borde enruta. El codigo de verificacion solo viaja en el cuerpo, nunca en la URL ni en el log.
 *  - La contrasena (la que se elige al VERIFICAR el codigo; pedir el acceso ya no la lleva) solo existe en el cuerpo de esa
 *    peticion (TLS). No se guarda, no se registra y no viaja en la URL; el
 *    cuerpo de la respuesta de error que se cita en un fallo es el del SERVIDOR, que nunca la repite.
 *  - El rol que concede aprobar NO se envia: lo decide el servidor (siempre `admin`).
 *  - Los cuerpos se construyen a mano como [JsonObject]: son de una a tres claves y asi no hay un DTO con contrasena.
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
                        "motivo" to JsonPrimitive(submission.reason),
                    ),
                ).toString(),
            )
            expectSuccess = false
        }
        response.requireSuccess(PUBLIC_REQUEST)
    }

    override suspend fun verifyEmail(email: String, code: String, password: String): AppResult<AccessRequestCodeOutcome> = adapter.safeCall {
        val response = client.post(url(PUBLIC_VERIFY)) {
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf("email" to JsonPrimitive(email), "code" to JsonPrimitive(code), "password" to JsonPrimitive(password)),
                ).toString(),
            )
            expectSuccess = false
        }
        // 400 sobre `password`: la contrasena elegida no cumple la regla. Todo otro 400 que no sea sobre el correo es el
        // mismo «codigo no valido» (el servidor no distingue por que).
        response.codeOutcome(PUBLIC_VERIFY, otherBadRequest = AccessRequestCodeOutcome.InvalidCode)
    }

    override suspend fun resendCode(email: String): AppResult<AccessRequestCodeOutcome> = adapter.safeCall {
        val response = client.post(url(PUBLIC_RESEND)) {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("email" to JsonPrimitive(email))).toString())
            expectSuccess = false
        }
        response.codeOutcome(PUBLIC_RESEND, otherBadRequest = null)
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

    /**
     * Lee la respuesta de verificar / reenviar. 429 conserva `Retry-After` (la pantalla dice cuanto esperar); 400 sobre
     * `identifier` es correo mal formado; el resto de 400 es [otherBadRequest] o, si es `null`, el mapeo de siempre.
     * 503 y cualquier otro fallo siguen el mapeo de siempre ([requireSuccess] + `NetworkResultAdapter`).
     */
    private suspend fun HttpResponse.codeOutcome(endpoint: String, otherBadRequest: AccessRequestCodeOutcome?): AccessRequestCodeOutcome {
        if (status.isSuccess()) return AccessRequestCodeOutcome.Accepted
        if (status == HttpStatusCode.TooManyRequests) {
            return AccessRequestCodeOutcome.RateLimited(headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
        }
        if (status == HttpStatusCode.BadRequest) {
            val body = bodyAsText()
            val fields = validationIssueFields(body)
            if (IDENTIFIER_FIELD in fields) return AccessRequestCodeOutcome.InvalidEmail
            if (endpoint == PUBLIC_VERIFY && PASSWORD_FIELD in fields) return AccessRequestCodeOutcome.InvalidPassword
            if (otherBadRequest != null) return otherBadRequest
            error("backend ${status.value} $endpoint: ${body.take(300)}")
        }
        requireSuccess(endpoint)
        error("backend ${status.value} $endpoint")
    }

    /** Campos de `details.issues[].field` del sobre de error del plano de auth. Un cuerpo ilegible no tiene campos. */
    private fun validationIssueFields(body: String): Set<String> = runCatching {
        // Indexado con corchetes a proposito: el inventario de llamadas HTTP cuenta como egreso cualquier get con receptor.
        val details = Json.parseToJsonElement(body).jsonObject["details"] as? JsonObject
        val issues = details?.let { it["issues"] }?.jsonArray.orEmpty()
        issues.mapNotNull { it.jsonObject["field"]?.jsonPrimitive?.contentOrNull }.toSet()
    }.getOrNull().orEmpty()

    companion object {
        const val PUBLIC_REQUEST = "/v1/auth/admin-access-requests"
        const val PUBLIC_VERIFY = "/v1/auth/admin-access-requests/verify"
        const val PUBLIC_RESEND = "/v1/auth/admin-access-requests/resend-code"
        private const val IDENTIFIER_FIELD = "identifier"
        private const val PASSWORD_FIELD = "password"
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
    // Si faltaran (servidor antiguo), lo seguro es «sin verificar»: la pantalla no ofrece aprobar.
    val correoVerificado: Boolean = false,
    val caducada: Boolean = false,
    val correoVerificadoEn: String? = null,
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
        emailVerified = correoVerificado,
        expired = caducada,
        emailVerifiedAt = correoVerificadoEn,
    )
}
