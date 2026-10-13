package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * Concesión administrativa de OposiTickets.
 *
 * Toda la economía pasa por `admin_grant_tickets`, que es atómico, idempotente y
 * sólo ejecutable por `service_role`. Este fichero no toca saldos: sólo valida y
 * da forma a la petición antes de delegar.
 *
 * La regla que sostiene todo esto: **nunca `saldo = saldo + X` desde la
 * aplicación**. Ya hubo una ruta que lo hacía y se cerró con 410; el ledger y el
 * saldo tienen que moverse en la misma transacción o no moverse.
 */
@Serializable
data class AdminGrantTicketsRequestDto(
    val usuarioId: String = "",
    /** Entero con signo: negativo es una corrección a la baja, nunca un pago. */
    val cantidad: Int = 0,
    val motivo: String = "",
    /**
     * Tipo de movimiento. Debe ser uno de los que admite el CHECK del RPC
     * (`ajuste` manual, `reporte_validado` para recompensa de OposiTester).
     */
    val tipo: String = TIPO_AJUSTE,
    /**
     * Obligatoria. Es lo único que impide que un reintento —o un doble clic—
     * conceda los tickets dos veces.
     */
    val idempotencyKey: String? = null,
    /** Referencia opcional al origen: id de reporte, de compra, de incidencia. */
    val referencia: String? = null,
) {
    companion object {
        const val TIPO_AJUSTE = "ajuste"
        const val TIPO_REPORTE_VALIDADO = "reporte_validado"

        /** Los únicos tipos que el RPC canónico acepta. */
        val TIPOS_PERMITIDOS = setOf(TIPO_AJUSTE, TIPO_REPORTE_VALIDADO)

        /**
         * Tope de la recompensa que se configura para un reporte validado.
         *
         * Un AJUSTE MANUAL ya no tiene tope de cantidad (decisión del propietario, 2026-10-13): el único límite es el
         * entero de 32 bits de la cartera, y la red contra el cero de más es la confirmación del panel. La recompensa de
         * un reporte sí lo conserva, porque no la teclea nadie cada vez: es un valor que se aplica solo.
         */
        const val MAX_RECOMPENSA_POR_REPORTE = 10_000

        /** MASTER5-API-001: tope de longitud del motivo (descripcion del ledger) y de la clave de idempotencia. */
        const val MAX_MOTIVO_LENGTH = 1_000
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
    }
}

@Serializable
data class AdminGrantTicketsResultDto(
    val usuarioId: String,
    val cantidad: Int,
    val saldoResultante: Int,
    val tipo: String,
    val idempotencyKey: String,
)

/**
 * Valida la petición antes de llegar al RPC.
 *
 * El RPC vuelve a validar todo esto —es la autoridad—, pero fallar aquí da un
 * mensaje que se puede enseñar en el formulario en vez de un error de upstream.
 */
