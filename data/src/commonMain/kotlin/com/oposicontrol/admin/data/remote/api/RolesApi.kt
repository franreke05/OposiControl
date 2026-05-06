package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.RoleDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class RolesApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getRoles(): List<RoleDto> {
        return client.get("${appConfig.baseUrl}/admin/roles") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }

    suspend fun upsertRole(role: RoleDto): RoleDto {
        return client.post("${appConfig.baseUrl}/admin/roles") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(role)
        }.body()
    }
}
