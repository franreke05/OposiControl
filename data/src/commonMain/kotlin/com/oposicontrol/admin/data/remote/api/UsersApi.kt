package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import com.oposicontrol.admin.domain.model.AccountStatus
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

/** Cuerpo del PATCH: solo los campos que el contrato `AdminUserPatchRequest` ya conocia. */
@Serializable private data class AdminUserPatchBody(
    val id: String = "",
    val fullName: String = "",
    val email: String = "",
    val roleIds: List<String> = emptyList(),
    val isActive: Boolean = true,
)

/**
 * Usuario tal y como llega hoy: un objeto `AdminUser` canonico (D1-23) con el duplicado legacy en `data`
 * (`isActive` = `!es_invitado`). Todo es tolerante: un backend anterior a Slice 1 solo trae `data`.
 */
@Serializable internal data class AdminUserV1Dto(
    val id: String = "",
    val username: String? = null,
    val fullName: String? = null,
    val email: String? = null,
    val roleIds: List<String> = emptyList(),
    val isActive: Boolean? = null,
    val accountStatus: String? = null,
    val onboardingCompleted: Boolean? = null,
    val createdAt: String = "",
    val authProviders: List<String> = emptyList(),
    val ticketsBalance: Int? = null,
    val data: AdminUserV1Dto? = null,
)

/** D1-22: `items` (AdminUserSummary) y el duplicado legacy `data`. */
@Serializable internal data class AdminUsersV1Response(
    val data: List<AdminUserV1Dto> = emptyList(),
    val items: List<AdminUserV1Dto> = emptyList(),
)

/**
 * Une el canon con el legacy. `isActive` sale del legacy (`!es_invitado`); si el backend algun dia quita
 * `data`, se usa `accountStatus == activa`. Un estado ausente o no reconocido queda en UNKNOWN, nunca activo.
 */
private fun merge(canon: AdminUserV1Dto?, legacy: AdminUserV1Dto?): UserDto {
    val c = canon ?: AdminUserV1Dto()
    val l = legacy ?: AdminUserV1Dto()
    val status = c.accountStatus ?: l.accountStatus
    return UserDto(
        id = c.id.ifBlank { l.id },
        fullName = c.fullName ?: l.fullName ?: "",
        email = c.email ?: l.email ?: "",
        roleIds = c.roleIds.ifEmpty { l.roleIds },
        isActive = l.isActive ?: (status == AccountStatus.ACTIVE.wire),
        createdAt = c.createdAt.ifBlank { l.createdAt },
        ticketsBalance = c.ticketsBalance ?: l.ticketsBalance ?: 0,
        accountStatus = status,
        username = c.username ?: l.username,
        authProviders = c.authProviders.ifEmpty { l.authProviders },
        onboardingCompleted = c.onboardingCompleted ?: l.onboardingCompleted,
    )
}

internal fun AdminUsersV1Response.toUserDtos(): List<UserDto> {
    if (data.isEmpty()) return items.map { merge(it, null) }
    val byId = items.associateBy { it.id }
    return data.map { merge(byId[it.id], it) }
}

internal fun AdminUserV1Dto.toUserDto(): UserDto = merge(this.takeIf { it.id.isNotBlank() }, data)

class UsersApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getUsers(): List<UserDto> =
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_USUARIOS)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody<AdminUsersV1Response>(BackendRoutes.ADMIN_V1_USUARIOS).toUserDtos()

    suspend fun getUser(userId: String): UserDto =
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.adminV1Usuario(userId))) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.checkedBody<AdminUserV1Dto>(BackendRoutes.adminV1Usuario(userId)).toUserDto()

    suspend fun upsertUser(user: UserDto): UserDto {
        if (user.id.isBlank()) error("Crear usuarios no está disponible desde la app admin. Usa el dashboard de Supabase.")
        // PATCH nombre_mostrar en perfiles_usuario
        client.patch(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.adminV1Usuario(user.id))) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(AdminUserPatchBody(id = user.id, fullName = user.fullName, email = user.email, roleIds = user.roleIds, isActive = user.isActive))
        }.checkedBody<kotlinx.serialization.json.JsonObject>(BackendRoutes.adminV1Usuario(user.id))
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
