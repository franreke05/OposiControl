package com.oposicontrol.admin.domain.model

/**
 * OposiMarket administrable.
 *
 * Todo el dinero se representa en enteros: [MarketTicketPack.priceCents] en
 * céntimos y los precios de servicio en OposiTickets o céntimos. Un `Double`
 * para precios acumula error de representación; el formateo es presentación.
 */
data class MarketTicketPack(
    val id: String,
    val code: String,
    val name: String,
    val description: String? = null,
    val ticketAmount: Int,
    val priceCents: Int,
    val currency: String = "EUR",
    val active: Boolean = true,
    val position: Int = 1,
) {
    /**
     * Céntimos por OposiTicket. Es lo que dice si la escala de packs tiene
     * sentido: un pack mayor que no baje esta cifra no le compensa a nadie.
     * Null cuando no hay tickets, en vez de cero o infinito, que mentirían.
     */
    val centsPerTicket: Double? get() = if (ticketAmount > 0) priceCents.toDouble() / ticketAmount else null
}

/** Potenciadores, protectores y servicios futuros del Market. */
data class MarketService(
    val id: String,
    val code: String,
    val name: String,
    val description: String? = null,
    val serviceType: String,
    val priceTickets: Int? = null,
    val priceCents: Int? = null,
    val active: Boolean = true,
) {
    /** Un servicio ofrecido sin ningún precio no se puede adquirir. */
    val isOfferedWithoutPrice: Boolean get() = active && priceTickets == null && priceCents == null
}

data class MarketPromotion(
    val id: String,
    val code: String,
    val name: String,
    val discountType: String,
    val value: Double,
    val active: Boolean = true,
    val validFrom: String? = null,
    val validUntil: String? = null,
) {
    /** Ventana imposible: la promoción no se aplicaría nunca. */
    val hasInvertedWindow: Boolean
        get() = validFrom != null && validUntil != null && validUntil < validFrom
}

/**
 * Regla de pop-up comercial: dónde, cuándo y a quién se muestra.
 *
 * Existe para que la app no lleve el disparador escrito en el código.
 */
data class MarketPopupRule(
    val id: String,
    val code: String,
    val trigger: String,
    val title: String,
    val message: String,
    val primaryCta: String? = null,
    val screen: String? = null,
    val moment: String? = null,
    val minimumPlan: String? = null,
    val targetExamType: String? = null,
    val minimumFrequencyHours: Int? = null,
    val priority: Int = 0,
    val active: Boolean = true,
) {
    /** Activa sin frecuencia: puede reaparecer en cada disparo. */
    val canRepeatOnEveryTrigger: Boolean get() = active && minimumFrequencyHours == null
}
