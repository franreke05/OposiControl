package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductMetricsModelsTest {
    @Test
    fun repeatedAnswerDoesNotChangeCompetitiveMean() {
        val one = (0 until 50).map { CompetitiveAnswer("q$it", it % 2 == 0) }
        val repeated = one + CompetitiveAnswer("q0", false, attemptEligible = true, isFirstRealResponse = false)
        assertEquals(5.0, CompetitiveMetrics.snapshot(one)?.score)
        assertEquals(5.0, CompetitiveMetrics.snapshot(repeated)?.score)
    }

    @Test
    fun rankingEligibilityRequiresFirstRealResponse() {
        val repeatedOnly = listOf(
            CompetitiveAnswer("q1", correct = true, attemptEligible = true, isFirstRealResponse = false),
        )
        assertNull(CompetitiveMetrics.snapshot(repeatedOnly))
    }

    @Test
    fun leagueWindowsAndTransitionsAreExact() {
        fun answers(count: Int) = (0 until count).map { CompetitiveAnswer("q$it", true) }
        assertEquals("50", CompetitiveMetrics.snapshot(answers(50))?.leagueCode)
        assertEquals(50, CompetitiveMetrics.snapshot(answers(200))?.windowSize)
        assertEquals("200", CompetitiveMetrics.snapshot(answers(201))?.leagueCode)
        assertEquals("1000", CompetitiveMetrics.snapshot(answers(1001))?.leagueCode)
    }

    @Test
    fun evolutionUpdatesOnlyAfterTwentyNewQuestions() {
        val state = EvolutionState(5.0, 0.0, 0)
        val pending = CompetitiveMetrics.updateEvolution(state, 50, 69, 6.0)
        assertEquals(19, pending.questionsSinceReference)
        val updated = CompetitiveMetrics.updateEvolution(pending, 69, 70, 6.0)
        assertEquals(0, updated.questionsSinceReference)
        assertEquals(6.0, updated.referenceScore)
        assertEquals(1.0, updated.evolution)
    }

    @Test
    fun tagAggregationIncludesUnansweredAndTime() {
        val aggregate = TagMetrics.aggregate(
            listOf(
                AnswerOutcome.CORRECT to 4,
                AnswerOutcome.INCORRECT to 8,
                AnswerOutcome.UNANSWERED to 0,
            ),
        )
        assertEquals(3, aggregate.total)
        assertEquals(12, aggregate.totalTimeSeconds)
        assertEquals(1.0 / 3.0, aggregate.failureRate)
    }

    @Test
    fun gradeBoundariesAreCanonical() {
        assertEquals("No suficiente", ExamGrade.verbal(4.99))
        assertEquals("Suficiente", ExamGrade.verbal(5.0))
        assertEquals("Bien", ExamGrade.verbal(6.0))
        assertEquals("Notable", ExamGrade.verbal(7.0))
        assertEquals("Sobresaliente", ExamGrade.verbal(9.0))
    }

    @Test
    fun difficultyUsesMedianAndFivePointScale() {
        assertEquals(15.0, DifficultyMetrics.median(listOf(10, 20)))
        assertEquals(2, DifficultyMetrics.failurePoints(21, 100))
        assertTrue(DifficultyMetrics.timePoints(50.0, listOf(10.0, 20.0, 30.0, 40.0, 50.0)) in 1..5)
    }

    @Test
    fun ticketLedgerRequiresBeforeAfterContinuityAndIdempotency() {
        val valid = listOf(
            TicketMovement("1", 40, 0, 40, "daily-1"),
            TicketMovement("2", -10, 40, 30, "purchase-1"),
        )
        assertTrue(TicketLedger.isConsistent(valid, 30))
        assertFalse(TicketLedger.isConsistent(valid + TicketMovement("3", 1, 29, 30), 30))
        assertFalse(TicketLedger.isConsistent(valid + TicketMovement("4", 1, 30, 31, "daily-1"), 31))
    }

    @Test
    fun eventIdsAreIdempotentAndInvalidVersionsAreIgnored() {
        val deduplicated = EventIdempotency.deduplicate(
            listOf(VersionedEvent("a", 1), VersionedEvent("a", 1), VersionedEvent("b", 0)),
        )
        assertEquals(listOf(VersionedEvent("a", 1)), deduplicated)
    }

    @Test
    fun firstResponseIdentityIsIndependentFromRankingEligibility() {
        val rows = listOf(
            FirstResponseRecord("u", "q", competitiveEligible = false, isFirstRealResponse = true),
            FirstResponseRecord("u", "q", competitiveEligible = true, isFirstRealResponse = false),
        )
        assertTrue(FirstResponseIntegrity.hasAtMostOneFirstResponse(rows))
        assertFalse(
            FirstResponseIntegrity.hasAtMostOneFirstResponse(
                rows + FirstResponseRecord("u", "q", competitiveEligible = true, isFirstRealResponse = true),
            ),
        )
    }

    @Test
    fun answerRetryAndConcurrentCandidatesUseOneFirstResponseWinner() {
        val existing = listOf(FirstResponseRecord("u", "q", false, true))
        assertFalse(FirstResponseIntegrity.shouldMarkFirst(existing, "u", "q"))

        val concurrentCandidates = listOf(
            FirstResponseRecord("u", "q", false, true),
            FirstResponseRecord("u", "q", true, true),
        )
        val persistedAfterUniqueIndex = listOf(concurrentCandidates.first())
        assertTrue(FirstResponseIntegrity.hasAtMostOneFirstResponse(persistedAfterUniqueIndex))
    }

    @Test
    fun unitPurchaseIsSeparateFromPlanEntitlementAndExpires() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val expired = UnitContentPurchase(ExamType.INMERSIVO, Instant.parse("2026-08-19T00:00:00Z"))
        assertFalse(ContentAccess.canAccess(ExamType.INMERSIVO, emptySet(), expired, now))
        assertTrue(ContentAccess.canAccess(ExamType.INMERSIVO, emptySet(), UnitContentPurchase(ExamType.INMERSIVO, Instant.parse("2026-08-21T00:00:00Z")), now))
        assertTrue(ContentAccess.canAccess(ExamType.RAZONADO, setOf(CanonicalEntitlementPolicy.RAZONADO_BENEFIT), null, now))
    }

    @Test
    fun capabilitiesUseCanonicalRazonadoVocabulary() {
        val capabilities = SpecialtyCapabilities(true, true, false, true, false)
        assertTrue(SpecialtyAccess.allows(capabilities, ExamType.RAZONADO))
        assertFalse(SpecialtyAccess.allows(capabilities, ExamType.INMERSIVO))
        assertNull(CanonicalExamTypeNormalizer.normalizeExam("guiadooo"))
    }
}
