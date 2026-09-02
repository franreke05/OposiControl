package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommercialCatalogAdminTest {

    private fun pack(
        codigo: String = "PACK-2600",
        cantidadTickets: Int? = 2600,
        precioCentimos: Int? = 2400,
        destacado: Boolean = true,
        visible: Boolean = true,
        posicion: Int = 2,
    ) = AdminProductoComercialDto(
        codigo = codigo,
        tipoProducto = "pack_tickets",
        nombre = "2.600 OposiTickets",
        precioCentimos = precioCentimos,
        cantidadTickets = cantidadTickets,
        destacado = destacado,
        visible = visible,
        etiquetaPromocional = "Más elegido",
        posicion = posicion,
    )

    private fun tariff(
        periodo: String = "mensual",
        fase: String = "alta",
        precioCentimos: Int = 299,
        activo: Boolean = true,
        visible: Boolean = true,
        id: String = "t-1",
    ) = AdminTarifaProductoDto(
        id = id,
        productoId = "p-1",
        periodoFacturacion = periodo,
        fasePrecio = fase,
        precioCentimos = precioCentimos,
        activo = activo,
        visible = visible,
    )

    // --- producto: forma y dinero ---

    @Test
    fun aValidPackIsAcceptedAndNormalised() {
        val saved = pack(codigo = "  pack-2600  ").copy(moneda = "eur").validated()

        assertEquals("pack-2600", saved.codigo, "El código se recorta.")
        assertEquals("EUR", saved.moneda, "La moneda se normaliza a mayúsculas.")
        assertEquals(2600, saved.cantidadTickets)
    }

    @Test
    fun aNegativePriceIsRejected() {
        // Un precio negativo pagaría al usuario por comprar.
        val failure = assertFailsWith<BackendDomainException> { pack(precioCentimos = -1).validated() }

        assertEquals("PRODUCT_PRICE_NEGATIVE", failure.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
    }

    @Test
    fun aFreeProductIsAllowed() {
        // 0 no es negativo: un producto gratuito es legítimo.
        assertEquals(0, pack(precioCentimos = 0).validated().precioCentimos)
    }

    @Test
    fun aPackThatDeliversNoTicketsIsRejected() {
        // Cobraría sin entregar nada.
        val failure = assertFailsWith<BackendDomainException> { pack(cantidadTickets = 0).validated() }
        assertEquals("PRODUCT_TICKETS_NOT_POSITIVE", failure.errorCode)
    }

    @Test
    fun aPackWithNegativeTicketsIsRejected() {
        // Restaría saldo al comprador.
        assertFailsWith<BackendDomainException> { pack(cantidadTickets = -100).validated() }
    }

    @Test
    fun aProductWithoutTicketsIsAllowedBecauseNotEveryProductIsAPack() {
        assertNull(pack(cantidadTickets = null).validated().cantidadTickets)
    }

    @Test
    fun highlightingAHiddenProductIsRejected() {
        // La etiqueta "Más elegido" sobre algo invisible no la ve nadie: casi
        // siempre es un despiste al desactivar la visibilidad.
        val failure = assertFailsWith<BackendDomainException> {
            pack(destacado = true, visible = false).validated()
        }
        assertEquals("PRODUCT_HIGHLIGHTED_BUT_HIDDEN", failure.errorCode)
    }

    @Test
    fun aHiddenProductThatIsNotHighlightedIsFine() {
        pack(destacado = false, visible = false).validated()
    }

    @Test
    fun requiredTextFieldsAreEnforced() {
        assertEquals(
            "PRODUCT_CODE_REQUIRED",
            assertFailsWith<BackendDomainException> { pack(codigo = "   ").validated() }.errorCode,
        )
        assertEquals(
            "PRODUCT_NAME_REQUIRED",
            assertFailsWith<BackendDomainException> { pack().copy(nombre = "").validated() }.errorCode,
        )
        assertEquals(
            "PRODUCT_TYPE_REQUIRED",
            assertFailsWith<BackendDomainException> { pack().copy(tipoProducto = "").validated() }.errorCode,
        )
    }

    @Test
    fun blankOptionalTextBecomesNullRatherThanEmptyString() {
        // Evita que la app pinte una etiqueta promocional vacía.
        val saved = pack().copy(etiquetaPromocional = "   ", descripcion = "  ").validated()

        assertNull(saved.etiquetaPromocional)
        assertNull(saved.descripcion)
    }

    @Test
    fun productTypeIsNotConstrainedToAGuessedEnum() {
        // Los valores permitidos los fija el CHECK de la tabla. Codificar aquí un
        // enum adivinado rompería el guardado en cuanto se añadiese un tipo.
        pack().copy(tipoProducto = "un_tipo_futuro_todavia_no_conocido").validated()
    }

    // --- tarifas ---

    @Test
    fun aValidTariffIsAcceptedAndNormalised() {
        val saved = tariff(periodo = " anual ", fase = " renovacion ").copy(moneda = "eur").validated()

        assertEquals("anual", saved.periodoFacturacion)
        assertEquals("renovacion", saved.fasePrecio)
        assertEquals("EUR", saved.moneda)
    }

    @Test
    fun aTariffNeedsBothPeriodAndPhase() {
        // Sin fase no se puede distinguir el precio de alta del de renovación,
        // que es justo lo que este modelo existe para representar.
        assertEquals(
            "TARIFF_PERIOD_REQUIRED",
            assertFailsWith<BackendDomainException> { tariff(periodo = "").validated() }.errorCode,
        )
        assertEquals(
            "TARIFF_PHASE_REQUIRED",
            assertFailsWith<BackendDomainException> { tariff(fase = " ").validated() }.errorCode,
        )
    }

    @Test
    fun aNegativeTariffPriceIsRejected() {
        assertEquals(
            "TARIFF_PRICE_NEGATIVE",
            assertFailsWith<BackendDomainException> { tariff(precioCentimos = -50).validated() }.errorCode,
        )
    }

    // --- ambigüedad de tarifas ---

    @Test
    fun twoActiveTariffsForTheSamePeriodAndPhaseAreAmbiguous() {
        // La app cogería la primera del orden: el precio mostrado sería azar.
        val clash = listOf(
            tariff(id = "t-1", precioCentimos = 299),
            tariff(id = "t-2", precioCentimos = 499),
        ).findAmbiguousTariff()

        assertNotNull(clash)
        assertEquals("t-2", clash.id)
    }

    @Test
    fun theSamePeriodWithADifferentPhaseIsNotAmbiguous() {
        // Éste es el caso normal: alta a 2,99 y renovación a 4,99.
        val tariffs = listOf(
            tariff(id = "t-1", fase = "alta", precioCentimos = 299),
            tariff(id = "t-2", fase = "renovacion", precioCentimos = 499),
        )

        assertNull(tariffs.findAmbiguousTariff())
    }

    @Test
    fun differentPeriodsForTheSamePhaseAreNotAmbiguous() {
        val tariffs = listOf(
            tariff(id = "t-1", periodo = "mensual"),
            tariff(id = "t-2", periodo = "anual"),
        )

        assertNull(tariffs.findAmbiguousTariff())
    }

    @Test
    fun anInactiveDuplicateDoesNotCauseAmbiguity() {
        // Es exactamente como se sustituye un precio: desactivar el viejo y
        // crear el nuevo. Bloquearlo haría imposible cambiar una tarifa.
        val tariffs = listOf(
            tariff(id = "t-old", precioCentimos = 299, activo = false),
            tariff(id = "t-new", precioCentimos = 349),
        )

        assertNull(tariffs.findAmbiguousTariff())
    }

    @Test
    fun aHiddenDuplicateDoesNotCauseAmbiguity() {
        val tariffs = listOf(
            tariff(id = "t-old", visible = false),
            tariff(id = "t-new"),
        )

        assertNull(tariffs.findAmbiguousTariff())
    }

    @Test
    fun ambiguityIgnoresCaseDifferencesInPeriodAndPhase() {
        // "Mensual" y "mensual" son el mismo periodo para quien compra.
        val tariffs = listOf(
            tariff(id = "t-1", periodo = "Mensual", fase = "Alta"),
            tariff(id = "t-2", periodo = "mensual", fase = "alta"),
        )

        assertNotNull(tariffs.findAmbiguousTariff())
    }

    // --- formato de precio para confirmaciones ---

    @Test
    fun pricesAreFormattedFromIntegerCents() {
        // El dinero viaja en céntimos enteros; el formateo es sólo presentación.
        assertEquals("12,99 EUR", formatPrice(1299, "EUR"))
        assertEquals("29,99 EUR", formatPrice(2999, "eur"))
        assertEquals("0,00 EUR", formatPrice(0, "EUR"))
    }

    @Test
    fun centsBelowTenKeepTheirLeadingZero() {
        // 1305 son 13,05 y no 13,5.
        assertEquals("13,05 EUR", formatPrice(1305, "EUR"))
    }

    @Test
    fun wholeEurosStillShowTwoDecimals() {
        assertEquals("45,00 EUR", formatPrice(4500, "EUR"))
    }
}
