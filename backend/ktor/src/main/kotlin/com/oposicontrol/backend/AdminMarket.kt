package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OposiMarket administrable: packs de OposiTickets, servicios (potenciadores y
 * protectores), promociones y reglas de pop-up comercial.
 *
 * Las cuatro tablas ya existen en la base de datos y están vacías: esto no crea
 * esquema, crea la superficie que faltaba para poblarlas sin tocar código.
 *
 * Dinero siempre en enteros: `precio_centimos` para fiat y `precio_tickets` para
 * OposiTickets. Ninguno admite decimales, y un `Double` acabaría cobrando
 * 9,989999 €.
 */

// ---------- Packs de OposiTickets ----------

@Serializable
data class AdminTicketPackDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    @SerialName("cantidad_tickets") val cantidadTickets: Int = 0,
    @SerialName("precio_centimos") val precioCentimos: Int = 0,
    val moneda: String = "EUR",
    val activo: Boolean = true,
    val posicion: Int = 1,
) {
    /**
     * Céntimos por OposiTicket. Es la cifra que dice si la escala de packs tiene
     * sentido: si un pack grande no sale más barato por ticket que uno pequeño,
     * no hay razón para comprarlo.
     */
    val centimosPorTicket: Double? get() = if (cantidadTickets > 0) precioCentimos.toDouble() / cantidadTickets else null
}

/**
 * Servicio del Market: potenciadores, protectores y servicios futuros.
 *
 * `tipo_servicio` y el resto de valores de catálogo los fija el CHECK de la
 * tabla, así que aquí sólo se valida forma. El multiplicador y la duración viven
 * en `metadatos` del lado de la base de datos cuando procede: no se inventa
 * columna ni se codifica aquí la regla económica de los potenciadores, que sigue
 * siendo decisión de producto.
 */
@Serializable
data class AdminServiceDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    @SerialName("tipo_servicio") val tipoServicio: String = "",
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    @SerialName("precio_centimos") val precioCentimos: Int? = null,
    val activo: Boolean = true,
)

@Serializable
data class AdminPromotionDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("tipo_descuento") val tipoDescuento: String = "",
    val valor: Double = 0.0,
    val activo: Boolean = true,
    @SerialName("valido_desde") val validoDesde: String? = null,
    @SerialName("valido_hasta") val validoHasta: String? = null,
)

/**
 * Regla de pop-up comercial.
 *
 * Existe para que OposiBot no lleve escrito `if (pregunta == 10) mostrarPopup()`:
 * el disparador, la pantalla, el momento, el plan mínimo y la frecuencia son
 * datos, no código.
 */
@Serializable
data class AdminPopupRuleDto(
    val id: String = "",
    val codigo: String = "",
    @SerialName("evento_disparador") val eventoDisparador: String = "",
    val titulo: String = "",
    val mensaje: String = "",
    @SerialName("cta_principal") val ctaPrincipal: String? = null,
    @SerialName("cta_secundario") val ctaSecundario: String? = null,
    val pantalla: String? = null,
    val momento: String? = null,
    @SerialName("requiere_plan_minimo") val requierePlanMinimo: String? = null,
    @SerialName("tipo_examen_objetivo") val tipoExamenObjetivo: String? = null,
    @SerialName("frecuencia_minima_horas") val frecuenciaMinimaHoras: Int? = null,
    val prioridad: Int = 0,
    val activo: Boolean = true,
)

// ---------- Validación ----------

private fun reject(code: String, message: String): Nothing =
    throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

internal fun AdminTicketPackDto.validated(): AdminTicketPackDto {
    if (codigo.isBlank()) reject("PACK_CODE_REQUIRED", "El pack necesita un código.")
    if (nombre.isBlank()) reject("PACK_NAME_REQUIRED", "El pack necesita un nombre.")
    // Un pack de cero tickets cobra sin entregar nada.
    if (cantidadTickets <= 0) {
        reject("PACK_TICKETS_NOT_POSITIVE", "Un pack debe entregar al menos un OposiTicket.")
    }
    if (precioCentimos < 0) reject("PACK_PRICE_NEGATIVE", "El precio no puede ser negativo.")
    if (moneda.isBlank()) reject("PACK_CURRENCY_REQUIRED", "El pack necesita una moneda.")
    if (posicion < 0) reject("PACK_POSITION_NEGATIVE", "La posición no puede ser negativa.")
    return copy(codigo = codigo.trim(), nombre = nombre.trim(), moneda = moneda.trim().uppercase())
}

