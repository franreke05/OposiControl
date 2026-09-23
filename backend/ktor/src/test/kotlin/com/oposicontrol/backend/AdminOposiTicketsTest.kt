package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Concesión administrativa de OposiTickets.
 *
 * Es dinero: la validación de entrada existe para que una operación irreversible
 * no salga por un descuido de tecleo, y para que ningún movimiento llegue al
 * ledger sin quedar justificado.
 */
class AdminOposiTicketsTest {

    private fun request(
        cantidad: Int = 50,
        motivo: String = "Recompensa OposiTester",
        tipo: String = AdminGrantTicketsRequestDto.TIPO_AJUSTE,
        idempotencyKey: String? = "op-1",
        usuarioId: String = "11111111-2222-3333-4444-555555555555",
    ) = AdminGrantTicketsRequestDto(
        usuarioId = usuarioId,
        cantidad = cantidad,
        motivo = motivo,
        tipo = tipo,
        idempotencyKey = idempotencyKey,
    )

    @Test
    fun aWellFormedGrantIsAccepted() {
        val valid = request().validated()

        assertEquals(50, valid.cantidad)
        assertEquals("op-1", valid.idempotencyKey)
    }

    @Test
    fun theIdempotencyKeyIsMandatory() {
        // Sin ella un reintento o un doble clic concede los tickets dos veces.
        assertEquals(
            "WALLET_IDEMPOTENCY_KEY_REQUIRED",
            assertFailsWith<BackendDomainException> { request(idempotencyKey = null).validated() }.errorCode,
        )
        assertEquals(
            "WALLET_IDEMPOTENCY_KEY_REQUIRED",
            assertFailsWith<BackendDomainException> { request(idempotencyKey = "   ").validated() }.errorCode,
        )
    }

    @Test
    fun aReasonIsMandatory() {
        // Un movimiento sin motivo deja el ledger con algo que nadie puede
        // justificar después, que es la mitad del valor de tener ledger.
        assertEquals(
            "GRANT_REASON_REQUIRED",
            assertFailsWith<BackendDomainException> { request(motivo = "  ").validated() }.errorCode,
        )
    }

    @Test
    fun aZeroGrantIsRejected() {
        assertEquals(
            "GRANT_AMOUNT_ZERO",
            assertFailsWith<BackendDomainException> { request(cantidad = 0).validated() }.errorCode,
        )
    }

    @Test
    fun aNegativeAdjustmentIsAllowedBecauseCorrectionsExist() {
        // Restar es legítimo para corregir una concesión previa equivocada.
        assertEquals(-30, request(cantidad = -30).validated().cantidad)
    }

    @Test
    fun anAbsurdlyLargeGrantIsRejectedInBothDirections() {
        // Red contra el dedo gordo: un cero de más es irreversible sin otra
        // operación manual.
        val tooBig = AdminGrantTicketsRequestDto.MAX_CANTIDAD_POR_OPERACION + 1
        assertEquals(
            "GRANT_AMOUNT_TOO_LARGE",
            assertFailsWith<BackendDomainException> { request(cantidad = tooBig).validated() }.errorCode,
        )
        assertEquals(
            "GRANT_AMOUNT_TOO_LARGE",
            assertFailsWith<BackendDomainException> { request(cantidad = -tooBig).validated() }.errorCode,
        )
    }

    @Test
    fun exactlyTheLimitIsAllowed() {
        val atLimit = AdminGrantTicketsRequestDto.MAX_CANTIDAD_POR_OPERACION
        assertEquals(atLimit, request(cantidad = atLimit).validated().cantidad)
    }

    @Test
    fun onlyTheTypesTheRpcAcceptsAreAllowed() {
        request(tipo = AdminGrantTicketsRequestDto.TIPO_AJUSTE).validated()
        request(tipo = AdminGrantTicketsRequestDto.TIPO_REPORTE_VALIDADO).validated()

        // Un tipo fuera del CHECK del RPC fallaría en Supabase con un error
        // opaco; se detiene antes, con un mensaje que se puede enseñar.
        assertEquals(
            "GRANT_TYPE_INVALID",
            assertFailsWith<BackendDomainException> { request(tipo = "regalo").validated() }.errorCode,
        )
    }

    @Test
    fun aReportRewardCannotBeNegative() {
        // Sería un castigo disfrazado de recompensa y descuadraría el contador
        // de OposiTester.
        assertEquals(
            "GRANT_REWARD_MUST_BE_POSITIVE",
            assertFailsWith<BackendDomainException> {
                request(cantidad = -10, tipo = AdminGrantTicketsRequestDto.TIPO_REPORTE_VALIDADO).validated()
            }.errorCode,
        )
    }

    @Test
    fun validationFailuresAreClientErrorsNotServerErrors() {
        val failure = assertFailsWith<BackendDomainException> { request(motivo = "").validated() }
        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
    }

    // --- idempotencia de la recompensa de OposiTester ---

    @Test
    fun theReportRewardKeyIsDerivedFromTheReportSoRetriesCollide() {
        // Determinista a propósito: resolver el mismo reporte dos veces produce
        // la misma clave, y el RPC devuelve el movimiento original en lugar de
        // crear otro.
        assertEquals(
            opositesterRewardIdempotencyKey("report-42"),
            opositesterRewardIdempotencyKey("report-42"),
        )
    }

    @Test
    fun differentReportsProduceDifferentKeys() {
        assertNotEquals(
            opositesterRewardIdempotencyKey("report-42"),
            opositesterRewardIdempotencyKey("report-43"),
        )
    }

    @Test
    fun theReportRewardKeyIsNamespacedSoItCannotCollideWithOtherOperations() {
        // Sin prefijo, un id de reporte podría coincidir con el de otra operación
        // y bloquear una concesión legítima distinta.
        assertTrue(opositesterRewardIdempotencyKey("42").startsWith("report-reward:"))
    }

    // --- resumen honesto ---

    @Test
    fun sumBasedTotalsAreReportedAsUnavailableRatherThanZero() {
        // PostgREST no expresa SUM; traer todas las carteras para sumarlas se
        // recorta en silencio al llegar al max-rows y devolvería un total menor
        // que el real con pinta de correcto.
        val overview = AdminTicketsOverviewDto(
            carteras = 2,
            unavailableMetrics = listOf(
                AdminTicketsMetricKey.OT_EN_CIRCULACION,
                AdminTicketsMetricKey.OT_EMITIDOS,
                AdminTicketsMetricKey.OT_CONSUMIDOS,
            ),
        )

        assertTrue(AdminTicketsMetricKey.OT_EN_CIRCULACION in overview.unavailableMetrics)
        // Lo que sí es contable se sirve como dato real.
        assertEquals(2, overview.carteras)
    }

    @Test
    fun anUncountableMetricIsNullNotZero() {
        val overview = AdminTicketsOverviewDto()

        assertEquals(null, overview.carteras)
        assertEquals(null, overview.movimientos)
    }
}
