package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.SettingsDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class SettingsApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getSettings(): SettingsDto {
        return client.get("${appConfig.baseUrl}/admin/settings") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }

    suspend fun updateSettings(settings: SettingsDto): SettingsDto {
        return client.post("${appConfig.baseUrl}/admin/settings") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(settings)
        }.body()
    }
}
