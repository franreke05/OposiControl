package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Foro / Red, lado Control: cola de moderacion, acciones (`admin_foro_moderate`), canales (`categorias_oposired`),
 * lista de bloqueo de texto (`foro_bloqueos`) y restricciones de escritura (`restricciones_foro`).
 *
 * ### Contrato de servidor (E1-V1-W5-FORO-CONTRACT.md, migracion 20261022101900)
 * - `publicaciones_oposired`, `comentarios_oposired`, `reportes_contenido` y `categorias_oposired` conservan los permisos
 *   de `service_role`: se leen y (canales) se escriben por PostgREST.
 * - `admin_foro_moderate(p_actor, p_tipo, p_id, p_accion, p_dias, p_notas)` es la UNICA via de moderacion: audita en la
 *   misma transaccion con el actor del JWT, retira la imagen al rechazar u ocultar y escribe la restriccion al silenciar.
 * - `foro_bloqueos` y `restricciones_foro` NO tienen permiso alguno para `service_role` (solo funciones). Las funciones
 *   `admin_foro_bloqueos_*` y `admin_foro_restricciones_*` NO existen todavia: se llaman por el nombre propuesto en
 *   `E6-V1-PANEL-04.md` y, si faltan, 501 `CONTROL_RPC_NOT_PROVISIONED`.
 *
 * ### Reglas
 * - El actor es el JWT; las acciones destructivas (rechazar, ocultar, silenciar) exigen motivo (3..200).
 * - El texto del foro es contenido de usuarios: se recorta, se sanea (sin control/bidi/zero-width) y el panel lo pinta como
 *   TEXTO (Compose no interpreta HTML), nunca como marcado.
 */
internal object ForumRules {
    val TIPOS: Set<String> = setOf("publicacion", "comentario")
    val ACCIONES: Set<String> = setOf("aprobar", "rechazar", "ocultar", "restaurar", "silenciar")
    val ACCIONES_CON_MOTIVO: Set<String> = setOf("rechazar", "ocultar", "silenciar")
    val ESTADOS_COLA_PUBLICACION: Set<String> = setOf("pendiente_moderacion", "oculta", "reportada")
    val ESTADOS_COLA_COMENTARIO: Set<String> = setOf("pendiente_moderacion", "oculto", "reportada")
    const val DIAS_MIN = 1
    const val DIAS_MAX = 365
    const val DIAS_DEFAULT = 7
    const val MOTIVO_MIN = 3
    const val MOTIVO_MAX = 200
    const val SNIPPET = 400
    const val CHANNEL_NAME_MAX = 60
    const val CHANNEL_DESC_MAX = 300
    const val PATTERN_MIN = 2
    const val PATTERN_MAX = 100
    private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    data class Moderation(val tipo: String, val id: String, val accion: String, val dias: Int?, val notas: String?)

    fun moderation(raw: JsonObject): Moderation {
        raw.requireOnlyKeys(setOf("tipo", "id", "accion", "dias", "motivo"))
        val tipo = raw.optionalString("tipo")?.trim().orEmpty()
        if (tipo !in TIPOS) controlReject("FORO_TYPE_INVALID", "El tipo de contenido no es válido.")
        val id = raw.optionalString("id")?.trim().orEmpty()
        if (!id.isUuid()) controlBadBody()
        val accion = raw.optionalString("accion")?.trim().orEmpty()
        if (accion !in ACCIONES) controlReject("FORO_ACTION_INVALID", "La acción de moderación no es válida.")
        val motivo = raw.optionalString("motivo")?.controlSanitized(MOTIVO_MAX)?.ifEmpty { null }
        if (accion in ACCIONES_CON_MOTIVO && (motivo == null || motivo.length < MOTIVO_MIN)) {
            controlReject("FORO_REASON_REQUIRED", "Esta acción exige un motivo (mínimo $MOTIVO_MIN caracteres).")
        }
        val dias = raw.optionalInt("dias")
        if (accion == "silenciar") {
            if (dias != null && (dias < DIAS_MIN || dias > DIAS_MAX)) {
                controlReject("FORO_DAYS_INVALID", "El silencio va de $DIAS_MIN a $DIAS_MAX días.")
            }
        } else if (dias != null) {
            controlReject("FORO_DAYS_INVALID", "Los días solo se indican al silenciar.")
        }
        return Moderation(tipo, id.lowercase(), accion, if (accion == "silenciar") dias ?: DIAS_DEFAULT else null, motivo)
    }

