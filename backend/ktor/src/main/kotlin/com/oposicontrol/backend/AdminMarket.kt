package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminServiceDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val codigo: String = "",
    @EncodeDefault val nombre: String = "",
    val descripcion: String? = null,
    @EncodeDefault @SerialName("tipo_servicio") val tipoServicio: String = "",
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    @SerialName("precio_centimos") val precioCentimos: Int? = null,
    @EncodeDefault val activo: Boolean = true,
    // F28 (FA52, migracion 20261114400000): escaparate de la app y metadatos de consumible.
    /**
     * `false` = no se vende en la app aunque este activo. Anulable A PROPOSITO, como [posicion]: un panel anterior a F28 no
     * los manda y el upsert `merge-duplicates` conserva entonces el valor guardado (con un valor por defecto aqui, cada
     * guardado de un panel viejo devolveria el servicio a visible y a la posicion 0). En una fila nueva manda el DEFAULT de la base.
     */
    val visible: Boolean? = null,
    /** Orden dentro de su seccion (ascendente). */
    val posicion: Int? = null,
    /** Una de [SERVICE_SECTIONS]; `null` = la base la deriva del tipo (trigger `trg_servicios_seccion`). */
    val seccion: String? = null,
    /** Usos que da UNA compra; `null` = 1 activacion de 1 uso. */
    val usos: Int? = null,
    /** Vida de una activacion desde la compra; `null` = no caduca. */
    @SerialName("duracion_horas") val duracionHoras: Int? = null,
    /** Factor del potenciador sobre la recompensa base del reto diario (1..10). */
    val multiplicador: Double? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPromotionDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val codigo: String = "",
    @EncodeDefault val nombre: String = "",
    @EncodeDefault @SerialName("tipo_descuento") val tipoDescuento: String = "",
    @EncodeDefault val valor: Double = 0.0,
    @EncodeDefault val activo: Boolean = true,
    @SerialName("valido_desde") val validoDesde: String? = null,
    @SerialName("valido_hasta") val validoHasta: String? = null,
    /** D6-20: usos registrados de la promocion. Solo lectura (lo rellena el listado); null en la respuesta de un guardado. */
    val usos: Int? = null,
)

/**
 * Regla de pop-up comercial.
 *
 * Existe para que OposiBot no lleve escrito `if (pregunta == 10) mostrarPopup()`:
 * el disparador, la pantalla, el momento, el plan mínimo y la frecuencia son
 * datos, no código.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPopupRuleDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val codigo: String = "",
    @EncodeDefault @SerialName("evento_disparador") val eventoDisparador: String = "",
    @EncodeDefault val titulo: String = "",
    @EncodeDefault val mensaje: String = "",
    @SerialName("cta_principal") val ctaPrincipal: String? = null,
    @SerialName("cta_secundario") val ctaSecundario: String? = null,
    val pantalla: String? = null,
    val momento: String? = null,
    @SerialName("requiere_plan_minimo") val requierePlanMinimo: String? = null,
    @SerialName("tipo_examen_objetivo") val tipoExamenObjetivo: String? = null,
    @SerialName("frecuencia_minima_horas") val frecuenciaMinimaHoras: Int? = null,
    @EncodeDefault val prioridad: Int = 0,
    @EncodeDefault val activo: Boolean = true,
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
    // F28: los mismos limites que los CHECK de la migracion 20261114400000, dichos antes de llegar a la base.
    // (El tipo NO se valida aqui a proposito: lo fija el CHECK de la tabla, ver AdminMarketTest.serviceTypeIsNotConstrainedToAGuessedEnum.)
    val section = seccion?.trim()?.uppercase()?.ifEmpty { null }
    if (section != null && section !in SERVICE_SECTIONS) {
        reject("SERVICE_SECTION_INVALID", "La sección debe ser una de: ${SERVICE_SECTIONS.joinToString(", ")}.")
    }
    if (posicion != null && posicion < 0) reject("SERVICE_POSITION_NEGATIVE", "La posición no puede ser negativa.")
    if (usos != null && usos !in 1..100) reject("SERVICE_USES_OUT_OF_RANGE", "Los usos por compra van de 1 a 100.")
    if (duracionHoras != null && duracionHoras !in 1..8760) {
        reject("SERVICE_DURATION_OUT_OF_RANGE", "La duración va de 1 a 8760 horas (un año).")
    }
    if (multiplicador != null && (multiplicador.isNaN() || multiplicador < 1.0 || multiplicador > 10.0)) {
        reject("SERVICE_MULTIPLIER_OUT_OF_RANGE", "El multiplicador va de 1 a 10.")
    }
    // Como mucho dos decimales (el panel admite p. ej. 2,5 o 2,25): el reto aplica floor(base x m), un factor con mas decimales no se puede leer en el panel.
    if (multiplicador != null && kotlin.math.abs(multiplicador * 100 - kotlin.math.round(multiplicador * 100)) > 1e-6) {
        reject("SERVICE_MULTIPLIER_PRECISION", "El multiplicador admite como mucho dos decimales.")
    }
    // Un potenciador ACTIVO sin multiplicador cobraria OT por un x1 en el reto (la base aplica coalesce a 1): se rechaza. Inactivo se puede preparar sin el.
    if (activo && tipoServicio.trim() == "potenciador" && multiplicador == null) {
        reject("SERVICE_MULTIPLIER_REQUIRED", "Un potenciador activo necesita multiplicador.")
    }
    return copy(codigo = codigo.trim(), nombre = nombre.trim(), tipoServicio = tipoServicio.trim(), seccion = section)
}


/** CHECK `servicios_seccion_check` (F28): las tres secciones del Market que decidio el propietario. */
internal val SERVICE_SECTIONS = listOf("OPOSITICKETS", "POTENCIADORES", "SERVICIOS")

