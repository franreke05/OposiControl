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

class AuditApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getEvents(): List<AuditEventDto> {
        return client.get("${appConfig.baseUrl}/admin/audit") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }

    suspend fun appendEvent(event: AuditEventDto) {
        client.post("${appConfig.baseUrl}/admin/audit") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(event)
        }
    }
}
