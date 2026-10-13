package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.domain.model.TicketsOverview

/**
 * Lo que la pantalla de OposiTickets DICE, separado de cómo lo pinta: funciones puras que se prueban sin Compose.
 */

/**
 * Un ajuste manual no tiene tope de producto (decisión del propietario, 2026-10-13). A partir de esta cantidad, en
 * cualquier sentido, la confirmación pregunta expresamente si es intencionado: es lo que queda de la red contra el cero
 * de más.
 */
internal const val ADJUSTMENT_LARGE_AMOUNT: Int = 100_000

/** Lo más que cabe en una cartera: el saldo es un entero de 32 bits en el servidor y en la base de datos. */
internal const val WALLET_MAX_BALANCE: Int = Int.MAX_VALUE

internal fun isLargeAdjustment(amount: Int): Boolean = amount > ADJUSTMENT_LARGE_AMOUNT || amount < -ADJUSTMENT_LARGE_AMOUNT

/** Longitud máxima del motivo en el backend (`MAX_MOTIVO_LENGTH`). */
internal const val ADJUSTMENT_MAX_REASON: Int = 1_000

/** El backend devuelve como mucho este número de movimientos por cartera, sin paginar. */
internal const val MOVEMENTS_SERVER_LIMIT: Int = 100

/**
 * Nombre legible de un tipo de movimiento. Los valores son los del CHECK `transacciones_cartera_tipo_check`; uno que esta
 * versión no conozca se enseña tal cual, nunca como «desconocido».
 */
internal fun ticketMovementTypeLabel(type: String): String = when (type.trim().lowercase()) {
    "recarga" -> "Recarga"
    "pack_tickets" -> "Pack de tickets"
    "gasto" -> "Gasto"
    "compra_examen" -> "Compra de examen"
    "compra_servicio" -> "Compra de servicio"
    "ajuste" -> "Ajuste manual"
    "devolucion" -> "Devolución"
    "regalo" -> "Regalo"
    "reto_diario" -> "Reto diario"
    "bonus_racha" -> "Bonus de racha"
    "referido" -> "Referido"
    "reporte_validado" -> "Reporte validado"
    "potenciador" -> "Potenciador"
    "protector" -> "Protector"
    else -> type.ifBlank { "Sin tipo" }
}

/** Origen del movimiento tal como lo guarda el ledger, o su ausencia dicha como ausencia. */
internal fun ticketOriginLabel(originType: String?): String = when (originType?.trim()?.lowercase()) {
    null, "" -> "Sin origen registrado"
    "admin" -> "Administración"
    "compra" -> "Compra"
    "reporte" -> "Reporte"
    "sistema" -> "Sistema"
    else -> originType.trim()
}

/** Entero con separador de miles («2.600»), como se escriben las cantidades en el resto del panel. */
internal fun formatCount(value: Int): String {
    val digits = kotlin.math.abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0) "−$grouped" else grouped
}

/** «+50 OT» / «−10 OT»: el signo va escrito, no sólo en el color. */
internal fun formatSignedOt(amount: Int): String = when {
    amount > 0 -> "+${formatCount(amount)} OT"
    amount < 0 -> "${formatCount(amount)} OT"
    else -> "0 OT"
}

internal fun formatOt(amount: Int): String = "${formatCount(amount)} OT"

/** Abono en positivo; un cargo es informativo, no un error. */
internal fun ticketMovementTone(amount: Int): StatusTone = if (amount > 0) StatusTone.POSITIVE else StatusTone.NEUTRAL

/** «Saldo 100 → 150 OT», sólo si el ledger trae los dos lados. */
internal fun ticketBalanceTrail(movement: TicketMovement): String? {
    val after = movement.balanceAfter ?: return null
    val before = movement.balanceBefore ?: return "Saldo resultante ${formatOt(after)}"
    return "Saldo ${formatCount(before)} → ${formatOt(after)}"
}

/** Fecha y hora del asiento, de un instante ISO-8601: «2026-10-09 14:32». Si no tiene esa forma, se enseña tal cual. */
internal fun formatLedgerInstant(iso: String): String {
    val text = iso.trim()
    if (text.length < 16 || text[10] != 'T') return text.ifBlank { "Sin fecha" }
    return text.substring(0, 10) + " " + text.substring(11, 16)
}

internal fun walletMatches(wallet: WalletRow, query: String, filter: WalletFilter): Boolean {
    val q = query.trim().lowercase()
    val matchesQuery = q.isEmpty() || wallet.userName.lowercase().contains(q) || wallet.specialty.lowercase().contains(q)
    val matchesFilter = when (filter) {
        WalletFilter.ALL -> true
        WalletFilter.WITH_BALANCE -> wallet.balance > 0
        WalletFilter.EMPTY -> wallet.balance <= 0
    }
    return matchesQuery && matchesFilter
}

