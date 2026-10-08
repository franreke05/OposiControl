package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Administración de planes de suscripción y sus beneficios.
 *
 * `planes_suscripcion` es el catálogo (Gratuito, Ráfagas, Razonado,
 * Inmersivo/Premium) y `beneficios_plan` es un modelo EAV: cada fila es un
 * beneficio con su valor tipado. Esa forma es deliberada — permite añadir un
 * beneficio nuevo sin migración — y es también la razón de que un beneficio
 * tenga cuatro columnas de valor de las que sólo una debe estar rellena.
 *
 * Los precios NO viven aquí: viven en `tarifas_producto_comercial`, indexados por
 * periodo y fase, que es lo que permite representar "2,99 €/mes el primer año y
 * 4,99 € al renovar" sin duplicar el plan. Este fichero no los toca.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPlanDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault val codigo: String = "",
    @EncodeDefault val nombre: String = "",
    val descripcion: String? = null,
    /** Orden de capacidad: un nivel mayor incluye lo que ofrece el menor. */
    @EncodeDefault val nivel: Int = 0,
    @EncodeDefault val activo: Boolean = true,
    @EncodeDefault val visible: Boolean = true,
    @EncodeDefault val posicion: Int = 1,
    @EncodeDefault val beneficios: List<AdminPlanBenefitDto> = emptyList(),
)

/**
 * Un beneficio de plan.
 *
 * Exactamente uno de los cuatro valores debe estar presente: el código del
 * beneficio determina cuál. Rellenar dos deja el beneficio ambiguo y cuál gane
 * dependería de qué lea primero el consumidor.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPlanBenefitDto(
    @EncodeDefault val id: String = "",
    @EncodeDefault @SerialName("plan_id") val planId: String = "",
    @EncodeDefault @SerialName("codigo_beneficio") val codigoBeneficio: String = "",
    @SerialName("valor_boolean") val valorBoolean: Boolean? = null,
    @SerialName("valor_integer") val valorInteger: Int? = null,
    @SerialName("valor_numeric") val valorNumeric: Double? = null,
    @SerialName("valor_text") val valorText: String? = null,
) {
    /** Cuántos valores hay rellenos. Debe ser exactamente 1. */
    val filledValueCount: Int
        get() = listOfNotNull(valorBoolean, valorInteger, valorNumeric, valorText).size
}

@Serializable
internal data class PlanRow(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    val nivel: Int = 0,
    val activo: Boolean = true,
    val visible: Boolean = true,
    val posicion: Int = 1,
)

internal fun PlanRow.toDto(beneficios: List<AdminPlanBenefitDto>) = AdminPlanDto(
    id = id,
    codigo = codigo,
    nombre = nombre,
    descripcion = descripcion,
    nivel = nivel,
    activo = activo,
    visible = visible,
    posicion = posicion,
    beneficios = beneficios,
)

internal fun AdminPlanDto.toRow() = PlanRow(
    id = id,
    codigo = codigo,
    nombre = nombre,
    descripcion = descripcion,
    nivel = nivel,
    activo = activo,
    visible = visible,
    posicion = posicion,
)

internal fun AdminPlanDto.validated(): AdminPlanDto {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

    if (codigo.isBlank()) reject("PLAN_CODE_REQUIRED", "El plan necesita un código.")
    if (nombre.isBlank()) reject("PLAN_NAME_REQUIRED", "El plan necesita un nombre.")
    if (nivel < 0) reject("PLAN_LEVEL_NEGATIVE", "El nivel del plan no puede ser negativo.")
    if (posicion < 0) reject("PLAN_POSITION_NEGATIVE", "La posición no puede ser negativa.")
    // Un plan visible pero inactivo aparece en el catálogo y no se puede
    // contratar: es una vía muerta para el usuario.
    if (visible && !activo) {
        reject(
            "PLAN_VISIBLE_BUT_INACTIVE",
            "Un plan visible debe estar activo; si no, se muestra y no se puede contratar.",
        )
    }
    return copy(codigo = codigo.trim().lowercase(), nombre = nombre.trim())
}

internal fun AdminPlanBenefitDto.validated(): AdminPlanBenefitDto {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

    if (codigoBeneficio.isBlank()) {
        reject("BENEFIT_CODE_REQUIRED", "El beneficio necesita un código.")
    }
    // Se normaliza ANTES de contar. Al revés, un texto de sólo espacios contaba
    // como valor presente, pasaba la validación y se guardaba como null: el
    // beneficio acababa exactamente en el estado sin valor que esto impide.
    val normalised = copy(
        codigoBeneficio = codigoBeneficio.trim(),
        valorText = valorText?.trim()?.takeIf { it.isNotEmpty() },
    )
    // Sin valor el beneficio no dice nada; con dos, cuál gana depende de qué lea
    // primero el consumidor, que es peor que no tenerlo.
    when (normalised.filledValueCount) {
        0 -> reject(
            "BENEFIT_WITHOUT_VALUE",
            "El beneficio necesita exactamente un valor (booleano, entero, numérico o texto).",
        )
        1 -> Unit
        else -> reject(
            "BENEFIT_AMBIGUOUS_VALUE",
            "El beneficio tiene más de un valor relleno; sólo uno puede estarlo.",
        )
    }
    return normalised
}

@Serializable
data class AdminPlansResponse(val data: List<AdminPlanDto>)

@Serializable
data class AdminPlanResponse(val data: AdminPlanDto)

@Serializable
data class AdminPlanBenefitResponse(val data: AdminPlanBenefitDto)

@Serializable
data class AdminPlanBenefitsResponse(val data: List<AdminPlanBenefitDto>)
