package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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

/**
 * Cuerpo del PATCH (D1-25 `AdminUserPatchRequest`, `additionalProperties: false`): SOLO `nombreMostrar`. Los roles viajan por
 * POST /roles; `id`, `email` e `isActive` no son campos editables y un backend que aplique el contrato los rechazaria.
 */
@Serializable internal data class AdminUserPatchBody(val nombreMostrar: String)

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
    val createdAt: String? = null,
    val authProviders: List<String> = emptyList(),
    val ticketsBalance: Int? = null,
    val data: AdminUserV1Dto? = null,
)

/** D1-22: `items` (AdminUserSummary) y el duplicado legacy `data`. */
@Serializable internal data class AdminUsersV1Response(
    val data: List<AdminUserV1Dto> = emptyList(),
    val items: List<AdminUserV1Dto> = emptyList(),
    /** `OffsetPageEnvelope`: `total` es null si el conteo fallo (nunca se toma por "no hay mas"). */
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

/** Tamano de pagina que pide el cliente: el maximo de D1-22 (`AdminPageRequest.MAX_LIMIT`). */
internal const val USERS_PAGE_LIMIT = 200

/** Tope de `offset` del backend (D1-22): mas alla no hay pagina valida, asi que se corta el bucle. */
internal const val USERS_MAX_OFFSET = 100_000

/**
 * Recorre TODAS las paginas de D1-22. El offset avanza por las filas CRUDAS que devolvio el servidor (no por las que el
 * cliente logra mapear: una fila sin `id` se descarta al mapear pero el servidor ya la conto). Termina cuando la pagina
 * viene vacia o corta, o cuando ya se leyeron `total` filas. El resultado se deduplica por `id` (un insert concurrente
 * desplaza las paginas y repetiria filas; `LazyColumn` revienta con claves duplicadas). Si se alcanza [USERS_MAX_OFFSET]
 * sin haber terminado, la lista seria incompleta: se falla en voz alta en vez de truncar en silencio.
 */
internal suspend fun collectAllUsers(fetchPage: suspend (limit: Int, offset: Int) -> AdminUsersV1Response): List<UserDto> {
    val all = LinkedHashMap<String, UserDto>()
    var offset = 0
    while (offset <= USERS_MAX_OFFSET) {
        val page = fetchPage(USERS_PAGE_LIMIT, offset)
        val rawCount = if (page.data.isNotEmpty()) page.data.size else page.items.size
        if (rawCount == 0) return all.values.toList()
        for (row in page.toUserDtos()) all.putIfAbsent(row.id, row)
        offset += rawCount
        val pageSize = page.limit?.takeIf { it > 0 } ?: USERS_PAGE_LIMIT
        if (rawCount < pageSize) return all.values.toList()
        if (page.total != null && offset >= page.total) return all.values.toList()
    }
    error("lista de usuarios incompleta: se alcanzo el tope de offset $USERS_MAX_OFFSET sin llegar al final")
}

/**
 * Une el canon con el legacy. `isActive` es SOLO el legacy (`!es_invitado`): si falta queda null, nunca se deriva de
 * `accountStatus` (son dos conceptos distintos). Un estado ausente o no reconocido queda en UNKNOWN, nunca activo.
 * Una fila sin `id` se descarta (null) y un `createdAt` ausente queda null: no se fabrican.
 */
private fun merge(canon: AdminUserV1Dto?, legacy: AdminUserV1Dto?): UserDto? {
    val c = canon ?: AdminUserV1Dto()
    val l = legacy ?: AdminUserV1Dto()
    val status = c.accountStatus ?: l.accountStatus
    val id = c.id.ifBlank { l.id }
    if (id.isBlank()) return null
    return UserDto(
        id = id,
        fullName = c.fullName ?: l.fullName ?: "",
        email = c.email ?: l.email ?: "",
        roleIds = c.roleIds.ifEmpty { l.roleIds },
        isActive = l.isActive,
        createdAt = c.createdAt?.ifBlank { null } ?: l.createdAt?.ifBlank { null },
        ticketsBalance = c.ticketsBalance ?: l.ticketsBalance,
        accountStatus = status,
        username = c.username ?: l.username,
        authProviders = c.authProviders.ifEmpty { l.authProviders },
        onboardingCompleted = c.onboardingCompleted ?: l.onboardingCompleted,
    )
}

internal fun AdminUsersV1Response.toUserDtos(): List<UserDto> {
    if (data.isEmpty()) return items.mapNotNull { merge(it, null) }
    val byId = items.associateBy { it.id }
    return data.mapNotNull { merge(byId[it.id], it) }
}

internal fun AdminUserV1Dto.toUserDto(): UserDto =
    merge(this.takeIf { it.id.isNotBlank() }, data) ?: error("backend devolvio un usuario sin id")

class UsersApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getUsers(): List<UserDto> = collectAllUsers { limit, offset ->
        client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_USUARIOS)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            parameter("limit", limit)
            parameter("offset", offset)
        }.checkedBody<AdminUsersV1Response>(BackendRoutes.ADMIN_V1_USUARIOS)
    }

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
            setBody(AdminUserPatchBody(nombreMostrar = user.fullName))
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