internal fun AdminGrantTicketsRequestDto.validated(): AdminGrantTicketsRequestDto {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)

    if (usuarioId.isBlank()) reject("GRANT_USER_REQUIRED", "Falta el usuario destino.")
    if (cantidad == 0) {
        reject("GRANT_AMOUNT_ZERO", "Una concesión de cero OposiTickets no registra nada útil.")
    }
    // Sin tope de cantidad: `cantidad` es un Int y eso ya es el límite físico de la cartera. Si el saldo resultante no
    // cabe, lo rechaza el RPC, que es quien lo conoce.
    // Sin motivo, el ledger queda con un movimiento que nadie puede justificar
    // después. La trazabilidad es la mitad del valor de tener ledger.
    if (motivo.isBlank()) reject("GRANT_REASON_REQUIRED", "La concesión necesita un motivo.")
    if (motivo.trim().length > AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH) {
        reject("GRANT_REASON_TOO_LONG", "El motivo no puede superar ${AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH} caracteres.")
    }
    if (tipo !in AdminGrantTicketsRequestDto.TIPOS_PERMITIDOS) {
        reject(
            "GRANT_TYPE_INVALID",
            "Tipo no permitido. Usa ${AdminGrantTicketsRequestDto.TIPOS_PERMITIDOS.joinToString(" o ")}.",
        )
    }
    // Una recompensa de reporte no puede restar: sería un castigo disfrazado de
    // recompensa y descuadraría el contador de OposiTester.
    if (tipo == AdminGrantTicketsRequestDto.TIPO_REPORTE_VALIDADO && cantidad < 0) {
        reject("GRANT_REWARD_MUST_BE_POSITIVE", "Una recompensa de reporte no puede ser negativa.")
    }
    val key = idempotencyKey?.trim().orEmpty()
    if (key.isEmpty()) {
        reject(
            "WALLET_IDEMPOTENCY_KEY_REQUIRED",
            "Falta la clave de idempotencia: sin ella un reintento concedería los tickets dos veces.",
        )
    }
    if (key.length > AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH) {
        reject(
            "WALLET_IDEMPOTENCY_KEY_INVALID",
            "La clave de idempotencia no puede superar ${AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH} caracteres.",
        )
    }
    return copy(motivo = motivo.trim(), idempotencyKey = key, referencia = referencia?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * Clave de idempotencia determinista para la recompensa de un reporte.
 *
 * Determinista a propósito: si el mismo reporte se resuelve dos veces —por doble
 * clic o por reintento de red— la segunda llamada choca con la misma clave y el
 * RPC devuelve el movimiento original en lugar de crear otro.
 */
internal fun opositesterRewardIdempotencyKey(reportId: String): String = "report-reward:$reportId"

/**
 * Resumen agregado de la economía de OposiTickets.
 *
 * Los campos son nullable porque cada uno depende de un conteo que puede no
 * poder calcularse; un cero inventado aquí se leería como "no hay tickets en
 * circulación", que es una afirmación muy distinta de "no lo sé".
 */
@Serializable
data class AdminTicketsOverviewDto(
    val carteras: Int? = null,
    val movimientos: Int? = null,
    val movimientosCredito: Int? = null,
    val movimientosDebito: Int? = null,
    val unavailableMetrics: List<String> = emptyList(),
)

/** Claves de métrica del resumen de OposiTickets. */
object AdminTicketsMetricKey {
    const val CARTERAS = "carteras"
    const val MOVIMIENTOS = "movimientos"
    const val MOVIMIENTOS_CREDITO = "movimientosCredito"
    const val MOVIMIENTOS_DEBITO = "movimientosDebito"

    /**
     * OT en circulación = suma de todos los saldos. PostgREST no expresa SUM, y
     * sumarlo trayendo todas las carteras se recortaría en silencio al llegar al
     * `max-rows` del servidor, devolviendo un total menor que el real sin avisar.
     * Necesita un read model o un RPC agregado.
     */
    const val OT_EN_CIRCULACION = "otEnCirculacion"

    /** Igual que el anterior: exige SUM sobre `transacciones_cartera`. */
    const val OT_EMITIDOS = "otEmitidos"
    const val OT_CONSUMIDOS = "otConsumidos"
}

@Serializable
data class AdminGrantTicketsResponse(val data: AdminGrantTicketsResultDto)

@Serializable
data class AdminTicketsOverviewResponse(val data: AdminTicketsOverviewDto)

/**
 * MASTER5-API-001: validacion de magnitud/longitud para TODO llamador del RPC `admin_grant_tickets` (rutas legacy
 * `/usuarios/{id}/tickets` y `/tickets/{id}/transacciones`, recompensa de reporte y la ruta canonica), que antes
 * solo cubria `validated()`. El RPC no impone tope de longitud. La magnitud ya no se acota aqui (solo se rechaza el
 * cero): ver [AdminGrantTicketsRequestDto.MAX_RECOMPENSA_POR_REPORTE].
 */
internal fun requireWalletGrantWithinBounds(cantidad: Int, motivo: String, idempotencyKey: String) {
    fun reject(code: String, message: String): Nothing =
        throw BackendDomainException(code, HttpStatusCode.UnprocessableEntity, message)
    if (cantidad == 0) reject("WALLET_AMOUNT_INVALID", "La cantidad debe ser distinta de cero.")
    if (motivo.isBlank() || motivo.trim().length > AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH) {
        reject("WALLET_DESCRIPTION_REQUIRED", "El motivo es obligatorio y no puede superar ${AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH} caracteres.")
    }
    if (idempotencyKey.length > AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH) {
        reject("WALLET_IDEMPOTENCY_KEY_INVALID", "La clave de idempotencia no puede superar ${AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH} caracteres.")
    }
}
