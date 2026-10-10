package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.formatPriceCents

/**
 * Lo que el catálogo comercial DICE y las reglas con las que valida, separado de cómo lo pinta. Las reglas son las del
 * backend (`AdminProductoComercialDto.validated`, `AdminTarifaProductoDto.validated` y los CHECK de producción): la
 * pantalla las adelanta para no mandar un guardado que se sabe rechazado, y el servidor las vuelve a aplicar.
 */

/** CHECK `productos_comerciales_tipo_producto_check`. */
internal val PRODUCT_TYPES: List<String> = listOf("suscripcion", "pack_tickets", "compra_examen", "servicio", "consumible")

/** CHECK `tarifas_producto_comercial_periodo_facturacion_check`. */
internal val TARIFF_PERIODS: List<String> = listOf("mensual", "anual", "unitario")

/** CHECK `tarifas_producto_comercial_fase_precio_check`. */
internal val TARIFF_PHASES: List<String> = listOf("first_year", "renewal", "standard")

internal fun productTypeLabel(type: String): String = when (type) {
    "suscripcion" -> "Suscripción"
    "pack_tickets" -> "Pack de tickets"
    "compra_examen" -> "Compra de examen"
    "servicio" -> "Servicio"
    "consumible" -> "Consumible"
    else -> type.ifBlank { "Sin tipo" }
}

internal fun tariffPeriodLabel(period: String): String = when (period) {
    "mensual" -> "Mensual"
    "anual" -> "Anual"
    "unitario" -> "Pago único"
    else -> period.ifBlank { "Sin periodo" }
}

internal fun tariffPhaseLabel(phase: String): String = when (phase) {
    "first_year" -> "Primer año (alta)"
    "renewal" -> "Renovación"
    "standard" -> "Estándar"
    else -> phase.ifBlank { "Sin fase" }
}

/** Etiqueta corta del filtro: en plural y sin «de …», para que los seis quepan en dos líneas de la columna de catálogo. */
internal fun productTypeFilterLabel(type: String): String = when (type) {
    "suscripcion" -> "Suscripciones"
    "pack_tickets" -> "Packs"
    "compra_examen" -> "Exámenes"
    "servicio" -> "Servicios"
    "consumible" -> "Consumibles"
    else -> productTypeLabel(type)
}

internal val PRODUCT_TYPE_FILTERS: List<CatalogFilterOption> =
    listOf(CatalogFilterOption(null, "Todos")) + PRODUCT_TYPES.map { CatalogFilterOption(it, productTypeFilterLabel(it)) }

/** Pestañas de la ficha de un producto. */
enum class ProductTab(val id: String, val label: String) {
    PRODUCT("product", "Producto"),
    TARIFFS("tariffs", "Tarifas"),
    ;

    companion object {
        fun fromId(id: String): ProductTab = entries.firstOrNull { it.id == id } ?: PRODUCT
    }
}

/** Lo que hay escrito en la ficha de un producto. Los números van como texto: son lo que el administrador teclea. */
data class ProductDraft(
    val code: String = "",
    val name: String = "",
    val type: String = "",
    val description: String = "",
    val price: String = "",
    val ticketAmount: String = "",
    val planId: String? = null,
    val promotionalLabel: String = "",
    val position: String = "1",
    val active: Boolean = true,
    val visible: Boolean = true,
    val highlighted: Boolean = false,
)

internal fun CommercialProduct.toDraft() = ProductDraft(
    code = code,
    name = name,
    type = productType,
    description = description.orEmpty(),
    price = priceCents?.let(::centsToInput).orEmpty(),
    ticketAmount = ticketAmount?.toString().orEmpty(),
    planId = planId,
    promotionalLabel = promotionalLabel.orEmpty(),
    position = position.toString(),
    active = active,
    visible = visible,
    highlighted = highlighted,
)

