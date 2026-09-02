package com.oposicontrol.admin.features.rankings.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingEntry
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.RankingSection
import com.oposicontrol.admin.domain.model.UserStanding
import com.oposicontrol.admin.domain.repository.RankingsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Comportamiento de la pantalla de rankings.
 *
 * Se prueban las tres cosas que pueden mentirle a quien administra: una lista
 * vacía que no se ha podido comprobar, una paginación que salta o repite filas,
 * y una clasificación que se pudiera tocar.
 *
 * El repositorio falso pagina de verdad (recorta la lista por `offset`/`limit`),
 * así que un salto de página se detecta por las filas devueltas, no sólo por el
 * número que se pidió.
 */
class RankingsViewModelTest {

    private val seededLeagues = listOf(
        league("liga_50", minimum = 50, window = 50, position = 1),
        league("liga_200", minimum = 200, window = 100, position = 2),
        league("liga_500", minimum = 500, window = 100, position = 3),
        league("liga_800", minimum = 800, window = 100, position = 4),
        league("liga_1000", minimum = 1000, window = 100, position = 5),
    )

    @Test
    fun theLeagueCatalogueLoadsAsRealDataWhileTheRankingIsStillEmpty() = runTest {
        // Estado real hoy: `ligas_nivel` sembrada, `estado_competitivo_usuario`
        // aprovisionada y vacía.
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                leagues = AppResult.Success(seededLeagues),
                global = FakeDataset(rows = emptyList(), total = 0),
            ),
        )

        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<UiAsync.Success<List<LeagueLevel>>>(state.leagues)
        assertEquals(5, state.leagues.value.size)
        // Vacío comprobado: la pantalla puede afirmar que nadie compite todavía.
        assertEquals(UiAsync.Empty, state.page)
    }

    @Test
    fun anEmptyRankingWithoutAConfirmedCountIsNotPresentedAsEmpty() = runTest {
        // Sin conteo fiable, decir "no compite nadie" sería inventar el dato que
        // precisamente falta.
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                global = FakeDataset(
                    rows = emptyList(),
                    total = null,
                    unavailableSections = setOf(RankingSection.TOTAL),
                ),
            ),
        )

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.page)
    }

    @Test
    fun rowsAreShownEvenWhenTheirNamesCouldNotBeResolved() = runTest {
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                global = FakeDataset(
                    rows = listOf(entry("u-1")),
                    total = 1,
                    unavailableSections = setOf(RankingSection.NOMBRES),
                ),
            ),
        )

        testScheduler.advanceUntilIdle()

        val page = assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page).value
        assertEquals(1, page.entries.size)
        assertTrue(page.isUnavailable(RankingSection.NOMBRES))
    }

    @Test
    fun theFirstPageIsRequestedWithTheClampedDefaultLimit() = runTest {
        val repository = FakeRankingsRepository(global = classification(120))
        viewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(RankingPageRequest.DEFAULT_LIMIT, repository.globalRequests.single().limit)
        assertEquals(0, repository.globalRequests.single().offset)
        assertTrue(RankingPageRequest.DEFAULT_LIMIT <= RankingPageRequest.MAX_LIMIT)
    }

    @Test
    fun advancingAPageContinuesExactlyWhereTheLastOneEnded() = runTest {
        // Si el salto no coincidiese con el tamaño de página, se perderían u
        // repetirían clasificados entre una página y la siguiente.
        val repository = FakeRankingsRepository(global = classification(120))
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        val first = assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page).value

        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()
        val second = assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page).value

        assertEquals(listOf(0, 50), repository.globalRequests.map { it.offset })
        assertEquals("u-49", first.entries.last().userId)
        assertEquals("u-50", second.entries.first().userId)
        assertEquals(2, second.pageNumber)
    }

    @Test
    fun theNextPageIsRefusedWhenTheTotalCouldNotBeConfirmed() = runTest {
        // Avanzar sin saber el total invitaría a leer una página vacía como el
        // final de la clasificación.
        val repository = FakeRankingsRepository(
            global = FakeDataset(rows = rows(120), total = null, unavailableSections = setOf(RankingSection.TOTAL)),
        )
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.globalRequests.size)
    }

    @Test
    fun theLastPageDoesNotOfferAnotherOne() = runTest {
        val repository = FakeRankingsRepository(global = classification(120))
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        repeat(4) {
            viewModel.dispatch(RankingsIntent.NextPage)
            testScheduler.advanceUntilIdle()
        }

        // 120 filas de 50 en 50: offsets 0, 50 y 100, y ahí se acaba. Los dos
        // avances sobrantes no producen ninguna petición más.
        assertEquals(listOf(0, 50, 100), repository.globalRequests.map { it.offset })
        val last = assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page).value
        assertEquals(20, last.entries.size)
        assertTrue(!last.hasNextPage)
    }

    @Test
    fun steppingBackReturnsToThePreviousPageAndStopsAtTheFirst() = runTest {
        val repository = FakeRankingsRepository(global = classification(120))
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.PreviousPage)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.PreviousPage)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(0, 50, 0), repository.globalRequests.map { it.offset })
    }

    @Test
    fun choosingALeagueRestartsAtItsFirstPage() = runTest {
        // El offset de la clasificación global no significa nada dentro de una
        // liga: mantenerlo enseñaría una página intermedia sin explicación.
        val repository = FakeRankingsRepository(
            leagues = AppResult.Success(seededLeagues),
            global = classification(120),
            league = classification(3),
        )
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SelectLeague("liga-liga_200"))
        testScheduler.advanceUntilIdle()

        assertEquals(RankingScope.LIGA, viewModel.state.value.scope)
        assertEquals("liga-liga_200", repository.leagueRequests.single().first)
        assertEquals(0, repository.leagueRequests.single().second.offset)
    }

    @Test
    fun goingBackToGlobalAlsoRestartsAtTheFirstPage() = runTest {
        val repository = FakeRankingsRepository(
            leagues = AppResult.Success(seededLeagues),
            global = classification(120),
            league = classification(120),
        )
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.SelectLeague("liga-liga_200"))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SelectGlobalScope)
        testScheduler.advanceUntilIdle()

        assertEquals(RankingScope.GLOBAL, viewModel.state.value.scope)
        assertEquals(0, repository.globalRequests.last().offset)
    }

    @Test
    fun aBlankSearchNeverReachesTheRepository() = runTest {
        val repository = FakeRankingsRepository(global = FakeDataset(total = 0))
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SearchQueryChanged("   "))
        viewModel.dispatch(RankingsIntent.Search)
        testScheduler.advanceUntilIdle()

        assertTrue(repository.searchQueries.isEmpty())
        assertEquals(UiAsync.Idle, viewModel.state.value.searchResults)
    }

    @Test
    fun aSearchIsTrimmedAndAlwaysStartsAtItsOwnFirstPage() = runTest {
        val repository = FakeRankingsRepository(
            global = classification(120),
            search = classification(1),
        )
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.NextPage)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SearchQueryChanged("  premium  "))
        viewModel.dispatch(RankingsIntent.Search)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("premium"), repository.searchQueries)
        assertEquals(0, repository.searchRequests.single().offset)
        // Buscar no mueve la clasificación de sitio: son dos listas distintas.
        assertEquals(50, viewModel.state.value.pageRequest.offset)
    }

    @Test
    fun aSearchWithNoMatchesIsEmptyNotAnError() = runTest {
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                global = FakeDataset(total = 0),
                search = FakeDataset(rows = emptyList(), total = 0),
            ),
        )
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SearchQueryChanged("nadie"))
        viewModel.dispatch(RankingsIntent.Search)
        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.searchResults)
    }

    @Test
    fun clearingTheSearchLeavesTheClassificationUntouched() = runTest {
        val repository = FakeRankingsRepository(global = classification(120), search = classification(1))
        val viewModel = viewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(RankingsIntent.SearchQueryChanged("premium"))
        viewModel.dispatch(RankingsIntent.Search)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.ClearSearch)
        testScheduler.advanceUntilIdle()

        assertEquals("", viewModel.state.value.searchQuery)
        assertEquals(UiAsync.Idle, viewModel.state.value.searchResults)
        assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page)
        assertEquals(1, repository.globalRequests.size)
    }

    @Test
    fun aUserWhoDoesNotCompeteYetLoadsWithoutAnError() = runTest {
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                global = FakeDataset(total = 0),
                standing = AppResult.Success(UserStanding(userId = "u-1", displayName = "Usuario Premium Test")),
            ),
        )
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(RankingsIntent.SelectUser("u-1"))
        testScheduler.advanceUntilIdle()

        val standing = assertIs<UiAsync.Success<UserStanding>>(viewModel.state.value.standing).value
        assertEquals("u-1", viewModel.state.value.selectedUserId)
        assertTrue(standing.competitive == null)
        assertTrue(standing.unavailableSections.isEmpty())
    }

    @Test
    fun aFailedLeagueCatalogueDoesNotTakeTheRankingDownWithIt() = runTest {
        // Los nombres de liga son decoración; las posiciones no. Un fallo en el
        // catálogo no debe dejar la pantalla en blanco.
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                leagues = AppResult.Failure(AppError.Network("sin catálogo")),
                global = classification(1),
            ),
        )

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.leagues)
        assertIs<UiAsync.Success<RankingPage>>(viewModel.state.value.page)
    }

    @Test
    fun divergentLeaguesAreSurfacedForTheWarningBanner() = runTest {
        val tampered = seededLeagues.map {
            if (it.code == "liga_1000") it.copy(calculationWindow = 50) else it
        }
        val viewModel = viewModel(
            testScheduler,
            FakeRankingsRepository(
                leagues = AppResult.Success(tampered),
                global = FakeDataset(total = 0),
            ),
        )

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("liga_1000"), viewModel.state.value.divergentLeagues.map { it.code })
    }

    @Test
    fun noIntentCanChangeARankingBecauseNoneExists() {
        // Regla de producto: la clasificación la calcula el producto y el panel la
        // consulta. Este test falla en cuanto alguien añada una intención de
        // escritura, que es la vía por la que una posición podría retocarse.
        val writeVerbs = listOf(
            "Save", "Update", "Delete", "Create", "Set", "Adjust", "Grant",
            "Reset", "Recalculate", "Assign", "Move", "Promote", "Demote",
        )
        val intentNames = listOf(
            RankingsIntent.Load, RankingsIntent.Refresh, RankingsIntent.SelectGlobalScope,
            RankingsIntent.NextPage, RankingsIntent.PreviousPage,
            RankingsIntent.Search, RankingsIntent.ClearSearch,
            RankingsIntent.SelectLeague("liga-1"), RankingsIntent.SearchQueryChanged("q"),
            RankingsIntent.SelectUser("u-1"),
        ).map { it::class.simpleName.orEmpty() }

        assertEquals(10, intentNames.size)
        intentNames.forEach { name ->
            assertTrue(
                writeVerbs.none { name.startsWith(it) },
                "Rankings es de solo lectura: la intención $name parece una escritura.",
            )
        }
    }

    private fun viewModel(
        testScheduler: TestCoroutineScheduler,
        repository: RankingsRepository,
    ) = RankingsViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    private fun league(code: String, minimum: Int, window: Int, position: Int) = LeagueLevel(
        id = "liga-$code",
        code = code,
        name = code.replace("liga_", "Liga "),
        minimumQuestions = minimum,
        calculationWindow = window,
        position = position,
    )

    private fun entry(userId: String) = RankingEntry(userId = userId)

    private fun rows(count: Int) = List(count) { RankingEntry(userId = "u-$it", globalRank = it + 1) }

    /** Clasificación con conteo confirmado: el caso normal cuando haya datos. */
    private fun classification(count: Int) = FakeDataset(rows = rows(count), total = count)
}

