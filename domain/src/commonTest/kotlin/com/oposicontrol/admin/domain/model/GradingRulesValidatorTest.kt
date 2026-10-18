package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GradingRulesValidatorTest {
    private fun draft(
        rational: Boolean = false,
        num: String = "0",
        den: String = "1",
        reason: String = "",
        rule: String? = null,
    ) = GradingRulesDraft(rule, rational, num, den, reason)

    @Test
    fun aPlainDraftIsValid() {
        val v = assertIs<GradingValidation.Valid>(GradingRulesValidator.validate(draft(), published = false))
        assertEquals(0 to 1, v.numerator to v.denominator)
    }

    @Test
    fun rationalAndAFixedFractionNeverCoexist() {
        val bad = GradingRulesValidator.validate(draft(rational = true, num = "1", den = "3"), published = false)
        assertEquals(GradingValidation.Field.EXCLUSION, assertIs<GradingValidation.Invalid>(bad).field)
        assertIs<GradingValidation.Valid>(GradingRulesValidator.validate(draft(rational = true), published = false))
    }

    @Test
    fun switchingToRationalClearsTheFractionAndBackLeavesTheBoxOff() {
        val fixed = draft(num = "1", den = "3")
        val rational = GradingRulesValidator.withRational(fixed, true)
        assertEquals("0" to "1", rational.numerator to rational.denominator)
        assertTrue(rational.rationalPenalty)
        assertFalse(GradingRulesValidator.withRational(rational, false).rationalPenalty)
    }

    @Test
    fun fractionBoundsMatchTheServer() {
        listOf("0" to "0", "4" to "3", "-1" to "3", "1" to "101", "a" to "3", "" to "3", "1" to "").forEach { (n, d) ->
            val r = GradingRulesValidator.validate(draft(num = n, den = d), published = false)
            assertEquals(GradingValidation.Field.FRACTION, assertIs<GradingValidation.Invalid>(r, "$n/$d").field, "$n/$d")
        }
        assertIs<GradingValidation.Valid>(GradingRulesValidator.validate(draft(num = "1", den = "3"), published = false))
        assertIs<GradingValidation.Valid>(GradingRulesValidator.validate(draft(num = "100", den = "100"), published = false))
    }

    @Test
    fun aPublishedExamNeedsAReasonOfThreeCharacters() {
        val none = GradingRulesValidator.validate(draft(), published = true)
        assertEquals(GradingValidation.Field.REASON, assertIs<GradingValidation.Invalid>(none).field)
        assertEquals(GradingValidation.Field.REASON, assertIs<GradingValidation.Invalid>(GradingRulesValidator.validate(draft(reason = "ab"), published = true)).field)
        val ok = assertIs<GradingValidation.Valid>(GradingRulesValidator.validate(draft(reason = " Por el tribunal "), published = true))
        assertEquals("Por el tribunal", ok.reason)
    }

    @Test
    fun theReasonIsCappedEvenOnADraft() {
        val r = GradingRulesValidator.validate(draft(reason = "x".repeat(301)), published = false)
        assertEquals(GradingValidation.Field.REASON, assertIs<GradingValidation.Invalid>(r).field)
    }

    @Test
    fun theRationalBoxDefaultsFollowTheOfficialFlag() {
        assertTrue(GradingRulesDraft.rationalDefaultFor(true))
        assertFalse(GradingRulesDraft.rationalDefaultFor(false))
    }

    @Test
    fun annulledRuleOptionsRoundTripAndUnknownMeansDerived() {
        assertEquals(AnnulledRuleOption.REPLACE, AnnulledRuleOption.fromWire("REEMPLAZO_POR_RESERVA"))
        assertEquals(AnnulledRuleOption.NO_SCORE, AnnulledRuleOption.fromWire("NO_PUNTUA"))
        assertEquals(AnnulledRuleOption.DERIVED, AnnulledRuleOption.fromWire(null))
        // PUNTUA_ACIERTO no existe: lo desconocido NO se interpreta como regla explicita
        assertEquals(AnnulledRuleOption.DERIVED, AnnulledRuleOption.fromWire("PUNTUA_ACIERTO"))
    }

    @Test
    fun serverCodesHaveReadableTexts() {
        listOf("REEMPLAZO_NOT_ENOUGH_RESERVES", "RESERVE_ORDER_GAPS", "RESERVE_WITHOUT_MAIN", "RESERVE_BEFORE_MAIN", "DEMO_CONFLICT")
            .forEach { assertFalse(GradingCheckTexts.problem(it).startsWith("El servidor ha detectado")) }
        assertTrue(GradingCheckTexts.problem("OTRO").startsWith("El servidor"))
        assertFalse(GradingCheckTexts.warning("MORE_RESERVES_THAN_MAX").startsWith("Aviso del servidor"))
    }

    @Test
    fun theSummaryDescribesTheRuleWithoutInventingPoints() {
        assertTrue(GradingRulesValidator.penaltySummary(true, 0, 1).contains("1/(n"))
        assertTrue(GradingRulesValidator.penaltySummary(false, 0, 1).startsWith("Sin penalización"))
        assertTrue(GradingRulesValidator.penaltySummary(false, 1, 3).contains("1/3"))
    }
}
