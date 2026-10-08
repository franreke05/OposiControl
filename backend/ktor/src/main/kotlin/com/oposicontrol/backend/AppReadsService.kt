package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Servicio de las lecturas de consumidor de FA15 (SOLO LECTURAS):
//   D7-05 `GET /api/v1/app/me/billing-history`   compras + suscripciones propias, una lista por cursor
//   D5-01 `GET /api/v1/app/oposired/categorias`  catalogo acotado de categorias activas
//   D5-16 `GET /api/v1/app/opositester/me`       contadores propios de OposiTester
// Reglas aqui; la ruta solo hace HTTP y la pasarela solo lee. Cable: `openapi/billing/billing.yaml` y
// `openapi/community/community.yaml`.
// =====================================================================

// ---------- cable ----------

/** `BillingKind` del contrato. */
internal enum class BillingKind(val wire: String) {
    PURCHASE("purchase"),
    SUBSCRIPTION("subscription"),
    ;

    companion object {
        fun fromWireOrNull(raw: String): BillingKind? = entries.firstOrNull { it.wire == raw }
    }
}

@Serializable
internal data class BillingEntry(
    val kind: String,
    val id: String,
    val productoCodigo: String,
    val tipo: String,
    val estado: String,
    val precioCentimos: Int? = null,
    val moneda: String? = null,
    val occurredAt: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
internal data class OposiRedCategoria(
    val id: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val posicion: Int,
)

@Serializable
internal data class OposiRedCategorias(val items: List<OposiRedCategoria>)

@Serializable
internal data class OposiTesterEstado(
    val reportesEnviados: Int,
    val reportesValidados: Int,
    val ticketsGanados: Int,
    /** DEC-028 abierta: SIEMPRE `null` (los niveles de OposiTester no estan definidos). */
    val nivel: String? = null,
)

// ---------- peticion ya validada ----------

internal data class BillingHistoryQuery(val kind: BillingKind?, val page: CursorPageRequest)

/**
 * D7-05. `kind` desconocido = 400 INVALID_FILTER; `limit` fuera de rango = 400 VALIDATION; cursor ajeno o corrupto =
 * 400 INVALID_CURSOR. El cursor lleva la huella de orden + `kind`; el usuario NO va en el cursor: sale del JWT.
 */
internal fun parseBillingHistoryQuery(params: Parameters): BillingHistoryQuery {
    val kind = params["kind"]?.let { raw ->
        BillingKind.fromWireOrNull(raw.trim())
            ?: throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = listOf(ErrorIssue("kind", CanonicalIssue.INVALID_FORMAT))))
    }
    val page = CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = CursorPageRequest.APP_DEFAULT_LIMIT,
        max = CursorPageRequest.APP_MAX_LIMIT,
        scope = CursorScope(sort = listOf("occurredAt desc", "id desc"), filters = mapOf("kind" to kind?.wire)),
    )
    return BillingHistoryQuery(kind, page)
}

// ---------- servicio ----------

internal class AppReadsService(private val gateway: AppReadsGateway) {

    /** Una entrada ya validada junto a su clave de orden (instante real + id) y la clave textual que viaja en el cursor. */
    private class Keyed(val entry: BillingEntry, val at: Instant, val rawAt: String)

    // ----- D7-05 -----
    /**
     * Dos tablas, UNA lista: de cada tabla incluida se piden `limit + 1` filas tras el mismo cursor `(instante, id)` y se
     * mezclan por `(instante DESC, id DESC)`. Cada tabla ya viene en ese orden, asi que las primeras `limit + 1` de la mezcla
     * son las primeras `limit + 1` de la union: no se salta ni se repite ninguna fila entre paginas.
     */
    suspend fun billingHistory(userId: CanonicalUserId, query: BillingHistoryQuery): CursorPage<BillingEntry> = withUpstreamIo {
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val fetch = query.page.fetchLimit
        val uid = userId.value
        val purchases = if (query.kind != BillingKind.SUBSCRIPTION) gateway.purchases(uid, after, fetch).map { it.keyed() } else emptyList()
        val subscriptions = if (query.kind != BillingKind.PURCHASE) gateway.subscriptions(uid, after, fetch).map { it.keyed() } else emptyList()
        val merged = (purchases + subscriptions)
            .sortedWith(compareByDescending<Keyed> { it.at }.thenByDescending { it.entry.id })
            .take(fetch)
        val page = query.page.page(merged) { KeysetPosition(it.rawAt, it.entry.id).toKeys() }
        CursorPage(items = page.items.map { it.entry }, nextCursor = page.nextCursor)
    }

    private fun PurchaseRow.keyed(): Keyed {
        if (!id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val at = instantOrFail(creadoEn)
        return Keyed(
            BillingEntry(
                kind = BillingKind.PURCHASE.wire,
                id = id.lowercase(),
                productoCodigo = productoCodigo,
                tipo = tipoProducto,
                estado = estado,
                precioCentimos = precioCentimos?.also { if (it < 0) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() },
                moneda = moneda,
                occurredAt = at.toString(),
            ),
            at,
            creadoEn,
        )
    }

    private fun SubscriptionRow.keyed(): Keyed {
        if (!id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val at = instantOrFail(inicioEn)
        return Keyed(
            BillingEntry(
                kind = BillingKind.SUBSCRIPTION.wire,
                id = id.lowercase(),
                productoCodigo = productoCodigo,
                tipo = SUBSCRIPTION_TIPO,
                estado = estado,
                occurredAt = at.toString(),
                startsAt = at.toString(),
                endsAt = finEn?.let { instantOrFail(it).toString() },
            ),
            at,
            inicioEn,
        )
    }

    // ----- D5-01 -----
    /** Catalogo acotado: como mucho [CATEGORIES_MAX] categorias activas (tope de servidor, sin paginacion). */
    suspend fun forumCategories(): OposiRedCategorias = withUpstreamIo {
        val rows = gateway.forumCategories(CATEGORIES_MAX)
        OposiRedCategorias(
            rows.take(CATEGORIES_MAX).map { row ->
                if (!row.id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                OposiRedCategoria(row.id.lowercase(), row.nombre, row.slug, row.descripcion, row.posicion)
            },
        )
    }

    // ----- D5-16 -----
    /** Contadores del propio usuario; sin fila = ceros. `nivel` SIEMPRE null (DEC-028). */
    suspend fun opositester(userId: CanonicalUserId): OposiTesterEstado = withUpstreamIo {
        val row = gateway.opositester(userId.value)
        OposiTesterEstado(
            reportesEnviados = (row?.reportesEnviados ?: 0).coerceAtLeast(0),
            reportesValidados = (row?.reportesValidados ?: 0).coerceAtLeast(0),
            ticketsGanados = (row?.ticketsGanados ?: 0).coerceAtLeast(0),
            nivel = null,
        )
    }

    private fun instantOrFail(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        const val CATEGORIES_MAX = 50
        const val SUBSCRIPTION_TIPO = "suscripcion"
    }
}
