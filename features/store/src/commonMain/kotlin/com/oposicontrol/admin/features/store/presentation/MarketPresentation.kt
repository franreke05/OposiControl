package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUse
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.model.formatPriceCents
import kotlinx.datetime.LocalDate

/**
 * Lo que OposiMarket DICE de packs, servicios, promociones y pop-ups, y las reglas con las que valida, sin Compose.
 *
 * Las reglas son las del backend (`AdminMarket.kt`) y los valores de catálogo son los CHECK de producción: la pantalla
 * los adelanta para no mandar un guardado que se sabe rechazado, y el servidor los vuelve a aplicar.
 */

/**
 * Lo que hay que saber antes de tocar nada aquí: la app no lee estas cuatro tablas todavía. Va dentro de la ficha de las
 * cuatro secciones, a cualquier ancho de ventana, porque cambia lo que significa «guardar».
 */
internal const val MARKET_NOT_CONSUMED_LINE: String =
    "OposiBot hoy sólo lee el Catálogo (productos, tarifas y planes). Lo que se guarda aquí queda configurado en el " +
        "servidor, pero la app todavía no lo consume."

internal const val FILTER_ACTIVE: String = "active"
internal const val FILTER_INACTIVE: String = "inactive"

internal val ACTIVE_FILTERS_MASCULINE: List<CatalogFilterOption> = listOf(
    CatalogFilterOption(null, "Todos"),
    CatalogFilterOption(FILTER_ACTIVE, "Activos"),
    CatalogFilterOption(FILTER_INACTIVE, "Inactivos"),
)

internal val ACTIVE_FILTERS_FEMININE: List<CatalogFilterOption> = listOf(
    CatalogFilterOption(null, "Todas"),
    CatalogFilterOption(FILTER_ACTIVE, "Activas"),
    CatalogFilterOption(FILTER_INACTIVE, "Inactivas"),
)

private fun matchesActive(active: Boolean, filter: String?): Boolean = when (filter) {
    FILTER_ACTIVE -> active
    FILTER_INACTIVE -> !active
    else -> true
}

private fun matchesText(query: String, vararg fields: String?): Boolean {
    val q = query.trim().lowercase()
    return q.isEmpty() || fields.any { it?.lowercase()?.contains(q) == true }
}

/** «Activa / Inactiva» para promoción y regla; el masculino lo da [commerceStatus]. */
internal fun feminineStatus(active: Boolean): CommerceStatus =
    if (active) CommerceStatus("Activa", StatusTone.POSITIVE) else CommerceStatus("Inactiva", StatusTone.NEGATIVE)

private val MARKET_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

/**
 * Código de una entidad nueva: obligatorio, con forma segura y sin repetir. [normalise] es lo que hace el servidor con él,
 * para que «otoño» y «OTOÑO» se reconozcan como el mismo código cuando el servidor los iguala.
 */
private fun newCodeProblem(
    code: String,
    existing: List<String>,
    missing: String,
    duplicate: String,
    normalise: (String) -> String = { it.trim() },
): String? {
    val value = normalise(code)
    return when {
        value.isEmpty() -> missing
        !MARKET_CODE.matches(value) -> "El código sólo admite letras, números, punto, guion y guion bajo (máximo 64)."
        existing.any { normalise(it) == value } -> duplicate
        else -> null
    }
}

private fun String.orNullIfBlank(): String? = trim().takeIf { it.isNotEmpty() }

// =====================================================================================================================
// Packs de OposiTickets
// =====================================================================================================================

data class PackDraft(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val ticketAmount: String = "",
    val price: String = "",
    val position: String = "1",
    val active: Boolean = true,
)

internal object PackSpec : MarketEditorSpec<MarketTicketPack, PackDraft> {
    override val emptyDraft = PackDraft()

    override fun id(item: MarketTicketPack) = item.id

    override fun toDraft(item: MarketTicketPack) = PackDraft(
        code = item.code,
        name = item.name,
        description = item.description.orEmpty(),
        ticketAmount = item.ticketAmount.toString(),
        price = centsToInput(item.priceCents),
        position = item.position.toString(),
        active = item.active,
    )

