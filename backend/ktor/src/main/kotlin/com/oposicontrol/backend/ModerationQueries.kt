package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

// =====================================================================
// Lecturas de moderacion (FA12, `openapi/moderation/moderation.yaml`): D5-20, D5-21, D5-22 (hilos de OposiRed),
// D5-25, D5-26 (reportes de contenido) y D5-28, D5-29 (feedback de preguntas).
//
// Los siete contratos viven en la MISMA ruta que ya consume el cliente OposiControl, asi que el cable solo CRECE
// (campos y parametros nuevos, nunca uno renombrado o retirado). Aqui solo se sanea la peticion ANTES de tocar la base:
// todo literal del cliente que acaba en un filtro PostgREST es un enum cerrado, un uuid o un cursor ya validado.
// Las consultas y el ensamblado de DTO siguen en [AdminV1Gateway] (un unico servicio para la ruta canonica y sus alias
// legacy `/api/v1/admin/moderacion` y `/api/v1/admin/moderacion/reportes`).
// =====================================================================

/** Filtros ya validados de D5-25. [after] es la posicion keyset (`creado_en ASC, id ASC`) o null en la primera pagina. */
internal data class ReportesQuery(
    val estado: String?,
    val tipoEntidad: String?,
    val limit: Int,
    val offset: Int,
    val after: KeysetPosition?,
    val scope: CursorScope,
) {
    /**
     * Cursor de la pagina siguiente. La lista NO pide `limit + 1` filas (la URL de la primera pagina es la que ya tenia el
     * panel): una pagina LLENA anuncia siguiente, asi que la ultima puede llegar vacia. Una pagina corta es la ultima.
     */
    fun nextCursor(page: List<AdminReporteDto>): String? {
        val last = page.lastOrNull()?.takeIf { page.size >= limit } ?: return null
        if (last.creadoEn.isBlank() || !last.id.isUuid()) return null
        return PageCursorCodec.encode(KeysetPosition(last.creadoEn, last.id.lowercase()).toKeys(), scope.fingerprint())
    }
}

internal object ModerationQueries {
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50
    const val REPORTES_DEFAULT_LIMIT = 200
    const val REPORTES_MAX_LIMIT = 200

    /** Acciones de un reporte que devuelve D5-26 (las mas recientes primero). */
    const val REPORTE_ACCIONES_MAX = 50

    const val NO_STORE = "no-store"

    val HILO_MODOS: Set<String> = setOf("recientes", "reportados")

    /** CHECK `reportes_contenido_tipo_entidad_check` de produccion, tal cual. */
    val REPORTE_TIPOS_ENTIDAD: Set<String> =
        setOf("pregunta", "recurso_pregunta", "opositeca_item", "publicacion_oposired", "comentario_oposired", "usuario")

    /**
     * `tipo_entidad` de un reporte sobre un hilo / sobre una respuesta. El primero de cada lista es el UNICO valor que el
     * CHECK de produccion admite; los demas son los alias que el codigo anterior buscaba (ninguna fila puede tenerlos hoy)
     * y se conservan solo para no perder filas de un entorno con datos antiguos.
     */
    val REPORTE_TIPOS_HILO: List<String> = listOf("publicacion_oposired", "hilo", "publicacion")
    val REPORTE_TIPOS_COMENTARIO: List<String> = listOf("comentario_oposired", "comentario", "respuesta")

    private val wire = Json { explicitNulls = false }

    private fun invalidFilter(field: String): Nothing =
        throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = listOf(ErrorIssue(field, CanonicalIssue.INVALID_FORMAT))))

    /** `limit` ausente -> [default]; no entero o < 1 -> 400 VALIDATION (OUT_OF_RANGE); > [max] -> recortado a [max]. */
    fun limit(raw: String?, default: Int = DEFAULT_LIMIT, max: Int = MAX_LIMIT): Int = when {
        raw.isNullOrBlank() -> default
        else -> raw.trim().toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(max)
            ?: throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE))
    }

    /**
     * Cursor opaco de los listados de hilos, respuestas y feedback (el formato que ya emitian: par `creado_en` + id). Se valida
     * AQUI, antes de cualquier lectura, y un cursor ilegible es 400 INVALID_CURSOR. Devuelve el cursor tal cual o null.
     */
    fun cursor(raw: String?, decode: (String) -> Unit): String? {
        val cursor = raw?.takeIf { it.isNotBlank() } ?: return null
        try {
            decode(cursor)
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.INVALID_CURSOR.exception()
        }
        return cursor
    }

    /**
     * D5-20: `modo=recientes|reportados`. `reportados=true` es el parametro que envia el cliente OC actual y equivale a
     * `modo=reportados`; si llegan los dos manda `modo`. Devuelve true cuando solo se piden los hilos reportados.
     */
    fun reportedOnly(params: Parameters): Boolean {
        val modo = params["modo"]?.trim()?.takeIf { it.isNotEmpty() }
        if (modo != null) {
            if (modo !in HILO_MODOS) invalidFilter("modo")
            return modo == "reportados"
        }
        return params["reportados"] == "true"
    }

    /** `categoriaId` opcional: un uuid o 400 INVALID_FILTER. */
    fun categoriaId(params: Parameters): String? =
        params["categoriaId"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!it.isUuid()) invalidFilter("categoriaId") }?.lowercase()

    /**
     * D5-25. `estado` y `limit`/`offset` conservan las reglas que ya tenia la ruta (un `estado` desconocido o un numero no
     * entero es 400; `limit` se recorta a 1..200 y `offset` a >= 0). Nuevos: `tipoEntidad` (enum del CHECK) y `cursor`.
     * `cursor` y un `offset` distinto de 0 a la vez son 400: son dos formas de paginar que no se combinan.
     */
    fun reportes(params: Parameters): ReportesQuery {
        val estado = (params["estado"]?.takeIf { it.isNotBlank() } ?: "pendiente")
        require(estado in REPORTE_LIST_ESTADOS) { "estado no valido." }
        val tipoEntidad = params["tipoEntidad"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in REPORTE_TIPOS_ENTIDAD) invalidFilter("tipoEntidad") }
        val limit = params["limit"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("limit debe ser un entero.") }
            ?.coerceIn(1, REPORTES_MAX_LIMIT) ?: REPORTES_DEFAULT_LIMIT
        val offset = params["offset"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("offset debe ser un entero.") }
            ?.coerceAtLeast(0) ?: 0
        val scope = CursorScope(
            sort = listOf("creado_en:asc", "id:asc"),
            filters = mapOf("estado" to estado, "tipoEntidad" to tipoEntidad),
        )
        val after = params["cursor"]?.takeIf { it.isNotEmpty() }
            ?.let { KeysetPosition.fromCursorKeys(PageCursorCodec.decode(it, scope.fingerprint())) }
        if (after != null && offset != 0) throw validationError(ErrorIssue("offset", CanonicalIssue.OUT_OF_RANGE))
        return ReportesQuery(estado, tipoEntidad, limit, offset, after, scope)
    }

    /**
     * D5-26: el contrato canonico es `{data: reporte}`; el cliente OC actual lee el reporte DESNUDO. Hasta que migre se emiten
     * las dos formas (mismo patron que D1-23): los campos del reporte arriba y el mismo objeto bajo `data`.
     */
    fun reporteDetail(reporte: AdminReporteDto): JsonObject {
        val bare = wire.encodeToJsonElement(reporte).jsonObject
        return JsonObject(bare + ("data" to bare))
    }
}
