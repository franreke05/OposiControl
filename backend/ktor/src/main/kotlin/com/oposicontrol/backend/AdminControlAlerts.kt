package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Alertas de SOLO LECTURA (V1): ventanas de ranking demasiado rapidas y desvios de contadores del foro.
 *
 * ### Ventanas rapidas (`ranking_ventanas_rapidas`, migracion 20261022101700)
 * Usuarios con una ventana COMPLETA de primeras respuestas cuya mediana de tiempo esta por debajo del minimo configurable
 * (`ranking_min_ms_respuesta`). No entran en el ranking. Es un FRENO contra cuentas automaticas, no una prueba de que sean
 * humanas ni de que sean trampa (hay quien responde rapido de verdad): por eso Control solo LISTA. No hay accion que
 * sancione desde esta pantalla; revisar es una decision humana con otras fuentes. Aceptado como residual por E1/E2
 * (E1-V1-OPEN-DECISIONS.md, "Accepted residual E2-V1-RK02").
 *
 * ### Contadores del foro
 * `app_job_foro` deja una fila `foro_contadores_desviados` en `auditoria_log` cuando los contadores de las publicaciones no
 * cuadran con las filas. Aqui se enseñan las ultimas.
 */
@Serializable
data class AdminFastWindowDto(
    val usuarioId: String,
    val handle: String? = null,
    val ligaNombre: String? = null,
    val ventanaCalculo: Int? = null,
    val medianaMs: Long? = null,
    val actualizadoEn: String? = null,
)

@Serializable
data class AdminFastWindowPageDto(
    val data: List<AdminFastWindowDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    /** Umbral vigente (`ranking_min_ms_respuesta`); `null` = la fila de configuracion no existe. */
    val umbralMs: Int? = null,
)

@Serializable
data class AdminForumDriftDto(val id: String, val creadoEn: String? = null, val filas: Int? = null)

@Serializable
data class AdminForumDriftResponse(val data: List<AdminForumDriftDto>)

internal fun AdminControlV1Gateway.fastWindows(limit: Int, offset: Int): AdminFastWindowPageDto {
    val page = transport.getPage(
        "/rest/v1/ranking_ventanas_rapidas?select=usuario_id,liga_nivel_id,ventana_calculo,ventana_tiempo_mediano_ms,actualizado_en" +
            "&order=ventana_tiempo_mediano_ms.asc,usuario_id.asc&limit=$limit&offset=$offset",
    )
    val rows = (json.parseToJsonElement(page.body) as? JsonArray).orEmpty().map { it.jsonObject }
    val userIds = rows.mapNotNull { it["usuario_id"].contentOrNullSafe()?.takeIf(String::isUuid) }.distinct()
    val handles = if (userIds.isEmpty()) emptyMap() else (json.parseToJsonElement(
        get("/rest/v1/perfiles_usuario?select=usuario_id,username&usuario_id=in.(${userIds.joinToString(",")})"),
    ) as? JsonArray).orEmpty().associate { it.jsonObject["usuario_id"].contentOrNullSafe().orEmpty() to it.jsonObject["username"].contentOrNullSafe() }
    val ligaIds = rows.mapNotNull { it["liga_nivel_id"].contentOrNullSafe()?.takeIf(String::isUuid) }.distinct()
    val ligas = if (ligaIds.isEmpty()) emptyMap() else (json.parseToJsonElement(
        get("/rest/v1/ligas_nivel?select=id,nombre&id=in.(${ligaIds.joinToString(",")})"),
    ) as? JsonArray).orEmpty().associate { it.jsonObject["id"].contentOrNullSafe().orEmpty() to it.jsonObject["nombre"].contentOrNullSafe() }
    val umbral = try {
        (json.parseToJsonElement(get("/rest/v1/configuracion_v1?select=valor_integer&clave=eq.ranking_min_ms_respuesta&limit=1")) as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("valor_integer").contentOrNullSafe()?.toIntOrNull()
    } catch (_: IllegalStateException) {
        null
    }
    return AdminFastWindowPageDto(
        data = rows.mapNotNull { r ->
            val uid = r["usuario_id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return@mapNotNull null
            AdminFastWindowDto(
                usuarioId = uid,
                handle = handles[uid]?.controlSanitized(40),
                ligaNombre = r["liga_nivel_id"].contentOrNullSafe()?.let { ligas[it] }?.controlSanitized(60),
                ventanaCalculo = r["ventana_calculo"].intOr(0).takeIf { r["ventana_calculo"].contentOrNullSafe() != null },
                medianaMs = r["ventana_tiempo_mediano_ms"].contentOrNullSafe()?.toDoubleOrNull()?.toLong(),
                actualizadoEn = r["actualizado_en"].contentOrNullSafe(),
            )
        },
        total = page.total,
        limit = limit,
        offset = offset,
        umbralMs = umbral,
    )
}

internal fun AdminControlV1Gateway.forumDrift(limit: Int): List<AdminForumDriftDto> =
    (json.parseToJsonElement(
        get("/rest/v1/auditoria_log?select=id,creado_en,despues&accion=eq.foro_contadores_desviados&order=creado_en.desc,id.desc&limit=$limit"),
    ) as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e.jsonObject
        AdminForumDriftDto(
            id = r["id"].contentOrNullSafe() ?: return@mapNotNull null,
            creadoEn = r["creado_en"].contentOrNullSafe(),
            filas = (r["despues"] as? JsonObject)?.get("filas").contentOrNullSafe()?.toIntOrNull(),
        )
    }

fun Route.adminControlAlertsRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/alertas") {
        get("/ranking-ventanas-rapidas") {
            val params = call.request.queryParameters
            val limit = ModerationQueries.limit(params["limit"], 25, 100)
            val offset = params["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: throw CanonicalErrorCode.VALIDATION.exception()
            } ?: 0
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(withUpstreamIo { gateway.fastWindows(limit, offset) })
        }
        get("/foro-contadores") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(AdminForumDriftResponse(withUpstreamIo { gateway.forumDrift(20) }))
        }
    }
}
