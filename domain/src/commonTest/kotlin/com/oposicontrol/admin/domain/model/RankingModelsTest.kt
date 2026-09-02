package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reglas del dominio de rankings: qué significa una página vacía, hasta dónde
 * puede pedir el cliente, y cuándo la ventana de cálculo de una liga contradice
 * lo que producto definió.
 */
class RankingModelsTest {

    // ---------- Ventana de cálculo por liga ----------

    @Test
    fun theEntryLeagueAveragesFiftyQuestionsAndEveryOtherLeagueOneHundred() {
        assertEquals(50, league("liga_50").expectedCalculationWindow)
        assertEquals(100, league("liga_200").expectedCalculationWindow)
        assertEquals(100, league("liga_500").expectedCalculationWindow)
        assertEquals(100, league("liga_800").expectedCalculationWindow)
        assertEquals(100, league("liga_1000").expectedCalculationWindow)
    }

    @Test
    fun theFiveSeededLeaguesMatchTheProductRule() {
        // Estas son las cinco filas reales de `ligas_nivel`.
        val seeded = listOf(
            league("liga_50", minimumQuestions = 50, calculationWindow = 50),
            league("liga_200", minimumQuestions = 200, calculationWindow = 100),
            league("liga_500", minimumQuestions = 500, calculationWindow = 100),
            league("liga_800", minimumQuestions = 800, calculationWindow = 100),
            league("liga_1000", minimumQuestions = 1000, calculationWindow = 100),
        )

        assertTrue(seeded.none { it.windowDivergesFromRule })
    }

    @Test
    fun aWindowThatContradictsTheRuleIsFlaggedBecauseAveragesStopBeingComparable() {
        val tampered = league("liga_1000", minimumQuestions = 1000, calculationWindow = 50)

        assertTrue(tampered.windowDivergesFromRule)
        assertEquals(100, tampered.expectedCalculationWindow)
    }

    @Test
    fun anUnknownLeagueHasNoRuleAndIsNeverFlagged() {
        val unknown = league("liga_2000", minimumQuestions = 2000, calculationWindow = 250)

        assertNull(unknown.expectedCalculationWindow)
        assertFalse(unknown.windowDivergesFromRule)
    }

    // ---------- Vacío real vs no disponible ----------

    @Test
    fun anEmptyTableWithAConfirmedCountReadsAsGenuinelyEmpty() {
        // Estado real hoy: `estado_competitivo_usuario` aprovisionada y vacía.
        val page = RankingPage(entries = emptyList(), total = 0)

        assertEquals(RankingPageState.EMPTY, page.state)
    }

    @Test
    fun anEmptyPageWithoutACountIsNotCalledEmpty() {
        // Sin conteo fiable no se puede afirmar que no compita nadie.
        val page = RankingPage(entries = emptyList(), total = null)

        assertEquals(RankingPageState.UNAVAILABLE, page.state)
    }

    @Test
    fun aZeroFromAFailedSectionDoesNotOverrideTheFailure() {
        val page = RankingPage(
            entries = emptyList(),
            total = 0,
            unavailableSections = setOf(RankingSection.TOTAL),
        )

        assertEquals(RankingPageState.UNAVAILABLE, page.state)
    }

    @Test
    fun rowsAreShownEvenWhenTheirNamesCouldNotBeResolved() {
        // Una clasificación sin nombres sigue siendo cierta; tirarla entera por un
        // fallo decorativo escondería datos reales.
        val page = RankingPage(
            entries = listOf(entry(userId = "u-1", displayName = null, username = null)),
            total = 1,
            unavailableSections = setOf(RankingSection.NOMBRES),
        )

        assertEquals(RankingPageState.CONTENT, page.state)
        assertTrue(page.isUnavailable(RankingSection.NOMBRES))
        assertNull(page.entries.single().label)
    }

    @Test
    fun theDisplayNameWinsOverTheUsernameAndBlanksDoNotCount() {
        assertEquals("Usuario Premium Test", entry(displayName = "Usuario Premium Test", username = "premium_test").label)
        assertEquals("premium_test", entry(displayName = "   ", username = "premium_test").label)
        assertNull(entry(displayName = null, username = "  ").label)
    }

    @Test
    fun aUserFoundBySearchWhoDoesNotCompeteYetIsStillAResult() {
        val found = entry(userId = "u-1", displayName = "Usuario Premium Test")

        assertTrue(found.hasNoCompetitiveState)
    }

