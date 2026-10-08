package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

// =====================================================================
// D7-27 (FA12, `openapi/commerce/commerce.yaml`): `GET /api/v1/admin/suscripciones`. Lectura de administracion de
// `public.suscripciones`, SOLO LECTURA: el estado de una suscripcion lo escribe el proveedor de pagos, nunca el panel.
//
// - Solo salen las columnas del contrato. `identificador_externo` y `payload_validacion` (datos del proveedor) NO se
//   seleccionan, asi que no pueden acabar en una respuesta.
// - Todo literal del cliente que llega a un filtro PostgREST es un valor de un CHECK cerrado, un uuid o un cursor validado.
// - `canceladaEn` es null: `suscripciones` no guarda el instante de cancelacion (no hay columna en produccion).
// =====================================================================

/** Fila de `suscripciones` con las columnas del contrato, tal cual las devuelve PostgREST. */
@Serializable
internal data class SuscripcionAdminRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val proveedor: String,
    @SerialName("producto_codigo") val productoCodigo: String,
    val estado: String,
    @SerialName("inicio_en") val inicioEn: String,
    @SerialName("fin_en") val finEn: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
)

@Serializable
data class AdminSuscripcionDto(
    val id: String,
    val usuarioId: String,
    /** Nombre visible del usuario; null si no tiene perfil o nombre (nunca el uuid). */
    val usuarioNombre: String? = null,
    val proveedor: String,
    val productoCodigo: String,
    val estado: String,
    val inicioEn: String,
    val finEn: String? = null,
    val renovacionEn: String? = null,
    /** Siempre null hoy: no existe la columna en produccion. */
    val canceladaEn: String? = null,
)

@Serializable
data class AdminSuscripcionesPage(val items: List<AdminSuscripcionDto>, val nextCursor: String? = null)

internal data class AdminSuscripcionesQuery(
    val estado: String?,
    val proveedor: String?,
    val usuarioId: String?,
    val page: CursorPageRequest,
) {
    val after: KeysetPosition? get() = KeysetPosition.fromCursorKeys(page.after)
}

internal object AdminSubscriptionQueries {
    const val COLUMNS = "id,usuario_id,proveedor,producto_codigo,estado,inicio_en,fin_en,renovacion_en"

    /** CHECK `suscripciones_estado_check` de produccion. */
    val ESTADOS: Set<String> = setOf("activa", "pausada", "cancelada", "caducada", "periodo_prueba")

    /** CHECK `suscripciones_proveedor_check` de produccion. */
    val PROVEEDORES: Set<String> = setOf("google_play", "app_store", "stripe", "manual")

    private fun invalidFilter(field: String): Nothing =
        throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = listOf(ErrorIssue(field, CanonicalIssue.INVALID_FORMAT))))

    /** Filtros de D7-27: cualquier valor fuera de su enum o un `usuarioId` que no es uuid es 400 INVALID_FILTER. */
    fun parse(params: Parameters): AdminSuscripcionesQuery {
        val estado = params["estado"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in ESTADOS) invalidFilter("estado") }
        val proveedor = params["proveedor"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in PROVEEDORES) invalidFilter("proveedor") }
        val usuarioId = params["usuarioId"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!it.isUuid()) invalidFilter("usuarioId") }?.lowercase()
        val page = CursorPageRequest.of(
            cursor = params["cursor"],
            limit = params["limit"],
            default = CursorPageRequest.ADMIN_DEFAULT_LIMIT,
            max = CursorPageRequest.ADMIN_MAX_LIMIT,
            scope = CursorScope(
                sort = listOf("inicio_en:desc", "id:desc"),
                filters = mapOf("estado" to estado, "proveedor" to proveedor, "usuarioId" to usuarioId),
            ),
        )
        // Valida la forma de las claves ANTES de cualquier lectura (un instante y un uuid, o 400 INVALID_CURSOR).
        KeysetPosition.fromCursorKeys(page.after)
        return AdminSuscripcionesQuery(estado, proveedor, usuarioId, page)
    }

    /** Instante RFC 3339 en UTC. Una fecha ilegible del upstream es un fallo de contrato del upstream, no un dato. */
    private fun instant(raw: String): String =
        runCatching { OffsetDateTime.parse(raw).toInstant().toString() }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }

    fun toDto(row: SuscripcionAdminRow, names: Map<String, String>): AdminSuscripcionDto = AdminSuscripcionDto(
        id = row.id,
        usuarioId = row.usuarioId,
        usuarioNombre = names[row.usuarioId]?.takeIf { it.isNotBlank() },
        proveedor = row.proveedor,
        productoCodigo = row.productoCodigo,
        estado = row.estado,
        inicioEn = instant(row.inicioEn),
        finEn = row.finEn?.let(::instant),
        renovacionEn = row.renovacionEn?.let(::instant),
    )
}
