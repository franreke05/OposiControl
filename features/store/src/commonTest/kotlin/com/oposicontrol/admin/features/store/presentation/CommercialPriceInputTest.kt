package com.oposicontrol.admin.features.store.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El precio se almacena en céntimos enteros y se teclea como texto. Esta
 * conversión es el único punto donde ambas representaciones se tocan, así que es
 * donde un fallo se convierte en cobrar de más o de menos.
 */
class CommercialPriceInputTest {

    @Test
    fun parsesTheCommaDecimalSeparatorUsedInSpanish() {
        assertEquals(1299, parsePriceToCents("12,99"))
    }

    @Test
    fun alsoParsesADotSeparator() {
        // Copiar y pegar desde una hoja de cálculo trae puntos con frecuencia.
        assertEquals(1299, parsePriceToCents("12.99"))
    }

    @Test
    fun awholeAmountBecomesExactCents() {
        assertEquals(1300, parsePriceToCents("13"))
        assertEquals(4500, parsePriceToCents("45"))
    }

    @Test
    fun aSingleDecimalIsPaddedNotTruncated() {
        // "12,9" son 12,90 y no 12,09: confundirlos cambiaria el precio en 81
        // centimos sin que nadie lo notase en la interfaz.
        assertEquals(1290, parsePriceToCents("12,9"))
    }

    @Test
    fun leadingAndTrailingSpacesAreIgnored() {
        assertEquals(2999, parsePriceToCents("  29,99  "))
    }

    @Test
    fun zeroIsAValidPrice() {
        assertEquals(0, parsePriceToCents("0"))
        assertEquals(0, parsePriceToCents("0,00"))
    }

    @Test
    fun anAmountWithoutIntegerPartIsRead() {
        assertEquals(99, parsePriceToCents(",99"))
    }

    @Test
    fun moreThanTwoDecimalsIsRejectedRatherThanRounded() {
        // Redondear en silencio cambiaria el precio guardado respecto al tecleado.
        assertNull(parsePriceToCents("12,999"))
    }

    @Test
    fun negativeAmountsAreRejected() {
        // Un precio negativo pagaria al usuario por comprar.
        assertNull(parsePriceToCents("-1"))
        assertNull(parsePriceToCents("-12,99"))
    }

    @Test
    fun nonNumericInputIsRejected() {
        assertNull(parsePriceToCents("doce euros"))
        assertNull(parsePriceToCents("12,9x"))
        assertNull(parsePriceToCents("1,2,3"))
        assertNull(parsePriceToCents(""))
        assertNull(parsePriceToCents("   "))
    }

    @Test
    fun anAmountTooLargeForCentsIsRejectedInsteadOfOverflowing() {
        // Sin este limite el valor daria la vuelta y se guardaria un precio
        // negativo o absurdo.
        assertNull(parsePriceToCents("999999999"))
    }

    @Test
    fun centsRoundTripBackToEditableText() {
        assertEquals("12,99", centsToInput(1299))
        assertEquals("13,00", centsToInput(1300))
        assertEquals("0,05", centsToInput(5))
    }

    @Test
    fun editingAndSavingWithoutChangesPreservesTheExactPrice() {
        // Cargar -> mostrar -> volver a guardar no puede desplazar el precio.
        listOf(0, 5, 99, 299, 1299, 2999, 4500, 17999).forEach { cents ->
            assertEquals(cents, parsePriceToCents(centsToInput(cents)), "Roundtrip roto para $cents")
        }
    }

    @Test
    fun costPerTicketKeepsFourDecimalsForComparingPacks() {
        // A 100 OT por euro, la diferencia entre packs vive en la tercera cifra:
        // redondear a dos la borraria justo cuando se necesita para decidir.
        assertEquals("0,9231", formatCentsPerTicket(2400.0 / 2600))
        assertEquals("1,0000", formatCentsPerTicket(1300.0 / 1300))
        assertEquals("0,8654", formatCentsPerTicket(4500.0 / 5200))
    }
}
