package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Administración del catálogo comercial: productos, packs de OposiTickets y sus
 * tarifas.
 *
 * OposiBot ya lee este catálogo de la base de datos (`/v1/comercial/catalogo`),
 * pero no existía forma de editarlo: cambiar el precio de un plan o los OT de un
 * pack exigía tocar código. Estas piezas son la mitad que faltaba.
 *
 * Dos decisiones que conviene no deshacer:
 *
 * - **El dinero es un entero de céntimos** (`precio_centimos`), nunca un decimal.
 *   Un `Double` para precios acumula error de representación y acaba cobrando
 *   9,989999 €. La UI formatea; el transporte y el almacenamiento no.
 * - **`tipo_producto`, `periodo_facturacion` y `fase_precio` se validan sólo como
 *   texto no vacío.** Sus valores permitidos los fija el CHECK de la tabla, que
 *   es la autoridad; codificar aquí un enum adivinado rompería el guardado en
 *   cuanto la base admitiese un valor más, o rechazaría uno legítimo.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminProductoComercialDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val codigo: String = "",
    @EncodeDefault val tipoProducto: String = "",
    @EncodeDefault val nombre: String = "",
    val descripcion: String? = null,
    val planId: String? = null,
    /** Precio en céntimos. Null cuando el producto sólo se vende por tarifas. */
    val precioCentimos: Int? = null,
    @EncodeDefault val moneda: String = "EUR",
    /** OposiTickets que entrega un pack. Null si el producto no es un pack. */
    val cantidadTickets: Int? = null,
    @EncodeDefault val activo: Boolean = true,
    @EncodeDefault val visible: Boolean = true,
    /** Marca de catálogo tipo "Más elegido" / "Mejor valor". */
    @EncodeDefault val destacado: Boolean = false,
    val etiquetaPromocional: String? = null,
    @EncodeDefault val posicion: Int = 1,
    @EncodeDefault val tarifas: List<AdminTarifaProductoDto> = emptyList(),
)

/**
 * Una tarifa concreta de un producto.
 *
 * `periodoFacturacion` (mensual/anual) y `fasePrecio` separan el precio de alta
 * del de renovación, que es como está definido el producto: un plan puede costar
 * 2,99 €/mes el primer año y 4,99 €/mes al renovar. Sin esas dos dimensiones no
 * se puede representar esa tabla de precios sin duplicar productos.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminTarifaProductoDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val productoId: String = "",
    @EncodeDefault val periodoFacturacion: String = "",
    @EncodeDefault val fasePrecio: String = "",
    @EncodeDefault val precioCentimos: Int = 0,
    @EncodeDefault val moneda: String = "EUR",
    @EncodeDefault val activo: Boolean = true,
    @EncodeDefault val visible: Boolean = true,
    @EncodeDefault val posicion: Int = 1,
)

@Serializable
internal data class ProductoComercialRow(
    val id: String = "",
    val codigo: String = "",
    @SerialName("tipo_producto") val tipoProducto: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("precio_centimos") val precioCentimos: Int? = null,
    val moneda: String = "EUR",
    @SerialName("cantidad_tickets") val cantidadTickets: Int? = null,
    val activo: Boolean = true,
    val visible: Boolean = true,
    val destacado: Boolean = false,
    @SerialName("etiqueta_promocional") val etiquetaPromocional: String? = null,
    val posicion: Int = 1,
)

@Serializable
internal data class TarifaProductoRow(
    val id: String = "",
    @SerialName("producto_id") val productoId: String = "",
    @SerialName("periodo_facturacion") val periodoFacturacion: String = "",
    @SerialName("fase_precio") val fasePrecio: String = "",
    @SerialName("precio_centimos") val precioCentimos: Int = 0,
    val moneda: String = "EUR",
    val activo: Boolean = true,
    val visible: Boolean = true,
    val posicion: Int = 1,
)

internal fun ProductoComercialRow.toDto(tarifas: List<AdminTarifaProductoDto>) = AdminProductoComercialDto(
    id = id,
    codigo = codigo,
    tipoProducto = tipoProducto,
    nombre = nombre,
    descripcion = descripcion,
    planId = planId,
    precioCentimos = precioCentimos,
    moneda = moneda,
    cantidadTickets = cantidadTickets,
    activo = activo,
    visible = visible,
    destacado = destacado,
    etiquetaPromocional = etiquetaPromocional,
    posicion = posicion,
    tarifas = tarifas,
)

internal fun TarifaProductoRow.toDto() = AdminTarifaProductoDto(
    id = id,
    productoId = productoId,
    periodoFacturacion = periodoFacturacion,
    fasePrecio = fasePrecio,
    precioCentimos = precioCentimos,
    moneda = moneda,
    activo = activo,
    visible = visible,
    posicion = posicion,
)

/**
 * Comprueba lo que se puede comprobar sin conocer el esquema en vivo: forma,
 * signo del dinero y coherencia interna. Los valores permitidos de los campos de
 * tipo los sigue fijando la base de datos.
 */