/**
 * Conjunto de filas que el falso pagina de verdad.
 *
 * Recortar por `offset`/`limit` en lugar de devolver siempre lo mismo es lo que
 * permite detectar un salto de página que se comiera filas.
 */
private data class FakeDataset(
    val rows: List<RankingEntry> = emptyList(),
    val total: Int? = 0,
    val unavailableSections: Set<String> = emptySet(),
    val failure: AppError? = null,
) {
    fun page(request: RankingPageRequest): AppResult<RankingPage> {
        failure?.let { return AppResult.Failure(it) }
        return AppResult.Success(
            RankingPage(
                entries = rows.drop(request.offset).take(request.limit),
                total = total,
                limit = request.limit,
                offset = request.offset,
                unavailableSections = unavailableSections,
            ),
        )
    }
}

/**
 * Repositorio falso que además registra qué se le pidió: la mitad de estas
 * pruebas comprueban precisamente que no se pide de más.
 */
private class FakeRankingsRepository(
    private val leagues: AppResult<List<LeagueLevel>> = AppResult.Success(emptyList()),
    private val global: FakeDataset = FakeDataset(),
    private val league: FakeDataset = FakeDataset(),
    private val search: FakeDataset = FakeDataset(),
    private val standing: AppResult<UserStanding> = AppResult.Success(UserStanding(userId = "u-1")),
) : RankingsRepository {
    val globalRequests = mutableListOf<RankingPageRequest>()
    val leagueRequests = mutableListOf<Pair<String, RankingPageRequest>>()
    val searchQueries = mutableListOf<String>()
    val searchRequests = mutableListOf<RankingPageRequest>()

    override suspend fun getLeagueLevels(): AppResult<List<LeagueLevel>> = leagues

    override suspend fun getGlobalRanking(page: RankingPageRequest): AppResult<RankingPage> {
        globalRequests += page
        return global.page(page)
    }

    override suspend fun getLeagueRanking(
        leagueLevelId: String,
        page: RankingPageRequest,
    ): AppResult<RankingPage> {
        leagueRequests += leagueLevelId to page
        return league.page(page)
    }

    override suspend fun getUserStanding(userId: String): AppResult<UserStanding> = standing

    override suspend fun searchUsers(query: String, page: RankingPageRequest): AppResult<RankingPage> {
        searchQueries += query
        searchRequests += page
        return search.page(page)
    }
}
