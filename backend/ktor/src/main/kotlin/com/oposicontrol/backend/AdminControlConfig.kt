package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Editor de la configuracion numerica V1 (`configuracion_v1`): invitaciones (`referido_*`), foro (`foro_*`) y el minimo
 * de tiempo de respuesta del ranking.
 *
 * ### Quien manda
 * La base: `trg_configuracion_v1_bounds` rechaza una clave desconocida (`CONFIG_KEY_UNKNOWN`) o un valor fuera de rango
 * (`CONFIG_VALUE_OUT_OF_BOUNDS`) y `trg_configuracion_v1_audit` deja la fila antes/despues. Esta lista ESPEJA los
 * rangos de `private.config_v1_range` (migracion 20261022101500) solo para decirle al operador el rango y rechazar antes
 * de salir a la red; si la base cambia un rango, la base sigue mandando y el espejo solo se queda corto o largo SIN
 * romper nada. Una clave que no esta aqui no se puede editar desde Control aunque la base la admita.
 *
 * ### Actor
 * `actualizado_por` se rellena con el `sub` del JWT: es lo que el trigger de auditoria de la base usa como autor.
 */
internal object ControlConfigV1 {
    data class Spec(val clave: String, val grupo: String, val etiqueta: String, val min: Int, val max: Int, val ayuda: String? = null)

    val SPECS: List<Spec> = listOf(
        // ---- Invitaciones (Invita y gana)
        Spec("referido_activo", "referido", "Programa activo", 0, 1, "0 = apagado (nadie recibe recompensa), 1 = encendido."),
        Spec("referido_ot_invitador", "referido", "OT para quien invita", 0, 1000),
        Spec("referido_ot_invitado", "referido", "OT para quien es invitado", 0, 1000),
        Spec("referido_min_respuestas", "referido", "Respuestas mínimas del invitado", 1, 1000),
        Spec("referido_min_segundos", "referido", "Segundos mínimos de intento", 0, 3600),
        Spec("referido_min_horas", "referido", "Horas desde la verificación", 0, 720),
        Spec("referido_retener_horas", "referido", "Horas de retención antes de pagar", 0, 720),
        Spec("referido_tope_diario", "referido", "Tope diario por invitador", 0, 1000),
        Spec("referido_tope_total", "referido", "Tope total por invitador", 0, 100000),
        Spec("referido_max_intentos_pago", "referido", "Intentos de pago antes de error", 1, 50),
        Spec("referido_intentos_hora", "referido", "Altas con código por hora", 1, 1000),
        Spec("referido_ip_rafaga", "referido", "Altas por IP y hora (marca de ráfaga)", 1, 100000),
        // ---- Foro / Red
        Spec("foro_activo", "foro", "Foro activo", 0, 1, "0 = el foro no admite escritura."),
        Spec("foro_edad_min_horas", "foro", "Antigüedad mínima de cuenta para votar (horas)", 0, 720),
        Spec("foro_posts_hora", "foro", "Publicaciones por hora", 1, 1000),
        Spec("foro_posts_dia", "foro", "Publicaciones por día", 1, 100000),
        Spec("foro_comentarios_hora", "foro", "Comentarios por hora", 1, 1000),
        Spec("foro_reportes_dia", "foro", "Reportes por día", 1, 100000),
        Spec("foro_votos_min", "foro", "Votos por minuto", 1, 10000),
        Spec("foro_votos_dia", "foro", "Votos por día", 1, 100000),
        Spec("foro_premoderacion_posts", "foro", "Publicaciones aprobadas antes de dejar de moderar", 0, 100),
        Spec("foro_titulo_max", "foro", "Longitud máxima del título", 10, 300),
        Spec("foro_cuerpo_max", "foro", "Longitud máxima del texto", 10, 20000),
        Spec("foro_comentario_max", "foro", "Longitud máxima del comentario", 10, 5000),
        Spec("foro_dup_minutos", "foro", "Ventana de duplicado del mismo autor (min)", 0, 10080),
        Spec("foro_dup_cruzado_minutos", "foro", "Ventana de duplicado entre autores (min)", 0, 10080),
        Spec("foro_autoocultar_k", "foro", "Reportes de spam para ocultar solo (0 = apagado)", 0, 1000),
        Spec("foro_imagenes_hora", "foro", "Imágenes por hora", 1, 1000),
        Spec("foro_imagen_urls_hora", "foro", "Enlaces de imagen por hora", 1, 100000),
        // ---- Ranking (alerta de ventanas rápidas)
        Spec("ranking_min_ms_respuesta", "ranking", "Mediana mínima de respuesta (ms; 0 = sin control)", 0, 60000),
    )

