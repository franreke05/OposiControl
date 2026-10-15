package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.AuditMovementDetailDto
import com.oposicontrol.admin.data.remote.dto.AuditMovementsPageDto
import com.oposicontrol.admin.domain.model.AuditFilter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Lecturas de la pestaña «Auditoría».
 *
 * Los filtros y la paginación los resuelve el servidor: aquí solo viajan. La ruta antigua (`GET /api/v1/admin/auditoria`,
 * las 100 últimas filas sin filtrar) ya no se llama desde esta clase; el servidor la conserva para las versiones del
 * panel que siguen instaladas.
 */
class AuditApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getMovements(filter: AuditFilter, cursor: String?): AuditMovementsPageDto {
        return client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_AUDITORIA_MOVIMIENTOS)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            parameter("limit", PAGE_SIZE)
            // Un filtro vacío no viaja: el servidor lo trataría como «sin filtro», pero la URL dice lo que se pidió.
            filter.action?.takeIf { it.isNotBlank() }?.let { parameter("accion", it) }
            filter.actorId?.takeIf { it.isNotBlank() }?.let { parameter("actor", it) }
            filter.from?.takeIf { it.isNotBlank() }?.let { parameter("desde", it) }
            filter.until?.takeIf { it.isNotBlank() }?.let { parameter("hasta", it) }
            cursor?.takeIf { it.isNotBlank() }?.let { parameter("cursor", it) }
        }.checkedBody(BackendRoutes.ADMIN_V1_AUDITORIA_MOVIMIENTOS)
    }

    suspend fun getMovementDetail(id: String): AuditMovementDetailDto {
        return client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.adminV1AuditoriaMovimiento(id))) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody(BackendRoutes.adminV1AuditoriaMovimiento(id))
    }

    /** Un 4xx/5xx es un fallo con su código, nunca un cuerpo de error deserializado como si fueran datos. */
    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        return body()
    }

    private companion object {
        /** El tamaño por defecto del servidor, dicho en voz alta para que «Cargar más» traiga siempre lo mismo. */
        const val PAGE_SIZE = 50
    }
}