/** El producto que se enviaría al guardar, o `null` si el borrador no es válido (ver [productProblem]). */
internal fun ProductDraft.toProduct(base: CommercialProduct?): CommercialProduct? {
    val priceCents = if (price.isBlank()) null else parsePriceToCents(price) ?: return null
    val tickets = if (ticketAmount.isBlank()) null else parseNonNegativeInt(ticketAmount) ?: return null
    val positionValue = parseNonNegativeInt(position) ?: return null
    return CommercialProduct(
        id = base?.id.orEmpty(),
        code = (base?.code ?: code).trim(),
        productType = type,
        name = name.trim(),
        description = description.trim().takeIf { it.isNotEmpty() },
        planId = planId?.takeIf { it.isNotBlank() },
        priceCents = priceCents,
        currency = base?.currency ?: "EUR",
        ticketAmount = tickets,
        active = active,
        visible = visible,
        highlighted = highlighted,
        promotionalLabel = promotionalLabel.trim().takeIf { it.isNotEmpty() },
        position = positionValue,
        tariffs = base?.tariffs.orEmpty(),
    )
}

private val PRODUCT_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

/**
 * Primer motivo por el que el producto no se puede guardar, o `null`.
 *
 * [existingCodes] son los códigos ya usados por OTROS productos: el código es la clave natural con la que la app pide el
 * producto, y uno repetido pisaría al que ya existe.
 */
internal fun productProblem(draft: ProductDraft, isNew: Boolean, existingCodes: Set<String>): String? {
    if (isNew) {
        if (draft.code.isBlank()) return "El producto necesita un código."
        if (!PRODUCT_CODE.matches(draft.code.trim())) return "El código sólo admite letras, números, punto, guion y guion bajo (máximo 64)."
        if (draft.code.trim() in existingCodes) return "Ya existe un producto con ese código."
    }
    if (draft.name.isBlank()) return "El producto necesita un nombre."
    if (draft.type !in PRODUCT_TYPES) return "Elige el tipo de producto."
    if (draft.price.isNotBlank() && parsePriceToCents(draft.price) == null) {
        return "El precio debe ser un importe válido, por ejemplo 12,99."
    }
    if (draft.ticketAmount.isNotBlank()) {
        val tickets = parseNonNegativeInt(draft.ticketAmount) ?: return "Los OposiTickets deben ser un número entero."
        if (tickets < 1) return "Un pack debe entregar al menos un OposiTicket."
    } else if (draft.type == "pack_tickets") {
        return "Un pack de tickets necesita la cantidad de OposiTickets que entrega."
    }
    if (draft.type == "suscripcion" && draft.planId.isNullOrBlank()) return "Una suscripción necesita su plan."
    if (parseNonNegativeInt(draft.position) == null) return "La posición debe ser un número entero no negativo."
    if (draft.highlighted && !draft.visible) return "Un producto destacado tiene que ser visible."
    return null
}

/**
 * Campos opcionales que se enviaron VACÍOS y el servidor devolvió con su valor anterior: su guardado sólo escribe las
 * columnas que recibe, y un campo vacío no viaja. Antes que decir «guardado» sobre algo que no cambió, la ficha lo cuenta.
 */
internal fun productKeptByServer(sent: CommercialProduct, saved: CommercialProduct): List<String> = listOfNotNull(
    "la descripción".takeIf { sent.description == null && saved.description != null },
    "el precio".takeIf { sent.priceCents == null && saved.priceCents != null },
    "los OposiTickets".takeIf { sent.ticketAmount == null && saved.ticketAmount != null },
    "el plan".takeIf { sent.planId == null && saved.planId != null },
    "la etiqueta promocional".takeIf { sent.promotionalLabel == null && saved.promotionalLabel != null },
)

/** Lo que hay escrito en el formulario de una tarifa. `id` en blanco = tarifa nueva. */
data class TariffDraft(
    val id: String = "",
    val period: String = "",
    val phase: String = "",
    val price: String = "",
    val position: String = "1",
    val active: Boolean = true,
    val visible: Boolean = true,
) {
    val isNew: Boolean get() = id.isBlank()
}

internal fun CommercialTariff.toDraft() = TariffDraft(
    id = id,
    period = billingPeriod,
    phase = pricePhase,
    price = centsToInput(priceCents),
    position = position.toString(),
    active = active,
    visible = visible,
)