    override fun problem(draft: PackDraft, isNew: Boolean, others: List<MarketTicketPack>): String? {
        if (isNew) newCodeProblem(draft.code, others.map { it.code }, "El pack necesita un código.", "Ya existe un pack con ese código.")?.let { return it }
        if (draft.name.isBlank()) return "El pack necesita un nombre."
        val tickets = parseNonNegativeInt(draft.ticketAmount) ?: return "Los OposiTickets deben ser un número entero."
        // Un pack de cero tickets cobra sin entregar nada.
        if (tickets < 1) return "Un pack debe entregar al menos un OposiTicket."
        if (draft.price.isBlank()) return "El pack necesita un precio (puede ser 0)."
        if (parsePriceToCents(draft.price) == null) return "El precio debe ser un importe válido, por ejemplo 4,99."
        if (parseNonNegativeInt(draft.position) == null) return "La posición debe ser un número entero no negativo."
        return null
    }

    override fun toEntity(draft: PackDraft, base: MarketTicketPack?): MarketTicketPack? = MarketTicketPack(
        id = base?.id.orEmpty(),
        code = base?.code ?: draft.code.trim(),
        name = draft.name.trim(),
        description = draft.description.orNullIfBlank(),
        ticketAmount = parseNonNegativeInt(draft.ticketAmount) ?: return null,
        priceCents = parsePriceToCents(draft.price) ?: return null,
        currency = base?.currency ?: "EUR",
        active = draft.active,
        position = parseNonNegativeInt(draft.position) ?: return null,
    )

    override fun matches(item: MarketTicketPack, query: String, filter: String?) =
        matchesText(query, item.name, item.code) && matchesActive(item.active, filter)

    override fun keptByServer(sent: MarketTicketPack, saved: MarketTicketPack): List<String> =
        listOfNotNull("la descripción".takeIf { sent.description == null && saved.description != null })
}

