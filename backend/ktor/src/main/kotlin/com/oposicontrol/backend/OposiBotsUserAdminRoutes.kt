package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

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

fun Route.oposiBotsUserAdminRoutes(gateway: OposiBotsUserAdminGateway) {
    route("/api/v1/admin/oposibots/usuarios") {
        get {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val search = call.request.queryParameters["search"]
            val users = gateway.listUsers(limit, offset, search)
            call.respond(OposiBotsUserAdminListResponse(users.map { it.toResponseDto() }))
        }
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val user = gateway.getUser(id) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(OposiBotsUserAdminSingleResponse(user.toResponseDto()))
        }
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
            call.respond(HttpStatusCode.Created, OposiBotsUserAdminSingleResponse(created.toResponseDto()))
        }
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
        }
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val deleted = gateway.deleteUser(id)
            if (!deleted) throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