internal fun AdminPromotionDto.validated(): AdminPromotionDto {
    if (codigo.isBlank()) reject("PROMO_CODE_REQUIRED", "La promoción necesita un código.")
    if (nombre.isBlank()) reject("PROMO_NAME_REQUIRED", "La promoción necesita un nombre.")
    if (tipoDescuento.isBlank()) reject("PROMO_TYPE_REQUIRED", "La promoción necesita un tipo de descuento.")
    if (valor < 0) reject("PROMO_VALUE_NEGATIVE", "El descuento no puede ser negativo.")
    // El mismo vocabulario que el CHECK de `promociones.tipo_descuento`: sin esto un valor desconocido llegaba a la base
    // y volvia como un error de upstream en vez de un mensaje que se pueda ensenar en el formulario.
    if (tipoDescuento.trim() !in PROMOTION_DISCOUNT_TYPES) {
        reject("PROMO_TYPE_INVALID", "El tipo de descuento debe ser uno de: ${PROMOTION_DISCOUNT_TYPES.joinToString(", ")}.")
    }
    // Mas del 100 % no es un descuento: es pagar por llevarselo.
    if (tipoDescuento.trim() == "porcentaje" && valor > 100) {
        reject("PROMO_PERCENT_OVER_100", "Un descuento en porcentaje no puede superar el 100 %.")
    }
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
    // El mismo vocabulario que el CHECK de `reglas_popup_comercial.momento`.
    if (momento != null && momento !in POPUP_MOMENTS) {
        reject("POPUP_MOMENT_INVALID", "El momento debe ser uno de: ${POPUP_MOMENTS.joinToString(", ")}.")
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

/** Los tres valores que admite `promociones_tipo_descuento_check`. */
internal val PROMOTION_DISCOUNT_TYPES = setOf("porcentaje", "cantidad", "tickets_extra")

/** Los cinco valores que admite `reglas_popup_comercial_momento_check` (o nulo: en cualquier momento). */
internal val POPUP_MOMENTS = setOf("antes", "durante", "despues", "home", "bloqueo")

/** Valor por defecto de `reglas_popup_comercial.frecuencia_minima_horas` en la base de datos. */
internal const val POPUP_DEFAULT_FREQUENCY_HOURS = 24

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