internal fun packRowSpec(pack: MarketTicketPack): CatalogRowSpec {
    val status = commerceStatus(pack.active)
    return CatalogRowSpec(
        key = pack.id,
        title = pack.name.ifBlank { "Pack sin nombre" },
        eyebrow = pack.code,
        meta = listOf("${pack.ticketAmount} OT", formatPriceCents(pack.priceCents, pack.currency)),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun packFacts(pack: MarketTicketPack?): List<WorkspaceFact> {
    if (pack == null) return emptyList()
    return buildList {
        add(WorkspaceFact("Estado", commerceStatus(pack.active).label))
        add(WorkspaceFact("OposiTickets", pack.ticketAmount.toString()))
        add(WorkspaceFact("Precio", formatPriceCents(pack.priceCents, pack.currency)))
        pack.centsPerTicket?.let { add(WorkspaceFact("Coste por OT", "${formatCentsPerTicket(it)} ${pack.currency}")) }
        add(WorkspaceFact("Posición", pack.position.toString()))
    }
}

internal val packInformationLines: List<String> = listOf(
    "El coste por OposiTicket dice si la escala compensa: un pack mayor que no lo baje no le interesa a nadie.",
    "El código identifica el pack y no se puede cambiar una vez creado.",
)

// =====================================================================================================================
// Servicios
// =====================================================================================================================

/** CHECK `servicios_tipo_servicio_check`. */
internal val SERVICE_TYPES: List<String> = listOf(
    "premium", "correccion", "tutoria", "contenido_extra", "protector_racha", "protector_tickets", "protector_dual",
    "potenciador", "doble_oportunidad", "triple_oportunidad", "modo_papel", "carta_ccaa_comodin",
)

internal fun serviceTypeLabel(type: String): String = when (type) {
    "premium" -> "Premium"
    "correccion" -> "Corrección"
    "tutoria" -> "Tutoría"
    "contenido_extra" -> "Contenido extra"
    "protector_racha" -> "Protector de racha"
    "protector_tickets" -> "Protector de tickets"
    "protector_dual" -> "Protector dual"
    "potenciador" -> "Potenciador"
    "doble_oportunidad" -> "Doble oportunidad"
    "triple_oportunidad" -> "Triple oportunidad"
    "modo_papel" -> "Modo papel"
    "carta_ccaa_comodin" -> "Carta CCAA comodín"
    else -> type.ifBlank { "Sin tipo" }
}

data class ServiceDraft(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val type: String = "",
    val priceTickets: String = "",
    val price: String = "",
    val active: Boolean = true,
)

internal object ServiceSpec : MarketEditorSpec<MarketService, ServiceDraft> {
    override val emptyDraft = ServiceDraft()

    override fun id(item: MarketService) = item.id

    override fun toDraft(item: MarketService) = ServiceDraft(
        code = item.code,
        name = item.name,
        description = item.description.orEmpty(),
        type = item.serviceType,
        priceTickets = item.priceTickets?.toString().orEmpty(),
        price = item.priceCents?.let(::centsToInput).orEmpty(),
        active = item.active,
    )

    override fun problem(draft: ServiceDraft, isNew: Boolean, others: List<MarketService>): String? {
        if (isNew) newCodeProblem(draft.code, others.map { it.code }, "El servicio necesita un código.", "Ya existe un servicio con ese código.")?.let { return it }
        if (draft.name.isBlank()) return "El servicio necesita un nombre."
        if (draft.type !in SERVICE_TYPES) return "Elige el tipo de servicio."
        if (draft.priceTickets.isNotBlank() && parseNonNegativeInt(draft.priceTickets) == null) {
            return "El precio en OposiTickets debe ser un número entero no negativo."
        }
        if (draft.price.isNotBlank() && parsePriceToCents(draft.price) == null) {
            return "El precio debe ser un importe válido, por ejemplo 4,99."
        }
        // Un servicio activo sin ningún precio no se puede adquirir de ninguna forma.
        if (draft.active && draft.priceTickets.isBlank() && draft.price.isBlank()) {
            return "Un servicio activo necesita precio en OposiTickets o en dinero; si no, no se puede adquirir."
        }
        return null
    }

    override fun toEntity(draft: ServiceDraft, base: MarketService?): MarketService? = MarketService(
        id = base?.id.orEmpty(),
        code = base?.code ?: draft.code.trim(),
        name = draft.name.trim(),
        description = draft.description.orNullIfBlank(),
        serviceType = draft.type,
        priceTickets = if (draft.priceTickets.isBlank()) null else parseNonNegativeInt(draft.priceTickets) ?: return null,
        priceCents = if (draft.price.isBlank()) null else parsePriceToCents(draft.price) ?: return null,
        active = draft.active,
    )

    override fun matches(item: MarketService, query: String, filter: String?) =
        matchesText(query, item.name, item.code, serviceTypeLabel(item.serviceType)) && matchesActive(item.active, filter)

    override fun keptByServer(sent: MarketService, saved: MarketService): List<String> = listOfNotNull(
        "la descripción".takeIf { sent.description == null && saved.description != null },
        "el precio en OposiTickets".takeIf { sent.priceTickets == null && saved.priceTickets != null },
        "el precio en dinero".takeIf { sent.priceCents == null && saved.priceCents != null },
    )
}

/** Precio de un servicio dicho en una línea: en OT, en dinero, en ambos o ninguno. */
internal fun servicePriceLabel(service: MarketService): String = listOfNotNull(
    service.priceTickets?.let { "$it OT" },
    service.priceCents?.let { formatPriceCents(it, "EUR") },
).joinToString(" · ").ifEmpty { "Sin precio" }

internal fun serviceRowSpec(service: MarketService): CatalogRowSpec {
    val status = commerceStatus(service.active)
    return CatalogRowSpec(
        key = service.id,
        title = service.name.ifBlank { "Servicio sin nombre" },
        eyebrow = service.code,
        meta = listOf(serviceTypeLabel(service.serviceType), servicePriceLabel(service)),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun serviceFacts(service: MarketService?): List<WorkspaceFact> {
    if (service == null) return emptyList()
    return listOf(
        WorkspaceFact("Estado", commerceStatus(service.active).label),
        WorkspaceFact("Tipo", serviceTypeLabel(service.serviceType)),
        WorkspaceFact("Precio en OT", service.priceTickets?.toString() ?: "No tiene"),
        WorkspaceFact("Precio", service.priceCents?.let { formatPriceCents(it, "EUR") } ?: "No tiene"),
    )
}

internal val serviceInformationLines: List<String> = listOf(
    "Un servicio puede cobrarse en OposiTickets, en dinero o de las dos formas. Activo sin ningún precio no se puede adquirir.",
    "Lo que hace cada tipo (duración, multiplicador) no se configura aquí: es una decisión de producto pendiente.",
)

// =====================================================================================================================
// Promociones
// =====================================================================================================================

/** CHECK `promociones_tipo_descuento_check`. */
internal val DISCOUNT_TYPES: List<String> = listOf("porcentaje", "cantidad", "tickets_extra")

internal fun discountTypeLabel(type: String): String = when (type) {
    "porcentaje" -> "Porcentaje"
    "cantidad" -> "Cantidad fija"
    "tickets_extra" -> "OposiTickets extra"
    else -> type.ifBlank { "Sin tipo" }
}

/** El valor de una promoción dicho con su tipo. La unidad de «cantidad» no la define el contrato: se enseña el número. */
internal fun promotionValueLabel(type: String, value: Double): String = when (type) {
    "porcentaje" -> "${formatBenefitNumber(value)} %"
    "tickets_extra" -> "+${formatBenefitNumber(value)} OT"
    "cantidad" -> "Cantidad ${formatBenefitNumber(value)}"
    else -> formatBenefitNumber(value)
}

/** Pestañas de la ficha de una promoción. */
enum class PromotionTab(val id: String, val label: String) {
    PROMOTION("promotion", "Promoción"),
    USES("uses", "Usos"),
    ;

    companion object {
        fun fromId(id: String): PromotionTab = entries.firstOrNull { it.id == id } ?: PROMOTION
    }
}

/** Las fechas van como texto `AAAA-MM-DD`: es lo que el administrador teclea. */
data class PromotionDraft(
    val code: String = "",
    val name: String = "",
    val discountType: String = "",
    val value: String = "",
    val validFrom: String = "",
    val validUntil: String = "",
    val active: Boolean = true,
)

/** La parte de fecha (`AAAA-MM-DD`) de un instante ISO, o vacío. */
internal fun isoDatePart(instant: String?): String =
    instant?.take(10)?.takeIf { parseDate(it) != null }.orEmpty()

internal fun parseDate(text: String): LocalDate? {
    val t = text.trim()
    if (t.length != 10) return null
    return runCatching { LocalDate.parse(t) }.getOrNull()
}

/**
 * El instante que se guarda para una fecha tecleada: el inicio del día para «desde» y su último segundo para «hasta», en
 * UTC. Si la fecha no cambió respecto a [stored], se conserva el instante original en vez de redondearlo.
 */
internal fun dateToInstant(text: String, stored: String?, endOfDay: Boolean): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (stored != null && isoDatePart(stored) == t) return stored
    return if (endOfDay) "${t}T23:59:59Z" else "${t}T00:00:00Z"
}

internal object PromotionSpec : MarketEditorSpec<MarketPromotion, PromotionDraft> {
    override val emptyDraft = PromotionDraft()

    override fun id(item: MarketPromotion) = item.id

    override fun toDraft(item: MarketPromotion) = PromotionDraft(
        code = item.code,
        name = item.name,
        discountType = item.discountType,
        value = formatBenefitNumber(item.value),
        validFrom = isoDatePart(item.validFrom),
        validUntil = isoDatePart(item.validUntil),
        active = item.active,
    )

    override fun problem(draft: PromotionDraft, isNew: Boolean, others: List<MarketPromotion>): String? {
        // El servidor guarda el código en mayúsculas: «otoño» choca con «OTOÑO».
        if (isNew) {
            newCodeProblem(
                code = draft.code,
                existing = others.map { it.code },
                missing = "La promoción necesita un código.",
                duplicate = "Ya existe una promoción con ese código.",
                normalise = { it.trim().uppercase() },
            )?.let { return it }
        }
        if (draft.name.isBlank()) return "La promoción necesita un nombre."
        if (draft.discountType !in DISCOUNT_TYPES) return "Elige el tipo de descuento."
        val value = parseBenefitNumeric(draft.value) ?: return "El valor debe ser un número, por ejemplo 15."
        if (value < 0) return "El descuento no puede ser negativo."
        if (draft.discountType == "tickets_extra" && value != value.toLong().toDouble()) {
            return "Los OposiTickets extra deben ser un número entero."
        }
        if (draft.validFrom.isNotBlank() && parseDate(draft.validFrom) == null) return "La fecha de inicio debe tener la forma AAAA-MM-DD."
        if (draft.validUntil.isNotBlank() && parseDate(draft.validUntil) == null) return "La fecha de fin debe tener la forma AAAA-MM-DD."
        // Una ventana invertida no se aplicaría jamás: es siempre un error de captura.
        if (draft.validFrom.isNotBlank() && draft.validUntil.isNotBlank() && draft.validUntil.trim() < draft.validFrom.trim()) {
            return "La fecha de fin no puede ser anterior a la de inicio."
        }
        return null
    }

    override fun toEntity(draft: PromotionDraft, base: MarketPromotion?): MarketPromotion? = MarketPromotion(
        id = base?.id.orEmpty(),
        code = base?.code ?: draft.code.trim().uppercase(),
        name = draft.name.trim(),
        discountType = draft.discountType,
        value = parseBenefitNumeric(draft.value) ?: return null,
        active = draft.active,
        validFrom = dateToInstant(draft.validFrom, base?.validFrom, endOfDay = false),
        validUntil = dateToInstant(draft.validUntil, base?.validUntil, endOfDay = true),
        uses = base?.uses,
    )

    override fun matches(item: MarketPromotion, query: String, filter: String?) =
        matchesText(query, item.name, item.code) && matchesActive(item.active, filter)

    override fun keptByServer(sent: MarketPromotion, saved: MarketPromotion): List<String> = listOfNotNull(
        "la fecha de inicio".takeIf { sent.validFrom == null && saved.validFrom != null },
        "la fecha de fin".takeIf { sent.validUntil == null && saved.validUntil != null },
    )
}

/** La ventana de validez en una línea. */
internal fun promotionWindowLabel(promotion: MarketPromotion): String {
    val from = isoDatePart(promotion.validFrom)
    val until = isoDatePart(promotion.validUntil)
    return when {
        from.isNotEmpty() && until.isNotEmpty() -> "$from → $until"
        from.isNotEmpty() -> "Desde $from"
        until.isNotEmpty() -> "Hasta $until"
        else -> "Sin fechas"
    }
}

internal fun promotionRowSpec(promotion: MarketPromotion): CatalogRowSpec {
    val status = feminineStatus(promotion.active)
    return CatalogRowSpec(
        key = promotion.id,
        title = promotion.name.ifBlank { "Promoción sin nombre" },
        eyebrow = promotion.code,
        meta = listOf(promotionValueLabel(promotion.discountType, promotion.value), promotionWindowLabel(promotion)),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun promotionFacts(promotion: MarketPromotion?): List<WorkspaceFact> {
    if (promotion == null) return emptyList()
    return buildList {
        add(WorkspaceFact("Estado", feminineStatus(promotion.active).label))
        add(WorkspaceFact("Tipo", discountTypeLabel(promotion.discountType)))
        add(WorkspaceFact("Valor", promotionValueLabel(promotion.discountType, promotion.value)))
        promotion.uses?.let { add(WorkspaceFact("Usos", formatCount(it))) }
        add(WorkspaceFact("Desde", isoDatePart(promotion.validFrom).ifEmpty { "Sin fecha" }))
        add(WorkspaceFact("Hasta", isoDatePart(promotion.validUntil).ifEmpty { "Sin fecha" }))
    }
}

internal val promotionInformationLines: List<String> = listOf(
    "El código se guarda en mayúsculas y no se puede cambiar una vez creada.",
    "Las fechas son días completos en UTC: «desde» empieza a las 00:00 y «hasta» acaba a las 23:59:59.",
    "Los usos sólo se consultan aquí. Nada los registra todavía: aparecerán cuando la app aplique promociones al comprar.",
)

/** Separador de miles con punto, sin depender de la plataforma. */
internal fun formatCount(value: Int): String {
    val digits = kotlin.math.abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** `2026-10-09T14:32:10Z` → «2026-10-09 14:32 UTC». Lo que no se entiende se enseña tal cual. */
internal fun formatUseInstant(instant: String): String {
    val date = instant.take(10)
    val time = instant.drop(11).take(5)
    return if (parseDate(date) != null && time.length == 5 && instant.getOrNull(10) == 'T') "$date $time UTC" else instant
}

internal fun promotionUseTitle(use: MarketPromotionUse): String = formatUseInstant(use.usedAt)

/** Qué pasó con el uso, dicho con palabras: nunca un `true`/`false`. */
internal fun promotionUseOutcome(use: MarketPromotionUse): String = listOf(
    if (use.generatedPurchase) "Generó compra" else "Sin compra",
    when (use.renewedAfter) {
        true -> "Renovó después"
        false -> "No renovó"
        null -> "Renovación por ver"
    },
).joinToString(" · ")

internal fun promotionUseDiscount(use: MarketPromotionUse): String =
    use.discountCents?.let { formatPriceCents(it, "EUR") } ?: "Sin importe"

// =====================================================================================================================
// Reglas de pop-up
// =====================================================================================================================

/** CHECK `reglas_popup_comercial_momento_check`. */
internal val POPUP_MOMENTS: List<String> = listOf("antes", "durante", "despues", "home", "bloqueo")

/** CHECK `reglas_popup_comercial_canonical_v2_tipo_examen_objetivo_check`. */
internal val POPUP_EXAM_TYPES: List<String> = listOf("rafaga", "razonado", "inmersivo")

/** Valores por defecto de la tabla (`frecuencia_minima_horas` 24, `prioridad` 50). */
internal const val POPUP_DEFAULT_FREQUENCY_HOURS: String = "24"
internal const val POPUP_DEFAULT_PRIORITY: String = "50"

internal fun popupMomentLabel(moment: String?): String = when (moment) {
    "antes" -> "Antes del examen"
    "durante" -> "Durante el examen"
    "despues" -> "Después del examen"
    "home" -> "En la pantalla de inicio"
    "bloqueo" -> "Al encontrar un bloqueo"
    null, "" -> "Cualquier momento"
    else -> moment
}

internal fun popupExamTypeLabel(type: String?): String = when (type) {
    "rafaga" -> "Ráfaga"
    "razonado" -> "Razonado"
    "inmersivo" -> "Inmersivo"
    null, "" -> "Cualquier examen"
    else -> type
}

internal fun popupFrequencyLabel(hours: Int?): String = when (hours) {
    null -> "Sin límite"
    0 -> "Sin espera"
    1 -> "Cada hora"
    else -> "Cada $hours h"
}

data class PopupDraft(
    val code: String = "",
    val trigger: String = "",
    val title: String = "",
    val message: String = "",
    val primaryCta: String = "",
    val secondaryCta: String = "",
    val screen: String = "",
    val moment: String? = null,
    val minimumPlan: String = "",
    val targetExamType: String? = null,
    val frequencyHours: String = POPUP_DEFAULT_FREQUENCY_HOURS,
    val priority: String = POPUP_DEFAULT_PRIORITY,
    val active: Boolean = true,
)

internal object PopupSpec : MarketEditorSpec<MarketPopupRule, PopupDraft> {
    override val emptyDraft = PopupDraft()

    override fun id(item: MarketPopupRule) = item.id

    override fun toDraft(item: MarketPopupRule) = PopupDraft(
        code = item.code,
        trigger = item.trigger,
        title = item.title,
        message = item.message,
        primaryCta = item.primaryCta.orEmpty(),
        secondaryCta = item.secondaryCta.orEmpty(),
        screen = item.screen.orEmpty(),
        moment = item.moment,
        minimumPlan = item.minimumPlan.orEmpty(),
        targetExamType = item.targetExamType,
        frequencyHours = item.minimumFrequencyHours?.toString().orEmpty(),
        priority = item.priority.toString(),
        active = item.active,
    )

    override fun problem(draft: PopupDraft, isNew: Boolean, others: List<MarketPopupRule>): String? {
        if (isNew) newCodeProblem(draft.code, others.map { it.code }, "La regla necesita un código.", "Ya existe una regla con ese código.")?.let { return it }
        if (draft.trigger.isBlank()) return "La regla necesita un evento disparador."
        if (draft.title.isBlank()) return "La regla necesita un título."
        if (draft.message.isBlank()) return "La regla necesita un mensaje."
        if (draft.moment != null && draft.moment !in POPUP_MOMENTS) return "Elige un momento de la lista."
        if (draft.targetExamType != null && draft.targetExamType !in POPUP_EXAM_TYPES) return "Elige un tipo de examen de la lista."
        if (draft.frequencyHours.isNotBlank() && parseNonNegativeInt(draft.frequencyHours) == null) {
            return "La frecuencia mínima debe ser un número entero de horas."
        }
        // Sin frecuencia mínima el pop-up puede reaparecer en cada disparo.
        if (draft.active && draft.frequencyHours.isBlank()) {
            return "Una regla activa necesita una frecuencia mínima en horas; sin ella el pop-up reaparecería en cada disparo."
        }
        if (parseNonNegativeInt(draft.priority) == null) return "La prioridad debe ser un número entero no negativo."
        return null
    }

    override fun toEntity(draft: PopupDraft, base: MarketPopupRule?): MarketPopupRule? = MarketPopupRule(
        id = base?.id.orEmpty(),
        code = base?.code ?: draft.code.trim(),
        trigger = draft.trigger.trim(),
        title = draft.title.trim(),
        message = draft.message.trim(),
        primaryCta = draft.primaryCta.orNullIfBlank(),
        secondaryCta = draft.secondaryCta.orNullIfBlank(),
        screen = draft.screen.orNullIfBlank(),
        moment = draft.moment,
        minimumPlan = draft.minimumPlan.orNullIfBlank(),
        targetExamType = draft.targetExamType,
        minimumFrequencyHours = if (draft.frequencyHours.isBlank()) null else parseNonNegativeInt(draft.frequencyHours) ?: return null,
        priority = parseNonNegativeInt(draft.priority) ?: return null,
        active = draft.active,
    )

    override fun matches(item: MarketPopupRule, query: String, filter: String?) =
        matchesText(query, item.title, item.code, item.trigger, item.screen) && matchesActive(item.active, filter)

    override fun keptByServer(sent: MarketPopupRule, saved: MarketPopupRule): List<String> = listOfNotNull(
        "el botón principal".takeIf { sent.primaryCta == null && saved.primaryCta != null },
        "el botón secundario".takeIf { sent.secondaryCta == null && saved.secondaryCta != null },
        "la pantalla".takeIf { sent.screen == null && saved.screen != null },
        "el momento".takeIf { sent.moment == null && saved.moment != null },
        "el plan mínimo".takeIf { sent.minimumPlan == null && saved.minimumPlan != null },
        "el tipo de examen".takeIf { sent.targetExamType == null && saved.targetExamType != null },
    )
}

internal fun popupRowSpec(rule: MarketPopupRule): CatalogRowSpec {
    val status = feminineStatus(rule.active)
    return CatalogRowSpec(
        key = rule.id,
        title = rule.title.ifBlank { "Regla sin título" },
        eyebrow = rule.code,
        meta = listOf(rule.trigger.ifBlank { "Sin disparador" }, popupFrequencyLabel(rule.minimumFrequencyHours)),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun popupFacts(rule: MarketPopupRule?): List<WorkspaceFact> {
    if (rule == null) return emptyList()
    return listOf(
        WorkspaceFact("Estado", feminineStatus(rule.active).label),
        WorkspaceFact("Prioridad", rule.priority.toString()),
        WorkspaceFact("Frecuencia", popupFrequencyLabel(rule.minimumFrequencyHours)),
        WorkspaceFact("Momento", popupMomentLabel(rule.moment)),
        WorkspaceFact("Examen", popupExamTypeLabel(rule.targetExamType)),
        WorkspaceFact("Plan mínimo", rule.minimumPlan?.takeIf { it.isNotBlank() } ?: "Cualquiera"),
    )
}

internal val popupInformationLines: List<String> = listOf(
    "Una regla dice dónde, cuándo y a quién se enseña un pop-up, para que la app no lo lleve escrito en el código.",
    "La frecuencia mínima evita repetir el mismo pop-up: una regla activa siempre la necesita.",
    "Con varias reglas para el mismo disparador, gana la de mayor prioridad.",
)
