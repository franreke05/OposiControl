package com.oposicontrol.backend

import io.ktor.http.Parameters
import io.micrometer.core.instrument.Counter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

// =====================================================================
// Pestana «Auditoria» del panel (2026-10-15): `auditoria_log` leida con filtros y paginacion EN SERVIDOR.
//
// - `GET /api/v1/admin/auditoria/movimientos`: una pagina `{items, nextCursor}`, `creado_en DESC, id DESC`.
// - `GET /api/v1/admin/auditoria/{id}`: UNA fila con su `antes` y su `despues`.
//
// Por que una ruta nueva y no parametros en `GET /api/v1/admin/auditoria`: esa ruta responde `{data: [...]}` y el panel ya
// instalado la lee asi. Cambiarle la forma lo romperia; anadirle filtros sin cambiarsela dejaria dos contratos en una URL.
// La antigua se queda como esta (obsoleta, ver su comentario en `AdminV1Routes.kt`).
//
// Lo que cuida este fichero:
// - La lista NO descarga `antes`/`despues` (jsonb de tamano libre): solo las seis columnas de [AdminAuditQueries.COLUMNS].
//   El trafico de salida de Supabase es una cuota limitada (incidente 2026-10-10) y la lista se abre mucho mas que una ficha.
// - `ip` y `user_agent` no se devuelven NUNCA, ni en la lista ni en la ficha: el panel no los necesita y son datos personales.
// - Todo lo que acaba interpolado en la URL de PostgREST se valida ANTES de salir (uuid canonico, patron cerrado, instante
//   reescrito por el servidor): un valor del cliente nunca llega tal cual a la consulta.
// =====================================================================

/** Fila de `auditoria_log`. `antes`/`despues` solo llegan cuando se piden ([AdminAuditQueries.DETAIL_COLUMNS]). */
@Serializable
internal data class AuditoriaMovimientoRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String? = null,
    val accion: String,
    @SerialName("tabla_afectada") val tablaAfectada: String? = null,
    @SerialName("registro_id") val registroId: String? = null,
    @SerialName("creado_en") val creadoEn: String,
    val antes: JsonElement? = null,
    val despues: JsonElement? = null,
)

/**
 * Un movimiento en la lista.
 *
 * [actorId] null = accion del sistema (el token estatico no es una persona y `registrarAuditoria` no fabrica un actor).
 * [actorNombre] null = hay actor pero no se encontro su perfil: el panel ensena el identificador, no un nombre inventado.
 */
@Serializable
data class AdminAuditMovimientoDto(
    val id: String,
    val actorId: String? = null,
    val actorNombre: String? = null,
    val accion: String,
    val tabla: String? = null,
    val registroId: String? = null,
    /** Instante tal cual lo devuelve la base (ISO-8601 con zona). La hora local la pone quien lo ensena. */
    val creadoEn: String,
)

/** La ficha de un movimiento: lo mismo que la lista, mas el estado anterior y el resultante TAL CUAL se guardaron. */
@Serializable
data class AdminAuditMovimientoDetalleDto(
    val id: String,
    val actorId: String? = null,
    val accion: String,
    val tabla: String? = null,
    val registroId: String? = null,
    val creadoEn: String,
    val antes: JsonElement? = null,
    val despues: JsonElement? = null,
)

internal fun AuditoriaMovimientoRow.toMovimientoDto(actorNombre: String?) = AdminAuditMovimientoDto(
    id = id,
    actorId = usuarioId,
    actorNombre = actorNombre,
    accion = accion,
    tabla = tablaAfectada,
    registroId = registroId,
    creadoEn = creadoEn,
)

internal fun AuditoriaMovimientoRow.toDetalleDto() = AdminAuditMovimientoDetalleDto(
    id = id,
    actorId = usuarioId,
    accion = accion,
    tabla = tablaAfectada,
    registroId = registroId,
    creadoEn = creadoEn,
    antes = antes,
    despues = despues,
)

/** Filtros ya validados. [desde] es inclusivo y [hastaExclusivo] exclusivo: `desde <= creado_en < hastaExclusivo`. */
internal data class AuditLogFilter(
    val accion: String? = null,
    val actor: String? = null,
    val tabla: String? = null,
    val desde: Instant? = null,
    val hastaExclusivo: Instant? = null,
) {
    /**
     * Los filtros como predicados de PostgREST. Solo interpola valores que ya pasaron por [AdminAuditQueries.parse]:
     * un codigo `[a-z0-9_]`, un uuid canonico o un instante que escribe `Instant.toString()` (nunca el texto del cliente).
     */
    fun toPostgrest(): String = buildString {
        accion?.let { append("&accion=eq.").append(it) }
        actor?.let { append("&usuario_id=eq.").append(it) }
        tabla?.let { append("&tabla_afectada=eq.").append(it) }
        desde?.let { append("&creado_en=gte.").append(encodeQuery(it.toString())) }
        hastaExclusivo?.let { append("&creado_en=lt.").append(encodeQuery(it.toString())) }
    }
}

internal data class AuditLogQuery(val filter: AuditLogFilter, val page: CursorPageRequest)

