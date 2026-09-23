package com.oposicontrol.admin.features.rankings.presentation

import com.oposicontrol.admin.domain.model.LeagueLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Formateo de la pantalla de rankings.
 *
 * Un signo perdido o un cero inventado aquí se convierten en una lectura falsa
 * del rendimiento de un opositor, así que estas reglas se fijan con pruebas.
 */
class RankingsFormatTest {

    // ---------- Evolución con signo ----------

    @Test
    fun anImprovementCarriesAnExplicitPlusSign() {
        // Sin el "+", "0,8" se lee igual que una bajada de 0,8.
        assertEquals("+0,8", formatSignedScore(0.8))
        assertEquals("+1,5", formatSignedScore(1.5))
    }

    @Test
    fun aDeclineKeepsItsMinusSign() {
        assertEquals("-0,7", formatSignedScore(-0.7))
        assertEquals("-2,3", formatSignedScore(-2.3))
    }

    @Test
    fun aFlatEvolutionIsMarkedAsMeasuredNotAsMissing() {
        // "±0,0" dice "medido y plano". Un "0,0" a secas se confunde con el hueco.
        assertEquals("±0,0", formatSignedScore(0.0))
        assertNotEquals(NO_DATA_TEXT, formatSignedScore(0.0))
    }

    @Test
    fun aTinyDeclineDoesNotRoundIntoAPlusSign() {
        // -0,04 redondea a cero decimal; el signo debe salir del valor redondeado,
        // no del original, o se mostraría "-0,0" — una bajada que no existe.
        assertEquals("±0,0", formatSignedScore(-0.04))
        assertEquals("±0,0", formatSignedScore(0.04))
    }

    @Test
    fun aDeclineThatRoundsUpToATenthKeepsItsSign() {
        assertEquals("-0,1", formatSignedScore(-0.06))
    }

    @Test
    fun anEvolutionThatHasNotBeenCalculatedIsAGapNotAZero() {
        // La evolución se recalcula por bloques: hasta el primero no hay cifra, y
        // un "±0,0" ahí diría que el opositor se ha estancado sin ser cierto.
        assertEquals(NO_DATA_TEXT, formatSignedScoreOrNoData(null))
        assertEquals("+0,8", formatSignedScoreOrNoData(0.8))
    }

    // ---------- Nota media ----------

    @Test
    fun aScoreIsShownOutOfTenWithOneDecimalAndACommaAsInSpanish() {
        assertEquals("7,4", formatScore(7.4))
        assertEquals("10,0", formatScore(10.0))
        assertEquals("0,0", formatScore(0.0))
    }

    @Test
    fun aScoreRoundsToTheNearestTenth() {
        assertEquals("6,8", formatScore(6.75))
        assertEquals("6,7", formatScore(6.74))
    }

    @Test
    fun aRealZeroScoreIsNotTheSameAsAMissingScore() {
        // Un opositor que falla todo tiene 0,0 y hay que verlo; uno sin nota
        // calculada no tiene ninguna.
        assertEquals("0,0", formatScoreOrNoData(0.0))
        assertEquals(NO_DATA_TEXT, formatScoreOrNoData(null))
    }

    // ---------- Posiciones ----------

    @Test
    fun aPositionIsOrdinalAndAMissingOneIsNeverPaintedAsFirst() {
        assertEquals("1.º", formatPosition(1))
        assertEquals("412.º", formatPosition(412))
        assertEquals(NO_DATA_TEXT, formatPosition(null))
    }

    // ---------- Percentil ----------

    @Test
    fun aPercentileIsShownWithItsUnit() {
        assertEquals("92%", formatPercentile(92.4))
        assertEquals("0%", formatPercentile(0.0))
        assertEquals("100%", formatPercentile(100.0))
    }

    @Test
    fun aPercentileOutsideItsRangeIsTreatedAsCorruptNotShown() {
        // 130% o -5% sólo pueden venir de un cálculo roto; pintarlos les daría
        // una credibilidad que no tienen.
        assertEquals(NO_DATA_TEXT, formatPercentile(130.0))
        assertEquals(NO_DATA_TEXT, formatPercentile(-5.0))
        assertEquals(NO_DATA_TEXT, formatPercentile(null))
    }

    @Test
    fun thePercentileDisclaimerNamesItAsAnInternalComparisonAndDeniesAnyPlacementClaim() {
        // Regla firme de producto: el percentil es comparativa interna, nunca una
        // probabilidad de obtener plaza.
        assertTrue(PERCENTILE_DISCLAIMER.contains("comparativo interno"))
        assertTrue(PERCENTILE_DISCLAIMER.contains("No es una probabilidad oficial de obtener plaza"))
    }

    // ---------- Ventana y conteos ----------

    @Test
    fun theWindowIsExplainedNotJustNumbered() {
        // "50" solo no dice qué mide; la nota media no se puede interpretar sin
        // saber sobre cuántas preguntas está calculada.
        assertEquals("últimas 50 preguntas", formatWindow(50))
        assertEquals("últimas 100 preguntas", formatWindow(100))
        assertEquals(NO_DATA_TEXT, formatWindow(null))
    }

    @Test
    fun aCountOfZeroIsRealAndAMissingCountIsNot() {
        assertEquals("0", formatCount(0))
        assertEquals(NO_DATA_TEXT, formatCount(null))
    }

    // ---------- Total de la página ----------

    @Test
    fun aConfirmedZeroAndAnUnreadableTotalLookDifferent() {
        // Éste es el caso real de hoy: la tabla existe y está vacía.
        assertEquals("0", formatTotal(0))
        assertEquals(NOT_AVAILABLE_TEXT, formatTotal(null))
        assertNotEquals(formatTotal(0), formatTotal(null))
    }

    @Test
    fun theTwoGapMarkersAreDistinguishable() {
        assertNotEquals(NO_DATA_TEXT, NOT_AVAILABLE_TEXT)
    }

    // ---------- Aviso de ventana divergente ----------

    @Test
    fun aLeagueWhoseStoredWindowMatchesTheRuleRaisesNoWarning() {
        assertNull(leagueWindowWarning(league("liga_50", calculationWindow = 50)))
        assertNull(leagueWindowWarning(league("liga_1000", calculationWindow = 100)))
    }

    @Test
    fun aDivergentWindowIsExplainedWithBothNumbersAndItsConsequence() {
        val warning = leagueWindowWarning(league("liga_200", calculationWindow = 50))

        assertTrue(warning != null)
        assertTrue(warning.contains("50"))
        assertTrue(warning.contains("100"))
        assertTrue(warning.contains("no son comparables"))
    }

    @Test
    fun anUnknownLeagueRaisesNoWarningBecauseThereIsNoRuleToContradict() {
        assertNull(leagueWindowWarning(league("liga_2000", calculationWindow = 250)))
    }

    // ---------- Etiqueta de fila ----------

    @Test
    fun aRowFallsBackFromDisplayNameToUsernameToTheId() {
        assertEquals("Usuario Premium Test", rowLabel("Usuario Premium Test", "premium_test", "u-1"))
        assertEquals("premium_test", rowLabel(null, "premium_test", "u-1"))
        assertEquals("premium_test", rowLabel("  ", "premium_test", "u-1"))
        // El id es feo pero es cierto; una fila sin etiqueta no se puede
        // seleccionar ni reportar.
        assertEquals("u-1", rowLabel(null, null, "u-1"))
    }

    private fun league(code: String, calculationWindow: Int) = LeagueLevel(
        id = "liga-$code",
        code = code,
        name = code.replace("liga_", "Liga "),
        minimumQuestions = 200,
        calculationWindow = calculationWindow,
    )
}