    val GROUPS: Set<String> = SPECS.map { it.grupo }.toSet()

    fun spec(clave: String): Spec? = SPECS.firstOrNull { it.clave == clave }

    fun forGroup(grupo: String): List<Spec> = SPECS.filter { it.grupo == grupo }

    fun validate(clave: String, valor: Int): Spec {
        val spec = spec(clave) ?: controlReject("CONFIG_KEY_UNKNOWN", "Esa clave de configuración no se puede editar desde Control.")
        if (valor < spec.min || valor > spec.max) {
            controlReject("CONFIG_VALUE_OUT_OF_BOUNDS", "El valor tiene que estar entre ${spec.min} y ${spec.max}.")
        }
        return spec
    }
}

@Serializable
data class AdminConfigEntryDto(
    val clave: String,
    val grupo: String,
    val etiqueta: String,
    val ayuda: String? = null,
    val min: Int,
    val max: Int,
    /** `null` = la fila no existe todavía en la base (la migración no la sembró): se dice, no se inventa un 0. */
    val valor: Int? = null,
    val actualizadoEn: String? = null,
)

@Serializable
data class AdminConfigResponse(val data: List<AdminConfigEntryDto>)

internal fun AdminControlV1Gateway.readConfig(grupo: String): List<AdminConfigEntryDto> {
    val specs = ControlConfigV1.forGroup(grupo)
    val inList = specs.joinToString(",") { it.clave }
    val rows = (json.parseToJsonElement(
        get("/rest/v1/configuracion_v1?select=clave,valor_integer,actualizado_en&clave=in.($inList)"),
    ) as? JsonArray).orEmpty().map { it.jsonObject }.associateBy { it["clave"].contentOrNullSafe() }
    return specs.map { spec ->
        val row = rows[spec.clave]
        AdminConfigEntryDto(
            clave = spec.clave,
            grupo = spec.grupo,
            etiqueta = spec.etiqueta,
            ayuda = spec.ayuda,
            min = spec.min,
            max = spec.max,
            valor = row?.get("valor_integer").contentOrNullSafe()?.toIntOrNull(),
            actualizadoEn = row?.get("actualizado_en").contentOrNullSafe(),
        )
    }
}

/** Cambia UN valor. 404 si la fila no existe (no se siembran claves desde el panel). */
internal fun AdminControlV1Gateway.writeConfig(clave: String, valor: Int): AdminConfigEntryDto? {
    val actor = requireActor()
    val spec = ControlConfigV1.validate(clave, valor)
    val updated = send(
        "PATCH",
        "/rest/v1/configuracion_v1?clave=eq.${clave.urlEncode()}",
        buildJsonObject {
            put("valor_integer", JsonPrimitive(valor))
            put("actualizado_por", JsonPrimitive(actor))
        }.toString(),
        prefer = "return=representation",
    )
    if ((json.parseToJsonElement(updated) as? JsonArray).isNullOrEmpty()) return null
    return readConfig(spec.grupo).firstOrNull { it.clave == clave }
}

fun Route.adminControlConfigRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/config-v1") {
        get {
            val grupo = call.request.queryParameters["grupo"]?.trim().orEmpty()
            if (grupo !in ControlConfigV1.GROUPS) {
                controlReject("CONFIG_GROUP_UNKNOWN", "Grupo de configuración desconocido.")
            }
            val data = withUpstreamIo { gateway.readConfig(grupo) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(AdminConfigResponse(data))
        }
        put("/{clave}") {
            val clave = call.requiredPath("clave")
            val body = call.receive<JsonObject>()
            body.requireOnlyKeys(setOf("valor"))
            val valor = body.requiredInt("valor")
            val saved = withUpstreamIo { gateway.writeConfig(clave, valor) }
                ?: throw CanonicalErrorCode.NOT_FOUND.exception("La clave de configuración no existe en este servidor.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(saved)
        }
    }
}