internal object AdminAuditQueries {
    /** Columnas de la LISTA. Sin `antes`, `despues`, `ip` ni `user_agent`, y nunca `*`. */
    const val COLUMNS = "id,usuario_id,accion,tabla_afectada,registro_id,creado_en"

    /** Columnas de la FICHA: las de la lista mas los dos jsonb. `ip` y `user_agent` siguen fuera. */
    const val DETAIL_COLUMNS = "$COLUMNS,antes,despues"

    /** Forma de un codigo de accion o de un nombre de tabla: lo unico que el backend escribe en esas columnas. */
    private val CODE = Regex("^[a-z0-9_]{1,64}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")

    /**
     * `accion`, `tabla` (codigo exacto), `actor` (uuid), `desde`, `hasta`, `limit` (1..100, defecto 50) y `cursor`.
     *
     * Un parametro ausente o en blanco no filtra. Uno presente con mala forma es 400 VALIDATION con el campo culpable, y
     * nada sale hacia Supabase. Los filtros van en la huella del cursor: un cursor de otra busqueda es 400 INVALID_CURSOR,
     * no una pagina de otra lista.
     *
     * `desde`/`hasta` aceptan un instante ISO-8601 con zona o una fecha `AAAA-MM-DD`. La fecha se lee como dia UTC (el
     * servidor no sabe en que zona esta quien pregunta): `desde` es su primer instante y `hasta` incluye el dia entero.
     * Un `hasta` con forma de instante es exclusivo. El panel manda instantes, calculados con la zona del equipo.
     */
    fun parse(params: Parameters): AuditLogQuery {
        val issues = mutableListOf<ErrorIssue>()
        fun text(name: String): String? = params[name]?.trim()?.takeIf { it.isNotEmpty() }
        fun code(name: String): String? = text(name)?.let { value ->
            if (CODE.matches(value)) value else null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) }
        }
        val accion = code("accion")
        val tabla = code("tabla")
        val actor = text("actor")?.let { value ->
            if (value.isUuid()) value.lowercase() else null.also { issues += ErrorIssue("actor", CanonicalIssue.INVALID_FORMAT) }
        }
        fun moment(name: String, wholeDay: Boolean): Instant? = text(name)?.let { value ->
            parseMoment(value, wholeDay) ?: null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) }
        }
        val desde = moment("desde", wholeDay = false)
        val hasta = moment("hasta", wholeDay = true)
        // Un rango al reves no es «sin resultados»: es una peticion mal hecha, y decirlo ahorra una consulta.
        if (desde != null && hasta != null && !desde.isBefore(hasta)) issues += ErrorIssue("hasta", CanonicalIssue.OUT_OF_RANGE)
        if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())

        val filter = AuditLogFilter(accion, actor, tabla, desde, hasta)
        val page = CursorPageRequest.of(
            cursor = params["cursor"],
            limit = params["limit"],
            default = CursorPageRequest.ADMIN_DEFAULT_LIMIT,
            max = CursorPageRequest.ADMIN_MAX_LIMIT,
            scope = CursorScope(
                sort = listOf("creado_en:desc", "id:desc"),
                filters = mapOf(
                    "accion" to accion,
                    "actor" to actor,
                    "tabla" to tabla,
                    "desde" to desde?.toString(),
                    "hasta" to hasta?.toString(),
                ),
            ),
        )
        // La forma de las claves del cursor se valida aqui, antes de tocar la base (400 INVALID_CURSOR).
        KeysetPosition.fromCursorKeys(page.after)
        return AuditLogQuery(filter, page)
    }

    /** El instante que dice [raw], o null si no es ni una fecha ni un instante validos (p. ej. `2026-02-30`). */
    private fun parseMoment(raw: String, wholeDay: Boolean): Instant? = when {
        DATE.matches(raw) -> runCatching { LocalDate.parse(raw) }.getOrNull()
            ?.let { if (wholeDay) it.plusDays(1) else it }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        INSTANT.matches(raw) -> runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Auditorias que no se pudieron escribir
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Escrituras de `auditoria_log` que fallaron. En Prometheus sale como `audit_write_failures_total`.
 *
 * Una auditoria no obligatoria que falla solo dejaba una linea de log: la operacion seguia adelante y la perdida del rastro
 * no se podia contar ni alertar. `required` separa las dos consecuencias: `false` = la accion se hizo y NO quedo registrada;
 * `true` = la peticion se rechazo por no poder registrarla. Dos valores fijos: nunca la accion ni la tabla (cardinalidad).
 */
const val AUDIT_WRITE_FAILURES_METRIC = "audit.write.failures"

private val auditWriteFailureCounters = ConcurrentHashMap<Boolean, Counter>()

internal fun recordAuditWriteFailure(required: Boolean) {
    auditWriteFailureCounters.computeIfAbsent(required) {
        Counter.builder(AUDIT_WRITE_FAILURES_METRIC)
            .description("Escrituras de auditoria_log fallidas (required=false: la accion se hizo sin dejar rastro)")
            .tag("required", it.toString())
            .register(BackendPrometheusMetrics.registry)
    }.increment()
}