    fun slug(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.length !in 2..60 || !SLUG.matches(s)) {
            controlReject("FORO_CHANNEL_SLUG_INVALID", "El identificador del canal usa minúsculas, números y guiones (2 a 60).")
        }
        return s
    }

    fun pattern(raw: String): String {
        val p = raw.controlSanitized(PATTERN_MAX).lowercase()
        if (p.length < PATTERN_MIN) controlReject("FORO_PATTERN_INVALID", "El patrón tiene que tener entre $PATTERN_MIN y $PATTERN_MAX caracteres.")
        return p
    }
}

@Serializable
data class AdminForumQueueItemDto(
    val tipo: String,
    val id: String,
    val publicacionId: String? = null,
    val canal: String? = null,
    val titulo: String? = null,
    val fragmento: String,
    val estado: String,
    val autorHandle: String? = null,
    val creadoEn: String? = null,
    val tieneImagen: Boolean = false,
    val reportesPendientes: Int = 0,
    val motivosReporte: List<String> = emptyList(),
)

@Serializable
data class AdminForumQueueDto(val items: List<AdminForumQueueItemDto>, val nextCursor: String? = null)

@Serializable
data class AdminForumChannelDto(
    val id: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val activa: Boolean = true,
    val posicion: Int = 1,
    val especialidadId: String? = null,
    val soloLectura: Boolean = false,
)

@Serializable
data class AdminForumChannelsResponse(val data: List<AdminForumChannelDto>)

@Serializable
data class AdminForumBlockPatternDto(val patron: String)

@Serializable
data class AdminForumBlockListResponse(val data: List<AdminForumBlockPatternDto>)

@Serializable
data class AdminForumRestrictionDto(
    val usuarioId: String,
    val handle: String? = null,
    val hasta: String? = null,
    val motivo: String? = null,
    val creadoEn: String? = null,
)

@Serializable
data class AdminForumRestrictionsResponse(val data: List<AdminForumRestrictionDto>)

@Serializable
data class AdminForumModerationResultDto(val accion: String)

// ---------------------------------------------------------------- cola

