package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable private data class RolesBody(val roleIds: List<String>)

@Serializable private data class AdminUserV1Dto(
    val id: String = "",
    val fullName: String = "",
    val email: String = "",
    val roleIds: List<String> = emptyList(),
    val isActive: Boolean = true,
    val createdAt: String = "",
)
@Serializable private data class AdminUsersV1Response(val data: List<AdminUserV1Dto>)
@Serializable private data class AdminUserV1Response(val data: AdminUserV1Dto)

private fun AdminUserV1Dto.toDto() = UserDto(
    id = id,
    fullName = fullName,
    email = email,
    roleIds = roleIds,
    isActive = isActive,
    createdAt = createdAt,
)

class UsersApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getUsers(): List<UserDto> =
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_USUARIOS)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody<AdminUsersV1Response>(BackendRoutes.ADMIN_V1_USUARIOS).data.map { it.toDto() }

    suspend fun getUser(userId: String): UserDto =
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.adminV1Usuario(userId))) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody<AdminUserV1Response>(BackendRoutes.adminV1Usuario(userId)).data.toDto()

    suspend fun upsertUser(user: UserDto): UserDto {
        if (user.id.isBlank()) error("Crear usuarios no está disponible desde la app admin. Usa el dashboard de Supabase.")
        // PATCH nombre_mostrar en perfiles_usuario
        client.patch(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.adminV1Usuario(user.id))) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(AdminUserV1Dto(id = user.id, fullName = user.fullName, email = user.email, roleIds = user.roleIds, isActive = user.isActive))
        }.checkedBody<AdminUserV1Response>(BackendRoutes.adminV1Usuario(user.id))
        // Sync roles
        client.post(BackendRoutes.url(appConfig.baseUrl, "${BackendRoutes.adminV1Usuario(user.id)}/roles")) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(RolesBody(user.roleIds))
        }.checkedBody<kotlinx.serialization.json.JsonObject>("${BackendRoutes.adminV1Usuario(user.id)}/roles")
        return getUser(user.id)
    }

    suspend fun deleteUser(userId: String) {
        // ponytail: eliminar usuarios requiere Supabase Auth admin — no disponible desde KMP
        error("Eliminar usuarios no está disponible. Usa el dashboard de Supabase.")
    }

    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        return body()
    }
}
