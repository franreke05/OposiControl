package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

// =====================================================================
// D7-29 (FA14, `openapi/commerce/commerce.yaml`): `GET /api/v1/admin/market/promociones/{id}/usos`. Registro de usos de una
// promocion (`usos_promocion_usuario`), SOLO LECTURA: las filas las escribe la liquidacion de una compra, nunca el panel.
//
// - Los tres contadores son conteos exactos de la base (nunca se cuentan filas traidas a memoria).
// - `descuentoTotalCentimos` es null: sumar exige un agregado en la base y no existe ninguna funcion que lo haga; sumar
//   fila a fila no tiene cota. Null dice "no calculado"; un 0 seria mentira.
// =====================================================================

@Serializable
internal data class PromocionUsoRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("compra_id") val compraId: String? = null,
    @SerialName("descuento_centimos") val descuentoCentimos: Int? = null,
    @SerialName("genero_compra") val generoCompra: Boolean = false,
    @SerialName("renovo_despues") val renovoDespues: Boolean? = null,
    @SerialName("usado_en") val usadoEn: String,
)

@Serializable
data class AdminPromocionUsoDto(
    val usuarioId: String,
    val compraId: String? = null,
    val descuentoCentimos: Int? = null,
    val usadoEn: String,
    val generoCompra: Boolean,
    val renovoDespues: Boolean? = null,
)

@Serializable
data class AdminPromocionUsosStats(
    val usos: Int,
    val conversiones: Int,
    val renovaciones: Int,
    /** Siempre null hoy (ver cabecera del fichero). */
    val descuentoTotalCentimos: Long? = null,
)

@Serializable
data class AdminPromocionUsosPage(
    val items: List<AdminPromocionUsoDto>,
    val nextCursor: String? = null,
    val stats: AdminPromocionUsosStats,
)

internal object AdminPromotionUseQueries {
    const val COLUMNS = "id,usuario_id,compra_id,descuento_centimos,genero_compra,renovo_despues,usado_en"

    /** `limit`/cursor canonicos de administracion (1..100, defecto 50); el cursor queda ligado a la promocion. */
    fun parse(promocionId: String, params: Parameters): CursorPageRequest {
        val page = CursorPageRequest.of(
            cursor = params["cursor"],
            limit = params["limit"],
            default = CursorPageRequest.ADMIN_DEFAULT_LIMIT,
            max = CursorPageRequest.ADMIN_MAX_LIMIT,
            scope = CursorScope(sort = listOf("usado_en:desc", "id:desc"), filters = mapOf("promocionId" to promocionId)),
        )
        KeysetPosition.fromCursorKeys(page.after)
        return page
    }

    fun toDto(row: PromocionUsoRow): AdminPromocionUsoDto = AdminPromocionUsoDto(
        usuarioId = row.usuarioId,
        compraId = row.compraId,
        descuentoCentimos = row.descuentoCentimos,
        usadoEn = runCatching { OffsetDateTime.parse(row.usadoEn).toInstant().toString() }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() },
        generoCompra = row.generoCompra,
        renovoDespues = row.renovoDespues,
    )
}