internal fun AdminServiceDto.validated(): AdminServiceDto {
    if (codigo.isBlank()) reject("SERVICE_CODE_REQUIRED", "El servicio necesita un código.")
    if (nombre.isBlank()) reject("SERVICE_NAME_REQUIRED", "El servicio necesita un nombre.")
    if (tipoServicio.isBlank()) reject("SERVICE_TYPE_REQUIRED", "El servicio necesita un tipo.")
    if (precioTickets != null && precioTickets < 0) {
        reject("SERVICE_TICKET_PRICE_NEGATIVE", "El precio en OposiTickets no puede ser negativo.")
    }
    if (precioCentimos != null && precioCentimos < 0) {
        reject("SERVICE_PRICE_NEGATIVE", "El precio no puede ser negativo.")
    }
    // Un servicio activo sin ningún precio no se puede adquirir de ninguna forma:
    // aparecería en el Market y no habría manera de comprarlo.
    if (activo && precioTickets == null && precioCentimos == null) {
        reject(
            "SERVICE_WITHOUT_PRICE",
            "Un servicio activo necesita precio en OposiTickets o en dinero; si no, no se puede adquirir.",
        )
    }
    return copy(codigo = codigo.trim(), nombre = nombre.trim(), tipoServicio = tipoServicio.trim())
}

internal fun AdminPromotionDto.validated(): AdminPromotionDto {
    if (codigo.isBlank()) reject("PROMO_CODE_REQUIRED", "La promoción necesita un código.")
    if (nombre.isBlank()) reject("PROMO_NAME_REQUIRED", "La promoción necesita un nombre.")
    if (tipoDescuento.isBlank()) reject("PROMO_TYPE_REQUIRED", "La promoción necesita un tipo de descuento.")
    if (valor < 0) reject("PROMO_VALUE_NEGATIVE", "El descuento no puede ser negativo.")
    // Una ventana invertida nunca es válida, así que la promoción no se aplicaría
    // jamás: es siempre un error de captura, no una configuración intencionada.
    if (validoDesde != null && validoHasta != null && validoHasta < validoDesde) {
        reject("PROMO_WINDOW_INVERTED", "La fecha de fin no puede ser anterior a la de inicio.")
    }
    return copy(codigo = codigo.trim().uppercase(), nombre = nombre.trim())
}

internal fun AdminPopupRuleDto.validated(): AdminPopupRuleDto {
    if (codigo.isBlank()) reject("POPUP_CODE_REQUIRED", "La regla necesita un código.")
    if (eventoDisparador.isBlank()) {
        reject("POPUP_TRIGGER_REQUIRED", "La regla necesita un evento disparador.")
    }
    if (titulo.isBlank()) reject("POPUP_TITLE_REQUIRED", "La regla necesita un título.")
    if (mensaje.isBlank()) reject("POPUP_MESSAGE_REQUIRED", "La regla necesita un mensaje.")
    if (frecuenciaMinimaHoras != null && frecuenciaMinimaHoras < 0) {
        reject("POPUP_FREQUENCY_NEGATIVE", "La frecuencia mínima no puede ser negativa.")
    }
    // Sin frecuencia mínima el pop-up puede reaparecer en cada disparo, que es la
    // forma más rápida de que el usuario desinstale. Se exige decidirlo.
    if (activo && frecuenciaMinimaHoras == null) {
        reject(
            "POPUP_FREQUENCY_REQUIRED",
            "Una regla activa necesita una frecuencia mínima en horas; sin ella el pop-up " +
                "puede reaparecer en cada disparo.",
        )
    }
    // Vocabulario canónico: el objetivo sólo puede ser un tipo de examen real.
    if (tipoExamenObjetivo != null && tipoExamenObjetivo !in CANONICAL_EXAM_TYPES) {
        reject(
            "POPUP_EXAM_TYPE_INVALID",
            "El tipo de examen objetivo debe ser uno de: ${CANONICAL_EXAM_TYPES.joinToString(", ")}.",
        )
    }
    return copy(codigo = codigo.trim(), titulo = titulo.trim(), mensaje = mensaje.trim())
}

/** Vocabulario canónico de examen. `guiado` es sólo alias legacy y no se acepta. */
internal val CANONICAL_EXAM_TYPES = setOf("rafaga", "razonado", "inmersivo")

/** Por encima de esto, una lectura correcta pero lenta se reporta como degradada. */
internal const val SUPABASE_SLOW_THRESHOLD_MS = 1500L

// ---------- Envolturas de respuesta ----------

@Serializable
data class AdminTicketPacksResponse(val data: List<AdminTicketPackDto>)

@Serializable
data class AdminTicketPackResponse(val data: AdminTicketPackDto)

@Serializable
data class AdminServicesResponse(val data: List<AdminServiceDto>)

@Serializable
data class AdminServiceResponse(val data: AdminServiceDto)

@Serializable
data class AdminPromotionsResponse(val data: List<AdminPromotionDto>)

@Serializable
data class AdminPromotionResponse(val data: AdminPromotionDto)

@Serializable
data class AdminPopupRulesResponse(val data: List<AdminPopupRuleDto>)

@Serializable
data class AdminPopupRuleResponse(val data: AdminPopupRuleDto)
