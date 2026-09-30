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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
    // MASTER4-API-004: el `id` legacy es un ENTERO de `public.usuario`; el canonico es un UUID, asi que no hay sucesor
    // por-id direccionable: GET/PATCH apuntan a la coleccion canonica, y crear/borrar (sin equivalente canonico: el alta
    // es por invitacion/Auth y el borrado es el comando diferido DEC-008) NO anuncian sucesor (`successorPath = null`).
    // Mismo `endpointId` por recurso => un unico contador `legacy_route_requests_total{endpoint=...}`.
    val BOT_ADMIN_001 = LegacyEndpoint(
        "BOT-ADMIN-001", "GET", "/api/v1/admin/oposibots/usuarios", "/api/v1/admin/usuarios",
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
    val BOT_ADMIN_001_POST = LegacyEndpoint(
        "BOT-ADMIN-001", "POST", "/api/v1/admin/oposibots/usuarios", null,
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
    val BOT_ADMIN_002 = LegacyEndpoint(
        "BOT-ADMIN-002", "GET/PATCH", "/api/v1/admin/oposibots/usuarios/{id}", "/api/v1/admin/usuarios",
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
    val BOT_ADMIN_002_DELETE = LegacyEndpoint(
        "BOT-ADMIN-002", "DELETE", "/api/v1/admin/oposibots/usuarios/{id}", null,
        LegacyEndpoints.SLICE_1_DEPRECATED_AT,
    )
}

/**
 * MASTER-AUTH-001: guardas de las operaciones destructivas de la superficie legacy.
 *
 * - [isAdministrative]: ¿el `auth_user_id` tiene rol `admin`/`superadmin`? Una cuenta de administración no se
 *   borra (borrado FÍSICO de la identidad de Auth) por esta ruta legacy. Un fallo de lectura propaga (500).
 * - [audit]: fila en `auditoria_log` (actor = sub del JWT). MASTER2-OBS-006: cada alta, modificacion y baja
 *   escribe una fila de INTENCION antes de mutar (OBLIGATORIA: si no se puede escribir, la petición falla sin
 *   mutar nada) y una de RESULTADO despues (si esta falla, se registra en ERROR con la carga y no se finge error).
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
            val requestId = call.ensureRequestId()
            // MASTER2-OBS-006: auditoria de INTENCION antes de mutar (obligatoria: si falla, no se crea nada).
            policy.audit(ACTION_CREATE_INTENT, null, intentDetail(requestId))
            val created = policy.auditingFailure(ACTION_CREATE_FAILED, null, intentDetail(requestId)) {
                gateway.createUser(
                    OposiBotsUserAdminCreateRequest(
                        email = body.email,
                        password = body.password,
                        nombre = body.nombre,
                        apellidos = body.apellidos,
                        username = body.username,
                        especialidadRegistro = body.especialidadRegistro,
                    ),
                )
            }
            policy.auditOutcome(ACTION_CREATED, created.authUserId, created.auditDetail(requestId))
            call.respond(HttpStatusCode.Created, OposiBotsUserAdminSingleResponse(created.toResponseDto()))
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_001_POST)
        patch("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val body = call.receive<OposiBotsUserAdminUpdateRequestBody>()
            val requestId = call.ensureRequestId()
            // MASTER2-OBS-006: el PATCH tambien se audita (antes no). Intencion obligatoria antes de mutar.
            policy.audit(ACTION_UPDATE_INTENT, null, intentDetail(requestId, legacyId = id, fields = body.changedFields()))
            val updated = policy.auditingFailure(ACTION_UPDATE_FAILED, null, intentDetail(requestId, legacyId = id, fields = body.changedFields())) {
                gateway.updateUser(
                    id,
                    OposiBotsUserAdminUpdateRequest(
                        nombre = body.nombre,
                        apellidos = body.apellidos,
                        username = body.username,
                        especialidadRegistro = body.especialidadRegistro,
                        perfilCompleto = body.perfilCompleto,
                    ),
                ) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            }
            policy.auditOutcome(ACTION_UPDATED, updated.authUserId, updated.auditDetail(requestId, fields = body.changedFields()))
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
            val requestId = call.ensureRequestId()
            // MASTER2-OBS-006: la baja es IRREVERSIBLE; la auditoria obligatoria va ANTES (si falla, no se borra nada).
            // Antes iba despues: un fallo devolvia 500 tras borrar y el reintento (404) ya nunca la escribia.
            policy.audit(ACTION_DELETE_INTENT, target.authUserId, target.auditDetail(requestId))
            policy.auditingFailure(ACTION_DELETE_FAILED, target.authUserId, target.auditDetail(requestId)) {
                if (!gateway.deleteUser(id)) throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            }
            policy.auditOutcome(ACTION_DELETED, target.authUserId, target.auditDetail(requestId))
            call.respond(HttpStatusCode.NoContent)
        }.legacyRoute(OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002_DELETE)
    }
}

/** Sin email ni nombres: el identificador legacy, el `auth_user_id` y el requestId bastan para trazar la operación. */
private fun OposiBotsUserAdminDto.auditDetail(requestId: String, fields: List<String>? = null): JsonObject = buildJsonObject {
    put("usuarioLegacyId", id)
    put("authUserId", authUserId)
    put("requestId", requestId)
    fields?.let { put("campos", JsonArray(it.map(::JsonPrimitive))) }
}

private fun intentDetail(requestId: String, legacyId: Int? = null, fields: List<String>? = null): JsonObject = buildJsonObject {
    put("requestId", requestId)
    legacyId?.let { put("usuarioLegacyId", it) }
    fields?.let { put("campos", JsonArray(it.map(::JsonPrimitive))) }
}

/** Nombres (nunca valores) de los campos que el PATCH pide cambiar. */
private fun OposiBotsUserAdminUpdateRequestBody.changedFields(): List<String> = buildList {
    if (nombre != null) add("nombre")
    if (apellidos != null) add("apellidos")
    if (username != null) add("username")
    if (especialidadRegistro != null) add("especialidadRegistro")
    if (perfilCompleto != null) add("perfilCompleto")
}

/**
 * MASTER2-OBS-006: auditoria de RESULTADO, tras la mutacion ya hecha. Su fallo NO convierte una operacion completada
 * en error (la fila de intencion ya existe y un reintento no podria repetir la mutacion): se registra en ERROR con la
 * carga completa para poder reconstruir la fila a mano.
 *
 * MASTER4-IDEM-001: la escritura corre en NonCancellable (antes solo la del camino de FALLO): si la llamada se cancela
 * (cliente desconectado) justo despues de una mutacion ya aplicada, la fila de intencion ya no queda sin su resultado.
 * Tras escribirla se re-comprueba la cancelacion (`ensureActive`): la cancelacion se propaga, nunca se traga. Dentro
 * del NonCancellable exterior del camino de fallo `ensureActive` no lanza y el error original se relanza tal cual.
 */
internal suspend fun OposiBotsUserAdminPolicy.auditOutcome(accion: String, authUserId: String?, detalle: JsonObject) {
    withContext(NonCancellable) {
        try {
            audit(accion, authUserId, detalle)
        } catch (failure: Exception) {
            // En NonCancellable una CancellationException solo puede venir del propio `audit` (p. ej. un timeout
            // interno), no de la cancelacion de esta llamada: se registra como cualquier otro fallo de escritura.
            LegacyUserAdminAuditLogger.error(
                "legacy user admin outcome audit failed accion={} authUserId={} detalle={} error={}",
                accion,
                authUserId,
                detalle.toString(),
                failure::class.simpleName,
            )
        }
    }
    currentCoroutineContext().ensureActive()
}

/**
 * MASTER3-OBS-002: contrapartida de FALLO de una fila de intencion. Antes, si la mutacion fallaba, la fila
 * `*_solicitada` quedaba huerfana y ambigua (¿se hizo o no?). Ahora cada intencion termina en `*_ado` o `*_fallida`:
 * `resultado` = `rechazada` (el gateway respondio que no: no encontrado / error de dominio, nada cambio) o
 * `desconocido` (transporte, 5xx o cancelacion: pudo o no aplicarse). Mejor esfuerzo como el resultado (ERROR si no
 * se puede escribir); el error original se relanza siempre tal cual. Corre en NonCancellable: una desconexion del
 * cliente no deja la intencion sin contrapartida.
 */
private suspend fun <T> OposiBotsUserAdminPolicy.auditingFailure(
    accion: String,
    authUserId: String?,
    detalle: JsonObject,
    mutation: suspend () -> T,
): T = try {
    mutation()
} catch (failure: Throwable) {
    val resultado = when (failure) {
        is OposiBotsUserAdminNotFoundException, is BackendDomainException -> "rechazada"
        else -> "desconocido"
    }
    withContext(NonCancellable) {
        auditOutcome(
            accion,
            authUserId,
            JsonObject(detalle + mapOf("resultado" to JsonPrimitive(resultado), "error" to JsonPrimitive(failure::class.simpleName))),
        )
    }
    throw failure
}

internal const val ACTION_CREATE_FAILED = "oposibots_usuario_creacion_fallida"
internal const val ACTION_UPDATE_FAILED = "oposibots_usuario_actualizacion_fallida"
internal const val ACTION_DELETE_FAILED = "oposibots_usuario_eliminacion_fallida"

internal const val ACTION_CREATE_INTENT = "oposibots_usuario_creacion_solicitada"
internal const val ACTION_CREATED = "oposibots_usuario_creado"
internal const val ACTION_UPDATE_INTENT = "oposibots_usuario_actualizacion_solicitada"
internal const val ACTION_UPDATED = "oposibots_usuario_actualizado"
internal const val ACTION_DELETE_INTENT = "oposibots_usuario_eliminacion_solicitada"
internal const val ACTION_DELETED = "oposibots_usuario_eliminado"

private val LegacyUserAdminAuditLogger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-LEGACY-USER-ADMIN-AUDIT")
