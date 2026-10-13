package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.PermissionDto
import com.oposicontrol.admin.data.remote.dto.RoleDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable private data class AdminRoleV1Dto(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val description: String = "",
    val permissions: List<AdminPermissionV1Dto> = emptyList(),
)
@Serializable private data class AdminPermissionV1Dto(val key: String = "", val label: String = "")
@Serializable private data class AdminRolesV1Response(val data: List<AdminRoleV1Dto>)

private fun AdminRoleV1Dto.toDto() = RoleDto(
    id = id,
    name = name,
    // El backend ya lo enviaba; este mapeo lo tiraba. Sin él, el cliente no puede
    // distinguir un rol de administración de uno de usuario. Ver `Role.code`.
    code = code,
    permissions = permissions.map { PermissionDto(it.key, it.label) },
)

class RolesApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getRoles(): List<RoleDto> =
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_ROLES)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody<AdminRolesV1Response>(BackendRoutes.ADMIN_V1_ROLES).data.map { it.toDto() }

    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        return body()
    }
}
