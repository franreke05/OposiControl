package com.oposicontrol.admin.domain.model

/**
 * Catálogo comercial administrable: planes, packs de OposiTickets y sus precios.
 *
 * Es el catálogo que OposiBot ya lee de la base de datos, de modo que editarlo
 * aquí cambia lo que ve el usuario sin desplegar la app. Ésa es toda la razón de
 * que exista: los precios y los packs cambian, y no pueden vivir en el código.
 *
 * El dinero se representa siempre en **céntimos enteros**. Un `Double` para
 * precios arrastra error de representación y termina cobrando 9,989999 €; el
 * formateo con coma decimal es cosa de la presentación, no del modelo.
 */
data class CommercialProduct(
    val id: String,
    val code: String,
    val productType: String,
    val name: String,
    val description: String? = null,
    val planId: String? = null,
    val priceCents: Int? = null,
    val currency: String = "EUR",
    /** OposiTickets que entrega un pack. Null si el producto no es un pack. */
    val ticketAmount: Int? = null,
    val active: Boolean = true,
    val visible: Boolean = true,
    /** Marca de catálogo tipo "Más elegido" / "Mejor valor". */
    val highlighted: Boolean = false,
    val promotionalLabel: String? = null,
    val position: Int = 1,
    val tariffs: List<CommercialTariff> = emptyList(),
) {
    /**
     * Precio por OposiTicket, en céntimos, para comparar packs entre sí. Null si
     * el producto no es un pack con precio. Es la cifra que dice si un pack
     * grande compensa de verdad frente a uno pequeño.
     */
    val centsPerTicket: Double?
        get() {
            val price = priceCents ?: return null
            val tickets = ticketAmount ?: return null
            if (tickets <= 0) return null
            return price.toDouble() / tickets
        }
}

/**
 * Una tarifa de un producto.
 *
 * [billingPeriod] (mensual/anual) y [pricePhase] (alta/renovación) son dos
 * dimensiones distintas a propósito: un plan puede costar 2,99 €/mes el primer
 * año y 4,99 €/mes al renovar, y sin separarlas habría que duplicar el producto
 * para representarlo.
 *
 * Los valores permitidos de ambos campos los fija la base de datos; el cliente
 * no impone un enum adivinado que rompería en cuanto se añadiese uno nuevo.
 */
data class CommercialTariff(
    val id: String,
    val productId: String,
    val billingPeriod: String,
    val pricePhase: String,
    val priceCents: Int,
    val currency: String = "EUR",
    val active: Boolean = true,
    val visible: Boolean = true,
    val position: Int = 1,
)

/** Formatea céntimos enteros como importe legible ("12,99 EUR"). */
fun formatPriceCents(priceCents: Int, currency: String): String {
    val sign = if (priceCents < 0) "-" else ""
    val absolute = if (priceCents < 0) -priceCents else priceCents
    val cents = absolute % 100
    return "$sign${absolute / 100},${cents.toString().padStart(2, '0')} ${currency.uppercase()}"
}
