package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class UsersApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getUsers(): List<UserDto> {
        return client.get("${appConfig.baseUrl}/admin/users") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }

    suspend fun getUser(userId: String): UserDto {
        return client.get("${appConfig.baseUrl}/admin/users/$userId") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }

    suspend fun upsertUser(user: UserDto): UserDto {
        return client.post("${appConfig.baseUrl}/admin/users") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(user)
        }.body()
    }

    suspend fun deleteUser(userId: String) {
        client.delete("${appConfig.baseUrl}/admin/users/$userId") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }
    }
}