/** [others] son las demás tarifas del producto: (periodo, fase) es su clave natural y no se puede repetir. */
internal fun tariffProblem(draft: TariffDraft, others: List<CommercialTariff>): String? {
    if (draft.period !in TARIFF_PERIODS) return "Elige el periodo de facturación."
    if (draft.phase !in TARIFF_PHASES) return "Elige la fase de precio."
    if (draft.isNew && others.any { it.billingPeriod == draft.period && it.pricePhase == draft.phase }) {
        return "Este producto ya tiene una tarifa ${tariffPeriodLabel(draft.period).lowercase()} de ${tariffPhaseLabel(draft.phase).lowercase()}."
    }
    if (draft.price.isBlank()) return "La tarifa necesita un precio."
    if (parsePriceToCents(draft.price) == null) return "El precio debe ser un importe válido, por ejemplo 12,99."
    if (parseNonNegativeInt(draft.position) == null) return "La posición debe ser un número entero no negativo."
    return null
}

internal fun TariffDraft.toTariff(productId: String, currency: String): CommercialTariff? {
    val cents = parsePriceToCents(price) ?: return null
    val positionValue = parseNonNegativeInt(position) ?: return null
    return CommercialTariff(
        id = id,
        productId = productId,
        billingPeriod = period,
        pricePhase = phase,
        priceCents = cents,
        currency = currency,
        active = active,
        visible = visible,
        position = positionValue,
    )
}

internal fun productMatches(product: CommercialProduct, query: String, type: String?): Boolean {
    val q = query.trim().lowercase()
    val matchesQuery = q.isEmpty() || product.name.lowercase().contains(q) || product.code.lowercase().contains(q)
    return matchesQuery && (type == null || product.productType == type)
}

/** Precio que se enseña de un producto: el suyo, o que lo fijan sus tarifas, o que no tiene ninguno. */
internal fun productPriceLabel(product: CommercialProduct): String = when {
    product.priceCents != null -> formatPriceCents(product.priceCents!!, product.currency)
    product.tariffs.isNotEmpty() -> "Por tarifa"
    else -> "Sin precio"
}

internal fun productRowSpec(product: CommercialProduct): CatalogRowSpec {
    val status = commerceStatus(product.active, product.visible, product.highlighted)
    return CatalogRowSpec(
        key = product.id,
        title = product.name.ifBlank { "Producto sin nombre" },
        eyebrow = product.code,
        // Dos datos, no tres: la fila mide 330 dp y el tercero se cortaba. En un pack lo que distingue a uno de otro es
        // cuántos OT entrega, y eso ya dice que es un pack; en el resto, el tipo.
        meta = listOf(
            product.ticketAmount?.let { "$it OT" } ?: productTypeLabel(product.productType),
            productPriceLabel(product),
        ),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun productFacts(product: CommercialProduct?, plans: List<CommercialPlan>): List<WorkspaceFact> {
    if (product == null) return emptyList()
    val status = commerceStatus(product.active, product.visible, product.highlighted)
    return buildList {
        add(WorkspaceFact("Estado", status.label))
        add(WorkspaceFact("Tipo", productTypeLabel(product.productType)))
        add(WorkspaceFact("Precio", productPriceLabel(product)))
        add(WorkspaceFact("Tarifas", if (product.tariffs.isEmpty()) "Ninguna" else product.tariffs.size.toString()))
        product.ticketAmount?.let { add(WorkspaceFact("OposiTickets", it.toString())) }
        product.centsPerTicket?.let { add(WorkspaceFact("Coste por OT", "${formatCentsPerTicket(it)} ${product.currency}")) }
        if (product.planId != null) {
            add(WorkspaceFact("Plan", plans.firstOrNull { it.id == product.planId }?.name ?: "No está en el catálogo de planes"))
        }
        add(WorkspaceFact("Posición", product.position.toString()))
    }
}

internal val productInformationLines: List<String> = listOf(
    "Estos valores los lee OposiBot: al guardar cambian para los usuarios, sin desplegar nada.",
    "El código identifica el producto en la app y no se puede cambiar una vez creado.",
    "En una suscripción el precio real lo fijan las tarifas (periodo y fase), no el precio del producto.",
    "Inactivo no se puede comprar. Oculto sigue activo, pero no aparece en el catálogo de la app.",
)