    @Test
    fun aUserWithARealZeroAverageIsNotConfusedWithOneWhoDoesNotCompete() {
        // Una nota media de 0,0 es un dato: significa que falla todo, no que falte
        // la fila. Confundirlos escondería justo al usuario que necesita ayuda.
        val struggling = entry(userId = "u-1").copy(
            leagueLevelId = "liga-id",
            currentAverage = 0.0,
            globalRank = 812,
        )

        assertFalse(struggling.hasNoCompetitiveState)
    }

    @Test
    fun aUserWithoutACompetitiveRowIsNotAnError() {
        val standing = UserStanding(userId = "u-1", displayName = "Usuario Premium Test")

        assertNull(standing.competitive)
        assertFalse(standing.isUnavailable(RankingSection.COMPETITIVO))
    }

    @Test
    fun aUserWhoseCompetitiveRowCouldNotBeReadIsMarkedDifferently() {
        val standing = UserStanding(
            userId = "u-1",
            unavailableSections = setOf(RankingSection.COMPETITIVO),
        )

        assertNull(standing.competitive)
        assertTrue(standing.isUnavailable(RankingSection.COMPETITIVO))
    }

    // ---------- Paginación acotada ----------

    @Test
    fun anAbsurdLimitIsClampedBeforeItEverLeavesTheClient() {
        assertEquals(RankingPageRequest.MAX_LIMIT, RankingPageRequest.of(999_999).limit)
        assertEquals(200, RankingPageRequest.MAX_LIMIT)
    }

    @Test
    fun aZeroOrNegativeLimitFallsBackToTheDefault() {
        // `limit = 0` devolvería una página vacía para siempre y parecería que no
        // compite nadie.
        assertEquals(RankingPageRequest.DEFAULT_LIMIT, RankingPageRequest.of(0).limit)
        assertEquals(RankingPageRequest.DEFAULT_LIMIT, RankingPageRequest.of(-25).limit)
    }

    @Test
    fun aNegativeOffsetBecomesTheFirstPage() {
        assertEquals(0, RankingPageRequest.of(50, -10).offset)
    }

    @Test
    fun steppingBackFromTheFirstPageStaysOnTheFirstPage() {
        val first = RankingPageRequest.of(50, 0)

        assertEquals(0, first.previous().offset)
    }

    @Test
    fun steppingForwardAdvancesByExactlyOnePage() {
        val page = RankingPageRequest.of(25, 50)

        assertEquals(75, page.next().offset)
        assertEquals(25, page.next().limit)
    }

    @Test
    fun steppingForwardFromAClampedLimitAdvancesByTheClampedSize() {
        // Si `next()` avanzase por el límite pedido y no por el aplicado, saltaría
        // filas: la página siguiente empezaría más allá de donde acabó la actual.
        val clamped = RankingPageRequest.of(999_999, 0)

        assertEquals(RankingPageRequest.MAX_LIMIT, clamped.next().offset)
    }

    @Test
    fun thereIsNoNextPageWhenTheTotalIsUnknown() {
        // Ofrecer "siguiente" sin saber el total invita a pedir una página que
        // podría no existir y a presentar el vacío resultante como el final.
        val page = RankingPage(entries = listOf(entry()), total = null, limit = 50, offset = 0)

        assertFalse(page.hasNextPage)
    }

    @Test
    fun theNextPageExistsOnlyWhileRowsRemainBehind() {
        val middle = RankingPage(entries = List(50) { entry(userId = "u-$it") }, total = 120, limit = 50, offset = 0)
        val last = RankingPage(entries = List(20) { entry(userId = "u-$it") }, total = 120, limit = 50, offset = 100)

        assertTrue(middle.hasNextPage)
        assertFalse(last.hasNextPage)
        assertTrue(last.hasPreviousPage)
        assertEquals(3, last.pageNumber)
    }

    private fun league(
        code: String,
        minimumQuestions: Int = 50,
        calculationWindow: Int = 50,
    ) = LeagueLevel(
        id = "liga-$code",
        code = code,
        name = code.replace("liga_", "Liga "),
        minimumQuestions = minimumQuestions,
        calculationWindow = calculationWindow,
    )

    private fun entry(
        userId: String = "u-1",
        displayName: String? = null,
        username: String? = null,
    ) = RankingEntry(userId = userId, displayName = displayName, username = username)
}