internal fun walletCatalogHasFilters(query: String, filter: WalletFilter): Boolean = query.isNotBlank() || filter != WalletFilter.ALL

/**
 * Lo tecleado como cantidad («+50», «-10», «50») a entero. `null` si no es un entero: los OposiTickets no tienen decimales,
 * y redondear en silencio movería un saldo distinto del que se escribió.
 */
internal fun parseAdjustmentAmount(text: String): Int? {
    val t = text.trim().replace('−', '-')
    if (t.isEmpty()) return null
    val digits = t.removePrefix("+").removePrefix("-")
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
    // Más cifras de las que caben en una cartera: no es un entero que el servidor pueda recibir.
    val value = digits.trimStart('0').ifEmpty { "0" }.takeIf { it.length <= 10 }?.toLong()?.takeIf { it <= WALLET_MAX_BALANCE }
        ?: return null
    return if (t.startsWith("-")) -value.toInt() else value.toInt()
}

/** Lo tecleado es un entero bien escrito, pero mayor de lo que cabe en una cartera. */
internal fun adjustmentExceedsStorage(text: String): Boolean {
    val digits = text.trim().replace('−', '-').removePrefix("+").removePrefix("-")
    return digits.isNotEmpty() && digits.all { it.isDigit() } && parseAdjustmentAmount(text) == null
}

/**
 * Primer motivo por el que el ajuste no se puede enviar, o `null` si es válido. No hay tope de cantidad: sólo el límite
 * físico de la cartera. [balance] es el saldo que se ve ahora; con él se avisa antes de que el servidor rechace un abono
 * que no cabe.
 */
internal fun adjustmentProblem(amountText: String, reason: String, hasWallet: Boolean, balance: Int? = null): String? {
    if (!hasWallet) return "Elige una cartera antes de ajustar el saldo."
    if (amountText.isBlank()) return "Escribe la cantidad de OposiTickets."
    if (adjustmentExceedsStorage(amountText)) {
        return "Una cartera no admite más de ${formatCount(WALLET_MAX_BALANCE)} OposiTickets."
    }
    val amount = parseAdjustmentAmount(amountText) ?: return "La cantidad debe ser un número entero, por ejemplo 50 o -10."
    if (amount == 0) return "La cantidad no puede ser cero."
    if (balance != null && balance.toLong() + amount > WALLET_MAX_BALANCE) {
        return "El saldo pasaría de ${formatCount(WALLET_MAX_BALANCE)} OposiTickets, que es lo más que admite una cartera."
    }
    if (reason.isBlank()) return "Escribe el motivo: queda registrado en el movimiento."
    if (reason.trim().length > ADJUSTMENT_MAX_REASON) return "El motivo no puede superar ${formatCount(ADJUSTMENT_MAX_REASON)} caracteres."
    return null
}

/** Aviso de un ajuste grande donde no hay diálogo: dice la cifra entera y cómo seguir. */
internal fun largeAdjustmentWarning(amount: Int): String =
    "Son ${formatSignedOt(amount)}, más de ${formatCount(ADJUSTMENT_LARGE_AMOUNT)}. Si es correcto, pulsa «Aplicar» otra vez."

/** Los cuatro recuentos del resumen. Un recuento que el backend no pudo calcular se dice «No disponible», no «0». */
internal fun ticketsOverviewFacts(overview: TicketsOverview?): List<WorkspaceFact> {
    fun value(count: Int?): String = count?.let(::formatCount) ?: "No disponible"
    return listOf(
        WorkspaceFact("Carteras", value(overview?.wallets)),
        WorkspaceFact("Movimientos", value(overview?.movements)),
        WorkspaceFact("Abonos", value(overview?.credits)),
        WorkspaceFact("Cargos", value(overview?.debits)),
    )
}

internal val ticketsInformationLines: List<String> = listOf(
    "Un ajuste queda registrado como un movimiento y en auditoría, y no se puede deshacer: para corregirlo se hace otro en sentido contrario.",
    "La cantidad es un entero distinto de cero, sin tope. Por encima de ${formatCount(ADJUSTMENT_LARGE_AMOUNT)} OposiTickets se pide una confirmación expresa.",
    "El saldo lo mueve siempre el servidor. Confirmar dos veces el mismo ajuste no lo aplica dos veces.",
    "El total de OposiTickets en circulación no se puede calcular todavía desde el panel.",
)
