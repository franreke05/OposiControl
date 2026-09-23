package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminMarketTest {

    // ---------- Packs de OposiTickets ----------

    private fun pack(
        codigo: String = "pack-2600",
        cantidadTickets: Int = 2600,
        precioCentimos: Int = 2400,
        posicion: Int = 2,
    ) = AdminTicketPackDto(
        codigo = codigo,
        nombre = "2.600 OposiTickets",
        cantidadTickets = cantidadTickets,
        precioCentimos = precioCentimos,
        posicion = posicion,
    )

    @Test
    fun aValidPackIsNormalised() {
        val saved = pack(codigo = "  pack-2600 ").copy(moneda = "eur").validated()

        assertEquals("pack-2600", saved.codigo)
        assertEquals("EUR", saved.moneda)
    }

    @Test
    fun aPackMustDeliverTickets() {
        // Cobraría sin entregar nada.
        assertEquals(
            "PACK_TICKETS_NOT_POSITIVE",
            assertFailsWith<BackendDomainException> { pack(cantidadTickets = 0).validated() }.errorCode,
        )
        assertEquals(
            "PACK_TICKETS_NOT_POSITIVE",
            assertFailsWith<BackendDomainException> { pack(cantidadTickets = -10).validated() }.errorCode,
        )
    }

    @Test
    fun aPackPriceCannotBeNegative() {
        assertEquals(
            "PACK_PRICE_NEGATIVE",
            assertFailsWith<BackendDomainException> { pack(precioCentimos = -1).validated() }.errorCode,
        )
    }

    @Test
    fun aFreePackIsAllowed() {
        // Cero no es negativo: un pack promocional gratuito es legítimo.
        assertEquals(0, pack(precioCentimos = 0).validated().precioCentimos)
    }

    @Test
    fun costPerTicketMakesThePackLadderComparable() {
        // Es la cifra que dice si comprar el pack grande compensa.
        assertEquals(1.0, pack(cantidadTickets = 1300, precioCentimos = 1300).centimosPorTicket)
        val bigger = pack(cantidadTickets = 5200, precioCentimos = 4500).centimosPorTicket
        assertTrue(bigger != null && bigger < 1.0, "Un pack mayor debería salir más barato por ticket.")
    }

    @Test
    fun costPerTicketIsUnknownRatherThanZeroWhenThereAreNoTickets() {
        // Dividir por cero daría infinito o cero y ambos mienten.
        assertNull(AdminTicketPackDto(cantidadTickets = 0, precioCentimos = 1000).centimosPorTicket)
    }

    // ---------- Servicios (potenciadores y protectores) ----------

    private fun service(
        precioTickets: Int? = 30,
        precioCentimos: Int? = null,
        activo: Boolean = true,
    ) = AdminServiceDto(
        codigo = "potenciador-x2",
        nombre = "Potenciador x2",
        tipoServicio = "potenciador",
        precioTickets = precioTickets,
        precioCentimos = precioCentimos,
        activo = activo,
    )

    @Test
    fun aServicePricedInTicketsIsValid() {
        assertEquals(30, service().validated().precioTickets)
    }

    @Test
    fun aServicePricedInMoneyIsAlsoValid() {
        assertEquals(90, service(precioTickets = null, precioCentimos = 90).validated().precioCentimos)
    }

    @Test
    fun anActiveServiceWithoutAnyPriceIsRejected() {
        // Aparecería en el Market sin forma de adquirirlo.
        assertEquals(
            "SERVICE_WITHOUT_PRICE",
            assertFailsWith<BackendDomainException> {
                service(precioTickets = null, precioCentimos = null).validated()
            }.errorCode,
        )
    }

    @Test
    fun anInactiveServiceWithoutPriceIsAllowedBecauseItIsNotOffered() {
        // Permite preparar un servicio antes de decidir su precio.
        service(precioTickets = null, precioCentimos = null, activo = false).validated()
    }

    @Test
    fun serviceTypeIsNotConstrainedToAGuessedEnum() {
        // Los valores permitidos los fija el CHECK de la tabla.
        service().copy(tipoServicio = "servicio_futuro").validated()
    }

    // ---------- Promociones ----------

    private fun promo(desde: String? = "2026-01-01T00:00:00Z", hasta: String? = "2026-02-01T00:00:00Z") =
        AdminPromotionDto(
            codigo = "verano25",
            nombre = "Verano 25%",
            tipoDescuento = "porcentaje",
            valor = 25.0,
            validoDesde = desde,
            validoHasta = hasta,
        )

    @Test
    fun promotionCodesAreUppercasedSoTheyMatchConsistently() {
        assertEquals("VERANO25", promo().validated().codigo)
    }

    @Test
    fun anInvertedValidityWindowIsRejected() {
        // Nunca se aplicaría: es siempre un error de captura.
        assertEquals(
            "PROMO_WINDOW_INVERTED",
            assertFailsWith<BackendDomainException> {
                promo(desde = "2026-02-01T00:00:00Z", hasta = "2026-01-01T00:00:00Z").validated()
            }.errorCode,
        )
    }

    @Test
    fun anOpenEndedPromotionIsAllowed() {
        promo(hasta = null).validated()
        promo(desde = null, hasta = null).validated()
    }

    @Test
    fun aNegativeDiscountIsRejected() {
        assertEquals(
            "PROMO_VALUE_NEGATIVE",
            assertFailsWith<BackendDomainException> { promo().copy(valor = -5.0).validated() }.errorCode,
        )
    }

    // ---------- Reglas de pop-up ----------

    private fun popup(
        frecuencia: Int? = 24,
        activo: Boolean = true,
        tipoExamen: String? = null,
    ) = AdminPopupRuleDto(
        codigo = "upsell-inmersivo",
        eventoDisparador = "preguntas_respondidas",
        titulo = "Prueba el modo inmersivo",
        mensaje = "Este contenido no está incluido en tu plan actual.",
        frecuenciaMinimaHoras = frecuencia,
        activo = activo,
        tipoExamenObjetivo = tipoExamen,
    )

    @Test
    fun aValidPopupRuleIsAccepted() {
        assertEquals(24, popup().validated().frecuenciaMinimaHoras)
    }

    @Test
    fun anActiveRuleWithoutAFrequencyIsRejected() {
        // Sin frecuencia el pop-up puede reaparecer en cada disparo.
        assertEquals(
            "POPUP_FREQUENCY_REQUIRED",
            assertFailsWith<BackendDomainException> { popup(frecuencia = null).validated() }.errorCode,
        )
    }

    @Test
    fun anInactiveRuleWithoutFrequencyIsAllowedWhileItIsBeingDrafted() {
        popup(frecuencia = null, activo = false).validated()
    }

    @Test
    fun theTargetExamTypeMustUseCanonicalVocabulary() {
        popup(tipoExamen = "rafaga").validated()
        popup(tipoExamen = "razonado").validated()
        popup(tipoExamen = "inmersivo").validated()
    }

    @Test
    fun theLegacyGuiadoAliasIsRejectedAsATargetExamType() {
        // `guiado` es sólo alias legacy; crear configuración nueva con él
        // reintroduciría el vocabulario que se está retirando.
        assertEquals(
            "POPUP_EXAM_TYPE_INVALID",
            assertFailsWith<BackendDomainException> { popup(tipoExamen = "guiado").validated() }.errorCode,
        )
    }

    @Test
    fun aRuleNeedsSomethingToSay() {
        assertEquals(
            "POPUP_TITLE_REQUIRED",
            assertFailsWith<BackendDomainException> { popup().copy(titulo = " ").validated() }.errorCode,
        )
        assertEquals(
            "POPUP_MESSAGE_REQUIRED",
            assertFailsWith<BackendDomainException> { popup().copy(mensaje = "").validated() }.errorCode,
        )
        assertEquals(
            "POPUP_TRIGGER_REQUIRED",
            assertFailsWith<BackendDomainException> { popup().copy(eventoDisparador = "").validated() }.errorCode,
        )
    }
}