internal fun AdminControlV1Gateway.forumQueue(tipo: String, estado: String, limit: Int, cursor: String?): AdminForumQueueDto {
    val reportedOnly = estado == "reportada"
    val table = if (tipo == "publicacion") "publicaciones_oposired" else "comentarios_oposired"
    val entidad = if (tipo == "publicacion") "publicacion_oposired" else "comentario_oposired"
    val cursorFilter = cursor?.let {
        val (creadoEn, id) = decodeForoCursor(it)
        if (!id.isUuid()) throw CanonicalErrorCode.INVALID_CURSOR.exception()
        "&or=(creado_en.lt.${creadoEn.urlEncode()},and(creado_en.eq.${creadoEn.urlEncode()},id.lt.$id))"
    }.orEmpty()
    val reportIds: Set<String>? = if (reportedOnly) {
        val ids = (json.parseToJsonElement(
            get("/rest/v1/reportes_contenido?select=entidad_id&tipo_entidad=eq.$entidad&estado=eq.pendiente&order=creado_en.desc&limit=100"),
        ) as? JsonArray).orEmpty().mapNotNull { it.jsonObject["entidad_id"].contentOrNullSafe()?.takeIf(String::isUuid) }.toSet()
        if (ids.isEmpty()) return AdminForumQueueDto(emptyList(), null)
        ids
    } else null
    val estadoFilter = if (reportedOnly) {
        "&id=in.(${reportIds!!.joinToString(",")})"
    } else {
        "&estado=eq.$estado"
    }
    val columns = if (tipo == "publicacion") {
        "id,categoria_id,usuario_id,titulo,contenido,estado,creado_en,imagen_id"
    } else {
        "id,publicacion_id,usuario_id,contenido,estado,creado_en"
    }
    val rows = (json.parseToJsonElement(
        get("/rest/v1/$table?select=$columns$estadoFilter$cursorFilter&order=creado_en.desc,id.desc&limit=${limit + 1}"),
    ) as? JsonArray).orEmpty().map { it.jsonObject }
    val page = rows.take(limit)
    val next = if (rows.size > limit) page.last().let { encodeForoCursor(it["creado_en"].contentOrNullSafe().orEmpty(), it["id"].contentOrNullSafe().orEmpty()) } else null
    if (page.isEmpty()) return AdminForumQueueDto(emptyList(), null)

    val authorIds = page.mapNotNull { it["usuario_id"].contentOrNullSafe()?.takeIf(String::isUuid) }.distinct()
    val handles = if (authorIds.isEmpty()) emptyMap() else (json.parseToJsonElement(
        get("/rest/v1/perfiles_usuario?select=usuario_id,username&usuario_id=in.(${authorIds.joinToString(",")})"),
    ) as? JsonArray).orEmpty().associate { it.jsonObject["usuario_id"].contentOrNullSafe().orEmpty() to it.jsonObject["username"].contentOrNullSafe() }
    val categoryIds = page.mapNotNull { it["categoria_id"].contentOrNullSafe()?.takeIf(String::isUuid) }.distinct()
    val channels = if (categoryIds.isEmpty()) emptyMap() else (json.parseToJsonElement(
        get("/rest/v1/categorias_oposired?select=id,nombre&id=in.(${categoryIds.joinToString(",")})"),
    ) as? JsonArray).orEmpty().associate { it.jsonObject["id"].contentOrNullSafe().orEmpty() to it.jsonObject["nombre"].contentOrNullSafe() }
    val pageIds = page.mapNotNull { it["id"].contentOrNullSafe()?.takeIf(String::isUuid) }
    val reports = if (pageIds.isEmpty()) emptyMap() else (json.parseToJsonElement(
        get("/rest/v1/reportes_contenido?select=entidad_id,motivo_codigo&tipo_entidad=eq.$entidad&estado=eq.pendiente&entidad_id=in.(${pageIds.joinToString(",")})&limit=1000"),
    ) as? JsonArray).orEmpty().map { it.jsonObject }.groupBy { it["entidad_id"].contentOrNullSafe().orEmpty() }

    return AdminForumQueueDto(
        items = page.mapNotNull { r ->
            val id = r["id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return@mapNotNull null
            val rep = reports[id].orEmpty()
            AdminForumQueueItemDto(
                tipo = tipo,
                id = id,
                publicacionId = r["publicacion_id"].contentOrNullSafe(),
                canal = r["categoria_id"].contentOrNullSafe()?.let { channels[it] }?.controlSanitized(ForumRules.CHANNEL_NAME_MAX),
                titulo = r["titulo"].contentOrNullSafe()?.controlSanitized(300),
                fragmento = r["contenido"].contentOrNullSafe().orEmpty().controlSanitized(ForumRules.SNIPPET, allowNewlines = true),
                estado = r["estado"].contentOrNullSafe().orEmpty(),
                autorHandle = r["usuario_id"].contentOrNullSafe()?.let { handles[it] }?.controlSanitized(40),
                creadoEn = r["creado_en"].contentOrNullSafe(),
                tieneImagen = r["imagen_id"].contentOrNullSafe() != null,
                reportesPendientes = rep.size,
                motivosReporte = rep.mapNotNull { it["motivo_codigo"].contentOrNullSafe() }.filter { it in setOf("spam", "ofensivo", "incorrecto", "otro") }.distinct().sorted(),
            )
        },
        nextCursor = next,
    )
}

internal fun AdminControlV1Gateway.moderateForum(m: ForumRules.Moderation): AdminForumModerationResultDto {
    val actor = requireActor()
    val body = rpcOrNotProvisioned(
        "admin_foro_moderate",
        buildJsonObject {
            put("p_actor", JsonPrimitive(actor))
            put("p_tipo", JsonPrimitive(m.tipo))
            put("p_id", JsonPrimitive(m.id))
            put("p_accion", JsonPrimitive(m.accion))
            put("p_dias", m.dias?.let(::JsonPrimitive) ?: JsonNull)
            put("p_notas", m.notas?.let(::JsonPrimitive) ?: JsonNull)
        },
    )
    val obj = json.parseToJsonElement(body) as? JsonObject
    return AdminForumModerationResultDto(obj?.get("accion").contentOrNullSafe() ?: m.accion)
}

// ---------------------------------------------------------------- canales

private const val CHANNEL_COLUMNS = "id,nombre,slug,descripcion,activa,posicion,especialidad_id,solo_lectura"

private fun JsonObject.toChannel(): AdminForumChannelDto? {
    val id = this["id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return null
    return AdminForumChannelDto(
        id = id,
        nombre = this["nombre"].contentOrNullSafe().orEmpty().controlSanitized(ForumRules.CHANNEL_NAME_MAX),
        slug = this["slug"].contentOrNullSafe().orEmpty(),
        descripcion = this["descripcion"].contentOrNullSafe()?.controlSanitized(ForumRules.CHANNEL_DESC_MAX),
        activa = this["activa"].let { (it as? JsonPrimitive)?.contentOrNullSafe() != "false" },
        posicion = this["posicion"].intOr(1),
        especialidadId = this["especialidad_id"].contentOrNullSafe(),
        soloLectura = this["solo_lectura"].boolOrFalse(),
    )
}

internal fun AdminControlV1Gateway.listChannels(): List<AdminForumChannelDto> =
    (json.parseToJsonElement(get("/rest/v1/categorias_oposired?select=$CHANNEL_COLUMNS&order=posicion.asc,nombre.asc&limit=200")) as? JsonArray)
        .orEmpty().mapNotNull { it.jsonObject.toChannel() }

private val CHANNEL_KEYS = setOf("nombre", "slug", "descripcion", "activa", "posicion", "especialidadId", "soloLectura")

internal fun parseChannel(raw: JsonObject, creating: Boolean): JsonObject {
    raw.requireOnlyKeys(CHANNEL_KEYS)
    return buildJsonObject {
        if (creating || raw.containsKey("nombre")) {
            val nombre = raw.optionalString("nombre")?.controlSanitized(ForumRules.CHANNEL_NAME_MAX).orEmpty()
            if (nombre.length < 2) controlReject("FORO_CHANNEL_NAME_INVALID", "El nombre del canal tiene entre 2 y ${ForumRules.CHANNEL_NAME_MAX} caracteres.")
            put("nombre", JsonPrimitive(nombre))
        }
        if (creating || raw.containsKey("slug")) put("slug", JsonPrimitive(ForumRules.slug(raw.optionalString("slug").orEmpty())))
        if (raw.containsKey("descripcion")) {
            put("descripcion", raw.optionalString("descripcion")?.controlSanitized(ForumRules.CHANNEL_DESC_MAX)?.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        }
        if (raw.containsKey("activa")) put("activa", JsonPrimitive(raw.requiredBool("activa")))
        if (raw.containsKey("soloLectura")) put("solo_lectura", JsonPrimitive(raw.requiredBool("soloLectura")))
        if (raw.containsKey("posicion")) {
            val p = raw.requiredInt("posicion")
            if (p < 1 || p > 10000) controlReject("FORO_CHANNEL_POSITION_INVALID", "La posición va de 1 a 10000.")
            put("posicion", JsonPrimitive(p))
        }
        if (raw.containsKey("especialidadId")) {
            val e = raw.optionalString("especialidadId")?.trim()?.ifEmpty { null }
            if (e != null && !e.isUuid()) controlBadBody()
            put("especialidad_id", e?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

internal fun AdminControlV1Gateway.createChannel(fields: JsonObject): AdminForumChannelDto {
    requireActor()
    val created = send("POST", "/rest/v1/categorias_oposired", JsonArray(listOf(fields)).toString(), prefer = "return=representation")
    val ch = (json.parseToJsonElement(created) as? JsonArray)?.firstOrNull()?.jsonObject?.toChannel()
        ?: throw IllegalStateException("categorias_oposired: respuesta sin fila")
    audit("foro_canal_creado", "categorias_oposired", ch.id, despues = fields)
    return ch
}

internal fun AdminControlV1Gateway.updateChannel(id: String, fields: JsonObject): AdminForumChannelDto? {
    requireActor()
    if (fields.isEmpty()) return listChannels().firstOrNull { it.id == id }
    val updated = send("PATCH", "/rest/v1/categorias_oposired?id=eq.$id", fields.toString(), prefer = "return=representation")
    val ch = (json.parseToJsonElement(updated) as? JsonArray)?.firstOrNull()?.jsonObject?.toChannel() ?: return null
    audit("foro_canal_modificado", "categorias_oposired", id, despues = fields)
    return ch
}

// ---------------------------------------------------------------- bloqueos y restricciones (via funciones de servidor)

internal fun AdminControlV1Gateway.listBlockPatterns(): List<AdminForumBlockPatternDto> {
    val body = rpcOrNotProvisioned("admin_foro_bloqueos_list", buildJsonObject {})
    return (json.parseToJsonElement(body) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() ?: (it as? JsonObject)?.get("patron").contentOrNullSafe() }
        .map { AdminForumBlockPatternDto(it.controlSanitized(ForumRules.PATTERN_MAX)) }
}

internal fun AdminControlV1Gateway.addBlockPattern(pattern: String) {
    val actor = requireActor()
    rpcOrNotProvisioned("admin_foro_bloqueos_add", buildJsonObject {
        put("p_actor", JsonPrimitive(actor))
        put("p_patron", JsonPrimitive(pattern))
    })
}

internal fun AdminControlV1Gateway.removeBlockPattern(pattern: String) {
    val actor = requireActor()
    rpcOrNotProvisioned("admin_foro_bloqueos_remove", buildJsonObject {
        put("p_actor", JsonPrimitive(actor))
        put("p_patron", JsonPrimitive(pattern))
    })
}

internal fun AdminControlV1Gateway.listRestrictions(): List<AdminForumRestrictionDto> {
    val body = rpcOrNotProvisioned("admin_foro_restricciones_list", buildJsonObject {})
    return (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        val uid = r["usuarioId"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return@mapNotNull null
        AdminForumRestrictionDto(
            usuarioId = uid,
            handle = r["handle"].contentOrNullSafe()?.controlSanitized(40),
            hasta = r["hasta"].contentOrNullSafe(),
            motivo = r["motivo"].contentOrNullSafe()?.controlSanitized(ForumRules.MOTIVO_MAX),
            creadoEn = r["creadoEn"].contentOrNullSafe(),
        )
    }
}

internal fun AdminControlV1Gateway.liftRestriction(usuarioId: String) {
    val actor = requireActor()
    rpcOrNotProvisioned("admin_foro_restriccion_levantar", buildJsonObject {
        put("p_actor", JsonPrimitive(actor))
        put("p_usuario", JsonPrimitive(usuarioId))
    })
}

fun Route.adminControlForumRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/foro") {
        get("/cola") {
            val params = call.request.queryParameters
            val tipo = params["tipo"]?.trim().orEmpty().ifEmpty { "publicacion" }
            if (tipo !in ForumRules.TIPOS) controlReject("FORO_TYPE_INVALID", "El tipo de contenido no es válido.")
            val estado = params["estado"]?.trim().orEmpty().ifEmpty { "pendiente_moderacion" }
            val allowed = if (tipo == "publicacion") ForumRules.ESTADOS_COLA_PUBLICACION else ForumRules.ESTADOS_COLA_COMENTARIO
            if (estado !in allowed) controlReject("FORO_STATE_INVALID", "El estado de la cola no es válido.")
            val limit = ModerationQueries.limit(params["limit"])
            val cursor = ModerationQueries.cursor(params["cursor"]) { decodeForoCursor(it) }
            val page = withUpstreamIo { gateway.forumQueue(tipo, estado, limit, cursor) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(page)
        }
        post("/moderar") {
            val m = ForumRules.moderation(call.receive<JsonObject>())
            val result = withUpstreamIo { gateway.moderateForum(m) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(result)
        }
        route("/canales") {
            get {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(AdminForumChannelsResponse(withUpstreamIo { gateway.listChannels() }))
            }
            post {
                val fields = parseChannel(call.receive<JsonObject>(), creating = true)
                call.respond(HttpStatusCode.Created, withUpstreamIo { gateway.createChannel(fields) })
            }
            patch("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val fields = parseChannel(call.receive<JsonObject>(), creating = false)
                val saved = withUpstreamIo { gateway.updateChannel(id, fields) }
                    ?: throw CanonicalErrorCode.NOT_FOUND.exception("Canal no encontrado.")
                call.respond(saved)
            }
        }
        route("/bloqueos") {
            get {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(AdminForumBlockListResponse(withUpstreamIo { gateway.listBlockPatterns() }))
            }
            post {
                val body = call.receive<JsonObject>()
                body.requireOnlyKeys(setOf("patron"))
                val p = ForumRules.pattern(body.optionalString("patron").orEmpty())
                withUpstreamIo { gateway.addBlockPattern(p) }
                call.respond(HttpStatusCode.Created, AdminForumBlockPatternDto(p))
            }
            delete("/{patron}") {
                val p = ForumRules.pattern(call.requiredPath("patron"))
                withUpstreamIo { gateway.removeBlockPattern(p) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/restricciones") {
            get {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(AdminForumRestrictionsResponse(withUpstreamIo { gateway.listRestrictions() }))
            }
            delete("/{usuarioId}") {
                val uid = call.requiredPath("usuarioId").validatedUuid("usuarioId")
                withUpstreamIo { gateway.liftRestriction(uid) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
