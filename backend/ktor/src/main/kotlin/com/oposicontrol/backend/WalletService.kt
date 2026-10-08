package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

// =====================================================================
// Servicio de cartera (SLICE 7, SOLO LECTURAS): D6-01 `GET /api/v1/app/wallet` y D6-02 `GET /api/v1/app/wallet/movimientos`.
// Reglas de negocio aqui; la ruta solo hace HTTP y la pasarela solo lee. Vocabulario del cable: `openapi/wallet/wallet.yaml`.
// NO hay camino de escritura: ningun metodo de esta clase muta saldo ni libro (canje D6-03 y recompensas D6-04 quedan fuera).
// =====================================================================

private val WalletLogger = LoggerFactory.getLogger("OC-KTOR-WALLET")

// ---------- vocabulario cerrado ----------

/** Tipos del CHECK `transacciones_cartera_tipo_check` (= enum `LedgerTipo` del contrato). DEC-040 solo puede ANADIR. */
internal enum class WalletTipo(val wire: String) {
    RECARGA("recarga"),
    PACK_TICKETS("pack_tickets"),
    GASTO("gasto"),
    COMPRA_EXAMEN("compra_examen"),
    COMPRA_SERVICIO("compra_servicio"),
    AJUSTE("ajuste"),
    DEVOLUCION("devolucion"),
    REGALO("regalo"),
    RETO_DIARIO("reto_diario"),
    BONUS_RACHA("bonus_racha"),
    REFERIDO("referido"),
    REPORTE_VALIDADO("reporte_validado"),
    POTENCIADOR("potenciador"),
    PROTECTOR("protector"),
    ;

    companion object {
        fun fromWireOrNull(raw: String): WalletTipo? = entries.firstOrNull { it.wire == raw }
    }
}

/** `MovimientoOrigen` del contrato. [OTROS] agrupa todo lo que no es una categoria con nombre (y NO es filtrable). */
internal enum class WalletOrigen(val wire: String) {
    RECOMPENSAS("recompensas"),
    COMPRAS("compras"),
    RACHAS("rachas"),
    RETOS("retos"),
    INVITACIONES("invitaciones"),
    OPOSITESTER("opositester"),
    OTROS("otros"),
    ;

    companion object {
        /** Los valores admitidos como filtro (`MovimientoOrigenFiltro`): todos menos [OTROS]. */
        fun filterFromWireOrNull(raw: String): WalletOrigen? = entries.firstOrNull { it != OTROS && it.wire == raw }
    }
}

/**
 * Tabla `tipo -> origen`. PENDIENTE DE CONFIRMAR POR EL LEAD: el diseno NO la define; esta es la PROPUESTA de
 * `docs/integration/api-redesign/OPENAPI_EXTENSION_PLAN.md` (seccion de pendientes), no una decision de producto registrada.
 * Esta es la UNICA fuente: el campo `origen`, el filtro `origen` y `ganancias.porOrigen` salen de aqui. Un `tipo` que la
 * base anada en el futuro (DEC-040) cae en [WalletOrigen.OTROS] hasta que se decida.
 */
internal object WalletOrigins {
    private val byTipo: Map<WalletTipo, WalletOrigen> = mapOf(
        WalletTipo.RECARGA to WalletOrigen.COMPRAS,
        WalletTipo.PACK_TICKETS to WalletOrigen.COMPRAS,
        WalletTipo.BONUS_RACHA to WalletOrigen.RACHAS,
        WalletTipo.RETO_DIARIO to WalletOrigen.RETOS,
        WalletTipo.REFERIDO to WalletOrigen.INVITACIONES,
        WalletTipo.REPORTE_VALIDADO to WalletOrigen.OPOSITESTER,
        WalletTipo.REGALO to WalletOrigen.RECOMPENSAS,
        // gasto, compra_examen, compra_servicio, ajuste, devolucion, potenciador, protector -> otros
    )

    fun of(tipo: String): WalletOrigen = WalletTipo.fromWireOrNull(tipo)?.let { byTipo[it] } ?: WalletOrigen.OTROS

    fun tiposOf(origen: WalletOrigen): Set<WalletTipo> = WalletTipo.entries.filterTo(linkedSetOf()) { of(it.wire) == origen }
}

// ---------- cable (respuestas) ----------

@Serializable
internal data class MovimientoReferencia(val tipo: String? = null, val id: String? = null, val titulo: String? = null)

@Serializable
internal data class Movimiento(
    val id: String,
    val tipo: String,
    val origen: String,
    val cantidad: Int,
    val saldoAnterior: Int,
    val saldoResultante: Int,
    val referencia: MovimientoReferencia? = null,
    val descripcion: String? = null,
    val creadoEn: String,
)

