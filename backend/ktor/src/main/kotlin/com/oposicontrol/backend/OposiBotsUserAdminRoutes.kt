package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class OposiBotsUserAdminListResponse(val data: List<OposiBotsUserAdminResponseDto>)

@Serializable
data class OposiBotsUserAdminSingleResponse(val data: OposiBotsUserAdminResponseDto)

@Serializable
data class OposiBotsUserAdminResponseDto(
    val id: Int,
    val authUserId: String?,
    val email: String?,
    val nombre: String?,
    val apellidos: String?,
    val username: String?,
    val especialidadRegistro: String?,
    val perfilCompleto: Boolean,
    val perfilCompletadoAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

private fun OposiBotsUserAdminDto.toResponseDto() = OposiBotsUserAdminResponseDto(
    id, authUserId, email, nombre, apellidos, username, especialidadRegistro, perfilCompleto, perfilCompletadoAt, createdAt, updatedAt,
)

@Serializable
data class OposiBotsUserAdminCreateRequestBody(
    val email: String,
    val password: String? = null,
    val nombre: String,
    val apellidos: String,
    val username: String? = null,
    val especialidadRegistro: String,
)

@Serializable
data class OposiBotsUserAdminUpdateRequestBody(
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    val especialidadRegistro: String? = null,
    val perfilCompleto: Boolean? = null,
)

class OposiBotsUserAdminNotFoundException(message: String) : Exception(message)

/**
 * MASTER-AUTH-001: superficie LEGACY (`public.usuario`, id entero) sin consumidor activo conocido
 * (OPOSIBOT_CONSUMER_MATRIX). Queda marcada como deprecada (cabeceras `Deprecation`/`Link` y contador
 * `legacy_route_requests_total`) con sucesora canónica `/api/v1/admin/usuarios`. Su sustitución por un flujo
 * de borrado diferido (DEC-008) está registrada en ARCHITECTURE_DELTA_S1.
 */
internal object OposiBotsUserAdminLegacyEndpoints {
    val BOT_ADMIN_001 = LegacyEndpoint(
        "BOT-ADMIN-001", "GET/POST", "/api/v1/admin/oposibots/usuarios", "/api/v1/admin/usuarios",
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
    val BOT_ADMIN_002 = LegacyEndpoint(
        "BOT-ADMIN-002", "GET/PATCH/DELETE", "/api/v1/admin/oposibots/usuarios/{id}", "/api/v1/admin/usuarios/{id}",
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
}

/**
 * MASTER-AUTH-001: guardas de las operaciones destructivas de la superficie legacy.
 *
 * - [isAdministrative]: ¿el `auth_user_id` tiene rol `admin`/`superadmin`? Una cuenta de administración no se
 *   borra (borrado FÍSICO de la identidad de Auth) por esta ruta legacy. Un fallo de lectura propaga (500).
 * - [audit]: fila en `auditoria_log` (actor = sub del JWT) de cada alta y baja; OBLIGATORIA: si no se puede
 *   escribir, la petición falla.
 *
 * Sin valor por defecto a propósito: quien monte la ruta tiene que decidir las dos cosas.
 */
class OposiBotsUserAdminPolicy(
    val isAdministrative: suspend (authUserId: String) -> Boolean,
    val audit: suspend (accion: String, authUserId: String?, detalle: JsonObject) -> Unit,
)

fun Route.oposiBotsUserAdminRoutes(gateway: OposiBotsUserAdminGateway, policy: OposiBotsUserAdminPolicy) {
    route("/api/v1/admin/oposibots/usuarios") {
        get {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val search = call.request.queryParameters["search"]
            val users = gateway.listUsers(limit, offset, search)
            call.respond(OposiBotsUserAdminListResponse(users.map { it.toResponseDto() }))
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_001)
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val user = gateway.getUser(id) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(OposiBotsUserAdminSingleResponse(user.toResponseDto()))
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002)
        post {
            val body = call.receive<OposiBotsUserAdminCreateRequestBody>()
            val created = gateway.createUser(
                OposiBotsUserAdminCreateRequest(
                    email = body.email,
                    password = body.password,
                    nombre = body.nombre,
                    apellidos = body.apellidos,
                    username = body.username,
                    especialidadRegistro = body.especialidadRegistro,
                ),
            )
            policy.audit("oposibots_usuario_creado", created.authUserId, created.auditDetail())
            call.respond(HttpStatusCode.Created, OposiBotsUserAdminSingleResponse(created.toResponseDto()))
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_001)
        patch("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val body = call.receive<OposiBotsUserAdminUpdateRequestBody>()
            val updated = gateway.updateUser(
                id,
                OposiBotsUserAdminUpdateRequest(
                    nombre = body.nombre,
                    apellidos = body.apellidos,
                    username = body.username,
                    especialidadRegistro = body.especialidadRegistro,
                    perfilCompleto = body.perfilCompleto,
                ),
            ) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(OposiBotsUserAdminSingleResponse(updated.toResponseDto()))
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002)
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val target = gateway.getUser(id) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            // MASTER-AUTH-001: ni la propia cuenta del actor ni una cuenta de administración se borran aquí.
            target.authUserId?.let { authUserId ->
                val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                if (actorId != null && authUserId.equals(actorId, ignoreCase = true)) {
                    throw AppForbidden("No puedes eliminar tu propia cuenta desde el panel.")
                }
                if (policy.isAdministrative(authUserId)) {
                    throw AppForbidden("Una cuenta de administración no se elimina desde esta superficie legacy.")
                }
            }
            val deleted = gateway.deleteUser(id)
            if (!deleted) throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            policy.audit("oposibots_usuario_eliminado", target.authUserId, target.auditDetail())
            call.respond(HttpStatusCode.NoContent)
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002)
    }
}

/** Sin email ni nombres: el identificador legacy y el `auth_user_id` bastan para trazar la operación. */
private fun OposiBotsUserAdminDto.auditDetail(): JsonObject = buildJsonObject {
    put("usuarioLegacyId", id)
    put("authUserId", authUserId)
}
