package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import io.ktor.server.plugins.BadRequestException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Superficie de Control para las funciones V1 (reglas de puntuacion, importacion por lotes, invitaciones, foro,
 * comunidad/precios, pedidos y alertas).
 *
 * ### Reglas que gobiernan este fichero
 *
 * 1. **La interfaz no es la guarda.** Cada formulario del panel valida por comodidad; aqui se valida DE NUEVO, antes de
 *    la primera peticion a Supabase, y la base vuelve a validar (CHECK, triggers, RPC). Si una capa falla, las otras
 *    dos siguen cerrando.
 * 2. **El actor es el `sub` del JWT** ([AdminAuditActor]); nunca viene del cuerpo.
 * 3. **Cuerpos estrictos**: una clave desconocida es un 400, no se ignora (un campo de mas es un intento de escribir
 *    algo que el formulario no ofrece).
 * 4. **Nada de PII ni texto del cliente en los errores**: los mensajes son constantes.
 *
 * No hay dominio de otras verticales aqui: el CRUD de examenes, preguntas y catalogo sigue en [AdminV1Gateway].
 */
class AdminControlV1Gateway internal constructor(
    internal val transport: SupabaseRequestTransport,
    internal val json: Json,
    private val actor: () -> String? = AdminAuditActor::current,
) {
    internal fun actorId(): String? = actor()

    /** Actor obligatorio: una escritura sin persona identificable (token estatico) es un 403, como en moderacion. */
    internal fun requireActor(): String =
        actor()?.takeIf { it.isUuid() } ?: throw CanonicalErrorCode.FORBIDDEN.exception()

    internal fun get(path: String): String = transport.get(path)

    internal fun send(method: String, path: String, body: String?, prefer: String? = null): String =
        transport.send(method = method, path = path, body = body, prefer = prefer)

    internal fun rpc(function: String, body: JsonObject): String = transport.rpc(function, body.toString())

    /**
     * RPC que puede no existir todavia en el servidor (migraciones de la rama de servidor aun sin aplicar): `PGRST202`
     * (funcion no encontrada) o 404 se traducen a [CONTROL_RPC_NOT_PROVISIONED] en vez de a un 500 opaco.
     */
    internal fun rpcOrNotProvisioned(function: String, body: JsonObject): String {
        val response = transport.rpcRaw(function, body.toString())
        if (response.status in 200..299) return response.body
        if (response.status == 404 || response.body.contains("PGRST202") || response.body.contains("PGRST205")) {
            throw domainError(CONTROL_RPC_NOT_PROVISIONED)
        }
        mapSupabaseDomainError(response.status, response.body)?.let { throw it }
        throw IllegalStateException("Supabase rpc $function -> ${response.status}")
    }

    /** Escribe una fila de auditoria con el actor del JWT. Fallar es fallar la operacion (`required`): sin rastro no se escribe. */
    internal fun audit(accion: String, tabla: String, registroId: String?, antes: JsonElement? = null, despues: JsonElement? = null) {
        val row = buildJsonObject {
            actor()?.let { put("usuario_id", JsonPrimitive(it)) }
            put("accion", JsonPrimitive(accion))
            put("tabla_afectada", JsonPrimitive(tabla))
            if (registroId != null) put("registro_id", JsonPrimitive(registroId))
            if (antes != null) put("antes", antes)
            if (despues != null) put("despues", despues)
        }
        transport.send("POST", "/rest/v1/auditoria_log", JsonArray(listOf(row)).toString(), prefer = "return=minimal")
    }

    internal fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
}

/** La funcion de servidor que necesita la operacion todavia no existe en este entorno. 501 honesto, no un 500. */
internal const val CONTROL_RPC_NOT_PROVISIONED = "CONTROL_RPC_NOT_PROVISIONED"

/** Rechazo de validacion de Control: 422 con codigo estable y mensaje constante. */
internal fun controlReject(code: String, message: String): Nothing =
    throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

/** 400 para cuerpos mal formados: sin nombres de campo del cliente en el mensaje. */
internal fun controlBadBody(): Nothing = throw BadRequestException("El cuerpo de la peticion no es valido.")

/** Mensaje de longitud maxima en puntos de codigo, sin caracteres de control ni bidi/zero-width. */
internal fun String.controlSanitized(maxCodePoints: Int, allowNewlines: Boolean = false): String {
    val out = StringBuilder()
    var count = 0
    this.codePoints().forEach { cp ->
        if (count >= maxCodePoints) return@forEach
        val type = Character.getType(cp)
        val control = type == Character.CONTROL.toInt() && !(allowNewlines && (cp == '\n'.code))
        val format = type == Character.FORMAT.toInt() // incluye bidi, zero-width, ZWJ
        val separator = type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        if (control || format || separator) return@forEach
        out.appendCodePoint(cp)
        count++
    }
    return out.toString().trim()
}

internal fun JsonObject.requireOnlyKeys(allowed: Set<String>) {
    if (keys.any { it !in allowed }) controlBadBody()
}

internal fun JsonObject.optionalString(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else controlBadBody()
    else -> controlBadBody()
}

internal fun JsonObject.requiredBool(key: String): Boolean {
    val v = this[key] as? JsonPrimitive ?: controlBadBody()
    if (v.isString) controlBadBody()
    return v.booleanOrNull ?: controlBadBody()
}

internal fun JsonObject.requiredInt(key: String): Int {
    val v = this[key] as? JsonPrimitive ?: controlBadBody()
    if (v.isString) controlBadBody()
    return v.intOrNull ?: controlBadBody()
}

internal fun JsonObject.optionalInt(key: String): Int? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (v.isString) controlBadBody() else v.intOrNull ?: controlBadBody()
    else -> controlBadBody()
}

internal fun JsonObject.optionalBool(key: String): Boolean? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (v.isString) controlBadBody() else v.booleanOrNull ?: controlBadBody()
    else -> controlBadBody()
}

internal fun JsonElement?.contentOrNullSafe(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/**
 * Punto unico de montaje de las rutas de Control V1. Se instala dentro del bloque `authenticate("gateway-auth")` de
 * `Application.kt`: el rol admin, la cuenta activa y el actor de auditoria los resuelve `AdminAuthorizationPlugin` /
 * `installAdminAuditActor` ANTES de que llegue aqui (token estatico confinado a superficies legacy, sin CSRF porque no
 * hay cookies de sesion: la credencial es siempre un bearer explicito).
 */
fun io.ktor.server.routing.Route.adminControlV1Routes(gateway: AdminControlV1Gateway) {
    adminControlGradingRoutes(gateway)
    adminControlConfigRoutes(gateway)
    adminControlReferralRoutes(gateway)
    adminControlForumRoutes(gateway)
    adminControlCommerceRoutes(gateway)
    adminControlAlertsRoutes(gateway)
    adminControlOfficialQuestionsRoutes(gateway)
}