@Serializable
internal data class GananciaOrigen(val origen: String, val cantidad: Int)

@Serializable
internal data class Ganancias(val hoy: Int, val semana: Int, val mes: Int, val porOrigen: List<GananciaOrigen>)

@Serializable
internal data class Consumible(
    val activacionId: String,
    val servicioCodigo: String,
    val estado: String,
    val usosRestantes: Int? = null,
    val activaHasta: String? = null,
)

@Serializable
internal data class WalletSummary(
    val saldo: Int,
    val ganancias: Ganancias,
    val recientes: List<Movimiento>,
    val consumibles: List<Consumible>,
    /** DEC-036 abierta: SIEMPRE `null` (catalogo de recompensas sin definir). */
    val recompensas: List<JsonObject>? = null,
    val actualizadoEn: String,
)

// ---------- peticion ya validada ----------

internal data class WalletMovementsQuery(val tipo: WalletTipo?, val origen: WalletOrigen?, val page: CursorPageRequest)

/**
 * D6-02. `tipo` u `origen` desconocidos (incluido `otros`, que no es filtrable) = 400 INVALID_FILTER; `limit` fuera de rango =
 * 400 VALIDATION; cursor ajeno/corrupto = 400 INVALID_CURSOR. El cursor lleva la huella de orden + filtros. El usuario NO
 * forma parte del cursor ni de la huella: sale del JWT en cada peticion.
 */
internal fun parseWalletMovementsQuery(params: Parameters): WalletMovementsQuery {
    val issues = mutableListOf<ErrorIssue>()
    val tipo = params["tipo"]?.let { raw ->
        WalletTipo.fromWireOrNull(raw.trim()) ?: run { issues += ErrorIssue("tipo", CanonicalIssue.INVALID_FORMAT); null }
    }
    val origen = params["origen"]?.let { raw ->
        WalletOrigen.filterFromWireOrNull(raw.trim()) ?: run { issues += ErrorIssue("origen", CanonicalIssue.INVALID_FORMAT); null }
    }
    if (issues.isNotEmpty()) throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = issues))
    val page = CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = CursorPageRequest.APP_DEFAULT_LIMIT,
        max = CursorPageRequest.APP_MAX_LIMIT,
        scope = CursorScope(sort = listOf("creado_en desc", "id desc"), filters = mapOf("tipo" to tipo?.wire, "origen" to origen?.wire)),
    )
    return WalletMovementsQuery(tipo, origen, page)
}

// ---------- servicio ----------

