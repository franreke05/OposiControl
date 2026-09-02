package com.oposicontrol.admin.features.users.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Formato de nota y evolución tal y como se presentan al usuario final: nota
 * sobre 10 con un decimal, y la evolución siempre con signo explícito.
 */
class UserDetailFormatTest {

    @Test
    fun scoresUseOneDecimalWithACommaSeparator() {
        assertEquals("7,4", formatScore(7.4))
        assertEquals("10,0", formatScore(10.0))
        assertEquals("0,0", formatScore(0.0))
    }

    @Test
    fun scoresAreRoundedNotTruncated() {
        // 7,46 es 7,5 y no 7,4: truncar sesgaría todas las notas a la baja.
        assertEquals("7,5", formatScore(7.46))
        assertEquals("7,4", formatScore(7.44))
    }

    @Test
    fun evolutionAlwaysCarriesAnExplicitSign() {
        // "+0,8" y "0,8" se leen distinto: el signo es el dato.
        assertEquals("+0,8", formatSignedScore(0.8))
        assertEquals("-0,8", formatSignedScore(-0.8))
    }

    @Test
    fun noChangeIsShownAsPositiveZeroNotAsBlank() {
        assertEquals("+0,0", formatSignedScore(0.0))
    }

    @Test
    fun negativeEvolutionKeepsOneDecimal() {
        assertEquals("-1,2", formatSignedScore(-1.23))
        assertEquals("-2,0", formatSignedScore(-2.0))
    }
}