internal fun AdminProductoComercialDto.validated(): AdminProductoComercialDto {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

    if (codigo.isBlank()) reject("PRODUCT_CODE_REQUIRED", "El producto necesita un código.")
    if (nombre.isBlank()) reject("PRODUCT_NAME_REQUIRED", "El producto necesita un nombre.")
    if (tipoProducto.isBlank()) reject("PRODUCT_TYPE_REQUIRED", "El producto necesita un tipo.")
    if (moneda.isBlank()) reject("PRODUCT_CURRENCY_REQUIRED", "El producto necesita una moneda.")

    // Un precio negativo pagaría al usuario por comprar.
    if (precioCentimos != null && precioCentimos < 0) {
        reject("PRODUCT_PRICE_NEGATIVE", "El precio no puede ser negativo.")
    }
    // Un pack de 0 tickets cobra sin entregar nada; uno negativo restaría saldo.
    if (cantidadTickets != null && cantidadTickets <= 0) {
        reject("PRODUCT_TICKETS_NOT_POSITIVE", "Un pack debe entregar al menos un OposiTicket.")
    }
    if (posicion < 0) reject("PRODUCT_POSITION_NEGATIVE", "La posición no puede ser negativa.")

    // Destacar un producto oculto no tiene efecto visible y suele ser un
    // despiste: la etiqueta se pone para que alguien la vea.
    if (destacado && !visible) {
        reject(
            "PRODUCT_HIGHLIGHTED_BUT_HIDDEN",
            "Un producto destacado debe ser visible; si no, la etiqueta no se muestra a nadie.",
        )
    }
    return copy(
        codigo = codigo.trim(),
        nombre = nombre.trim(),
        moneda = moneda.trim().uppercase(),
        etiquetaPromocional = etiquetaPromocional?.trim()?.takeIf { it.isNotEmpty() },
        descripcion = descripcion?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun AdminTarifaProductoDto.validated(): AdminTarifaProductoDto {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

    if (periodoFacturacion.isBlank()) {
        reject("TARIFF_PERIOD_REQUIRED", "La tarifa necesita un periodo de facturación.")
    }
    if (fasePrecio.isBlank()) {
        reject("TARIFF_PHASE_REQUIRED", "La tarifa necesita una fase de precio (alta o renovación).")
    }
    if (precioCentimos < 0) reject("TARIFF_PRICE_NEGATIVE", "El precio no puede ser negativo.")
    if (moneda.isBlank()) reject("TARIFF_CURRENCY_REQUIRED", "La tarifa necesita una moneda.")
    if (posicion < 0) reject("TARIFF_POSITION_NEGATIVE", "La posición no puede ser negativa.")

    return copy(
        periodoFacturacion = periodoFacturacion.trim(),
        fasePrecio = fasePrecio.trim(),
        moneda = moneda.trim().uppercase(),
    )
}

/**
 * Dos tarifas activas y visibles para el mismo periodo y la misma fase dejan el
 * precio indefinido: la app cogería la primera que devuelva el orden. Se detecta
 * al guardar en vez de dejar que el usuario final vea un precio aleatorio.
 */
internal fun List<AdminTarifaProductoDto>.findAmbiguousTariff(): AdminTarifaProductoDto? =
    filter { it.activo && it.visible }
        .groupBy { it.periodoFacturacion.lowercase() to it.fasePrecio.lowercase() }
        .values
        .firstOrNull { it.size > 1 }
        ?.get(1)

/** Precio en céntimos formateado para mensajes de confirmación ("12,99 EUR"). */
internal fun formatPrice(priceCents: Int, currency: String): String {
    val sign = if (priceCents < 0) "-" else ""
    val absolute = if (priceCents < 0) -priceCents else priceCents
    val units = absolute / 100
    val cents = absolute % 100
    return "$sign$units,${cents.toString().padStart(2, '0')} ${currency.uppercase()}"
}