internal class WalletService(
    private val gateway: WalletGateway,
    private val clock: () -> Instant = Instant::now,
) {
    // ----- D6-02 -----
    suspend fun movements(userId: CanonicalUserId, query: WalletMovementsQuery): CursorPage<Movimiento> = withUpstreamIo {
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val originTipos = query.origen?.let(WalletOrigins::tiposOf)
        val rows = gateway.ledgerPage(userId.value, query.tipo, originTipos, after, query.page.fetchLimit)
        // Se valida y mapea TODA fila antes de emitir cursor: una clave ilegible no puede viajar en `nextCursor`.
        val mapped = rows.map { it to it.toMovimiento() }
        val page = query.page.page(mapped) { (row, _) -> KeysetPosition(row.creadoEn, row.id.lowercase()).toKeys() }
        CursorPage(items = page.items.map { it.second }, nextCursor = page.nextCursor)
    }

    // ----- D6-01 -----
    suspend fun summary(userId: CanonicalUserId): WalletSummary = withUpstreamIo {
        val now = clock()
        val uid = userId.value
        val balance = gateway.balance(uid)?.saldoTickets?.coerceAtLeast(0) ?: 0
        WalletSummary(
            saldo = balance,
            ganancias = earnings(uid, now),
            recientes = gateway.recent(uid, RECENT_LIMIT).map { it.toMovimiento() },
            consumibles = consumables(uid, now),
            recompensas = null,
            actualizadoEn = now.toString(),
        )
    }

    /**
     * Abonos (cantidad > 0) de hoy / semana / mes en Europe/Madrid (default tecnico; la semana empieza el lunes). `porOrigen` es
     * el desglose del MES en curso (el contrato no fija el periodo: default tecnico). Se lee con keyset por paginas acotadas
     * (por debajo del tope de filas de PostgREST) y, si el mes no cabe en [EARNINGS_PAGES_MAX] paginas, falla CERRADO: una
     * suma truncada mostraria menos de lo ganado.
     */
    private fun earnings(userId: String, now: Instant): Ganancias {
        val today = now.atZone(MADRID).toLocalDate()
        val dayStart = today.atStartOfDay(MADRID).toInstant()
        val weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay(MADRID).toInstant()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(MADRID).toInstant()
        val since = minOf(dayStart, weekStart, monthStart)
        var day = 0L
        var week = 0L
        var month = 0L
        val byOrigin = linkedMapOf<WalletOrigen, Long>()
        var after: KeysetPosition? = null
        var pages = 0
        while (true) {
            val rows = gateway.creditsPage(userId, since, after, EARNINGS_PAGE_ROWS + 1)
            val take = rows.take(EARNINGS_PAGE_ROWS)
            for (row in take) {
                val at = instantOrFail(row.creadoEn)
                val amount = row.cantidad.toLong()
                if (!at.isBefore(dayStart)) day += amount
                if (!at.isBefore(weekStart)) week += amount
                if (!at.isBefore(monthStart)) {
                    month += amount
                    byOrigin.merge(WalletOrigins.of(row.tipo), amount, Long::plus)
                }
            }
            if (rows.size <= EARNINGS_PAGE_ROWS) break
            pages += 1
            if (pages >= EARNINGS_PAGES_MAX) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
            val last = take.last()
            after = KeysetPosition(last.creadoEn, last.id.lowercase())
        }
        return Ganancias(
            hoy = day.toBalance(),
            semana = week.toBalance(),
            mes = month.toBalance(),
            porOrigen = WalletOrigen.entries.mapNotNull { origen ->
                byOrigin[origen]?.takeIf { it > 0 }?.let { GananciaOrigen(origen.wire, it.toBalance()) }
            },
        )
    }

    /** Activas + usadas/caducadas de los ultimos [CONSUMABLE_RECENT_DAYS] dias; las canceladas no salen. Desbordamiento = falla cerrado. */
    private fun consumables(userId: String, now: Instant): List<Consumible> {
        val rows = gateway.activations(userId, now.minus(Duration.ofDays(CONSUMABLE_RECENT_DAYS)), CONSUMABLES_MAX)
        if (rows.size > CONSUMABLES_MAX) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return rows.filter { it.estado != "cancelada" }.map { row ->
            if (row.estado !in CONSUMABLE_STATES || !row.id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
            val until = row.activaHasta?.let(::instantOrFail)
            // Ninguna fila caduca sola: una activa con `activa_hasta` vencido se presenta como caducada (como D7-02).
            val lapsed = row.estado == "activa" && until != null && !until.isAfter(now)
            Consumible(
                activacionId = row.id.lowercase(),
                servicioCodigo = row.servicios?.codigo?.takeIf(String::isNotBlank) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(),
                estado = if (lapsed) "caducada" else row.estado,
                usosRestantes = (row.usosDisponibles - row.usosConsumidos).coerceAtLeast(0),
                activaHasta = until?.toString(),
            )
        }
    }

    /**
     * Una fila del libro al cable. `saldo_anterior` es NULLABLE en MAIN (filas antiguas) pero el contrato lo exige no nulo:
     * si falta se DERIVA como `saldo_resultante - cantidad` (el libro es append-only y `saldo_resultante >= 0`). Una fila que
     * aun asi daria un saldo negativo es un dato corrupto: falla CERRADO (502 UPSTREAM_CONTRACT_ERROR), nunca se inventa un 0.
     */
    private fun LedgerRow.toMovimiento(): Movimiento {
        if (!id.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val before: Long = saldoAnterior?.toLong() ?: (saldoResultante.toLong() - cantidad.toLong())
        if (before < 0 || before > Int.MAX_VALUE || saldoResultante < 0) {
            WalletLogger.warn("movimiento con saldos incoherentes (anterior={}, resultante={}, cantidad={})", saldoAnterior, saldoResultante, cantidad)
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        val ref = if (referenciaTipo == null && referenciaId == null) {
            null
        } else {
            MovimientoReferencia(tipo = referenciaTipo, id = referenciaId?.takeIf { it.isUuid() }?.lowercase(), titulo = null)
        }
        return Movimiento(
            id = id.lowercase(),
            tipo = tipo,
            origen = WalletOrigins.of(tipo).wire,
            cantidad = cantidad,
            saldoAnterior = before.toInt(),
            saldoResultante = saldoResultante,
            referencia = ref,
            descripcion = descripcion,
            creadoEn = instantOrFail(creadoEn).toString(),
        )
    }

    private fun instantOrFail(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun Long.toBalance(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val RECENT_LIMIT = 5
        const val EARNINGS_PAGE_ROWS = 500
        const val EARNINGS_PAGES_MAX = 10
        const val CONSUMABLES_MAX = 200
        const val CONSUMABLE_RECENT_DAYS = 30L
        private val CONSUMABLE_STATES = setOf("activa", "usada", "caducada")
        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
