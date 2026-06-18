package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.AuditEventDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

class AuditApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getEvents(): List<AuditEventDto> {
        return client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_AUDITORIA)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body<AdminAuditoriasResponse>().data.map { it.toAuditEventDto() }
    }

    suspend fun appendEvent(event: AuditEventDto) {
        client.post("${appConfig.baseUrl}/admin/audit") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(event)
        }
    }
}

@Serializable
private data class AdminAuditoriasResponse(val data: List<AdminAuditoriaDto>)

@Serializable
private data class AdminAuditoriaDto(
    val id: String = "",
    val usuarioId: String? = null,
    val accion: String = "",
    val tablaAfectada: String? = null,
    val registroId: String? = null,
    val creadoEn: String? = null,
)

private fun AdminAuditoriaDto.toAuditEventDto() = AuditEventDto(
    id = id,
    actorName = usuarioId ?: "backend",
    action = accion,
    target = listOfNotNull(tablaAfectada, registroId).joinToString(":").ifBlank { "admin" },
    occurredAt = creadoEn ?: "1970-01-01T00:00:00Z",
    metadata = mapOf(
        "tabla" to tablaAfectada.orEmpty(),
        "registroId" to registroId.orEmpty(),
    ),
)
