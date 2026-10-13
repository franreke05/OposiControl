package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditMovement
import com.oposicontrol.admin.domain.model.AuditPage
import com.oposicontrol.admin.domain.repository.AuditRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {
    private val baja = "cuenta_dada_de_baja"
    private val alta = "cuenta_reactivada"

    private fun movement(id: String, action: String = baja, actorId: String? = "u-ana", actorName: String? = "Ana García") =
        AuditMovement(id, actorId, actorName, action, "perfiles_usuario", "r-$id", "2026-10-13T12:34:56+00:00")

    private fun detail(id: String) = AuditDetail(id, before = listOf(AuditField("estado_cuenta", "activa")), after = listOf(AuditField("estado_cuenta", "bloqueada")))

    private fun AuditState.shownIds(): List<String> = assertIs<UiAsync.Success<List<AuditMovement>>>(movements).value.map { it.id }

    // ------------------------------------------------------------------------------------------------ carga

    @Test
    fun `opening the screen loads the first page unfiltered, opens the first movement and asks for no detail`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1"), movement("m2", actorId = null, actorName = null)), nextCursor = "c1"))
        val vm = viewModel(repository)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("m1", "m2"), state.shownIds())
        assertEquals("m1", state.selectedId)
        assertEquals("c1", state.nextCursor)
        assertEquals(listOf<Pair<AuditFilter, String?>>(AuditFilter() to null), repository.listCalls.toList())
        // El antes y el después no se piden hasta que alguien los pide.
        assertEquals(UiAsync.Idle, state.detail)
        assertTrue(repository.detailCalls.isEmpty())
        // Las personas del filtro salen de lo cargado; el sistema no es una persona.
        assertEquals(listOf(AuditActorOption("u-ana", "Ana García")), state.actorOptions)
    }

    @Test
    fun `an empty log says empty and a failed read says error`() = runTest {
        val empty = viewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, empty.state.value.movements)
        assertNull(empty.state.value.selectedId)
        assertFalse(empty.state.value.hasFilters)

        val broken = viewModel(FakeRepository(failList = true))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(broken.state.value.movements)
    }

    @Test
    fun `refreshing keeps what is on screen and the open movement until the answer arrives`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1"), movement("m2")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(AuditIntent.Select("m2"))
        advanceUntilIdle()

        repository.firstPage = AuditPage(listOf(movement("m3"), movement("m2"), movement("m1")), null)
        val gate = repository.holdNextList()
        vm.dispatch(AuditIntent.Refresh)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.state.value.shownIds(), "releer no vacía el catálogo")
        assertEquals("m2", vm.state.value.selectedId)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("m3", "m2", "m1"), vm.state.value.shownIds())
        assertEquals("m2", vm.state.value.selectedId, "el movimiento abierto sigue abierto")
    }

    // ------------------------------------------------------------------------------------------------ filtros

    @Test
    fun `action, person and dates travel to the repository and each one asks again`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()
        repository.listCalls.clear()

        vm.dispatch(AuditIntent.SelectAction(baja))
        advanceUntilIdle()
        vm.dispatch(AuditIntent.SelectActor("u-ana"))
        advanceUntilIdle()
        vm.dispatch(AuditIntent.UpdateFromDate("2026-10-01"))
        advanceUntilIdle()
        vm.dispatch(AuditIntent.UpdateUntilDate("2026-10-13"))
        advanceUntilIdle()

        assertEquals(
            listOf(
                AuditFilter(action = baja),
                AuditFilter(action = baja, actorId = "u-ana"),
                AuditFilter(action = baja, actorId = "u-ana", from = "2026-10-01T00:00:00Z"),
                // «Hasta el 13» incluye el 13: viaja el primer instante del 14, que el servidor trata como exclusivo.
                AuditFilter(action = baja, actorId = "u-ana", from = "2026-10-01T00:00:00Z", until = "2026-10-14T00:00:00Z"),
            ),
            repository.listCalls.map { it.first },
        )
        assertTrue(repository.listCalls.all { it.second == null }, "un filtro nuevo empieza por la primera página")
        assertTrue(vm.state.value.hasServerFilters)

        // Elegir lo que ya estaba elegido no vuelve al servidor.
        vm.dispatch(AuditIntent.SelectAction(baja))
        advanceUntilIdle()
        assertEquals(4, repository.listCalls.size)
    }

    @Test
    fun `a date being typed, impossible or reversed sends nothing`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()
        repository.listCalls.clear()

        "2026-10-1".indices.forEach { end ->
            vm.dispatch(AuditIntent.UpdateFromDate("2026-10-1".substring(0, end + 1)))
            advanceUntilIdle()
        }
        vm.dispatch(AuditIntent.UpdateFromDate("2026-02-30"))
        advanceUntilIdle()
        assertTrue(repository.listCalls.isEmpty(), "teclear una fecha no lanza una petición por tecla: ${repository.listCalls}")
        assertEquals(listOf("m1"), vm.state.value.shownIds(), "lo que se ve sigue ahí mientras se escribe")

        vm.dispatch(AuditIntent.UpdateFromDate("2026-10-13"))
        advanceUntilIdle()
        assertEquals(1, repository.listCalls.size)
        // Rango al revés: no se pregunta.
        vm.dispatch(AuditIntent.UpdateUntilDate("2026-10-01"))
        advanceUntilIdle()
        assertEquals(1, repository.listCalls.size)
        // Borrar la fecha sí es una pregunta nueva: «sin límite».
        vm.dispatch(AuditIntent.UpdateUntilDate(""))
        vm.dispatch(AuditIntent.UpdateFromDate(""))
        advanceUntilIdle()
        assertEquals(AuditFilter(), repository.listCalls.last().first)
    }

    @Test
    fun `the search box only looks at what is loaded`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1"), movement("m2", action = alta)), "c1"))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AuditIntent.UpdateSearch("reactivada"))
        advanceUntilIdle()
        assertEquals(listOf("m2"), vm.state.value.shownIds())
        vm.dispatch(AuditIntent.UpdateSearch("nadie"))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, vm.state.value.movements)
        assertEquals(2, vm.state.value.allMovements.size, "el buscador no descarta lo cargado")
        assertEquals(1, repository.listCalls.size, "buscar no vuelve al servidor")
        assertTrue(vm.state.value.hasFilters && !vm.state.value.hasServerFilters)

        // Quitar solo el buscador tampoco vuelve al servidor.
        vm.dispatch(AuditIntent.ClearFilters)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.state.value.shownIds())
        assertEquals(1, repository.listCalls.size)
    }

    @Test
    fun `clearing the filters asks for the unfiltered list again`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(AuditIntent.SelectAction(baja))
        vm.dispatch(AuditIntent.UpdateSearch("ana"))
        advanceUntilIdle()

        vm.dispatch(AuditIntent.ClearFilters)
        advanceUntilIdle()
        assertEquals(AuditFilter(), repository.listCalls.last().first)
        assertFalse(vm.state.value.hasFilters)
        assertEquals("", vm.state.value.searchQuery)
    }

    // ------------------------------------------------------------------------------------------------ cargar más

    @Test
    fun `load more continues the same filtered list and stops when there is no cursor`() = runTest {
        val repository = FakeRepository(
            firstPage = AuditPage(listOf(movement("m1"), movement("m2")), "c1"),
            morePages = mapOf(
                "c1" to AuditPage(listOf(movement("m2"), movement("m3", actorId = "u-bea", actorName = "Bea")), "c2"),
                "c2" to AuditPage(listOf(movement("m4")), null),
            ),
        )
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(AuditIntent.SelectAction(baja))
        advanceUntilIdle()
        repository.listCalls.clear()

        // Doble clic mientras la página está en camino: una sola petición.
        val inFlight = repository.holdNextList()
        vm.dispatch(AuditIntent.LoadMore)
        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadingMore)
        inFlight.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<Pair<AuditFilter, String?>>(AuditFilter(action = baja) to "c1"), repository.listCalls.toList())
        // m2 llegó repetida entre páginas: sale una vez.
        assertEquals(listOf("m1", "m2", "m3"), vm.state.value.shownIds())
        assertEquals("c2", vm.state.value.nextCursor)
        assertEquals("m1", vm.state.value.selectedId, "cargar más no cambia el movimiento abierto")
        assertEquals(listOf("Ana García", "Bea"), vm.state.value.actorOptions.map { it.label })

        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2", "m3", "m4"), vm.state.value.shownIds())
        assertNull(vm.state.value.nextCursor)

        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertEquals(2, repository.listCalls.size, "sin cursor no hay nada que pedir")
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `a failed load more keeps what was loaded and can be retried`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1")), "c1"), morePages = mapOf("c1" to AuditPage(listOf(movement("m2")), null)))
        val vm = viewModel(repository)
        advanceUntilIdle()

        repository.failList = true
        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("m1"), vm.state.value.shownIds())
        assertIs<AppError.Network>(vm.state.value.loadMoreError)
        assertEquals("c1", vm.state.value.nextCursor, "el cursor se conserva para reintentar")

        repository.failList = false
        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.state.value.shownIds())
        assertNull(vm.state.value.loadMoreError)
    }

    // ------------------------------------------------------------------------------------------------ detalle

    @Test
    fun `before and after are fetched on demand, once per movement`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1"), movement("m2")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AuditIntent.Select("m2"))
        advanceUntilIdle()
        assertTrue(repository.detailCalls.isEmpty(), "abrir un movimiento no descarga sus cambios")
        assertEquals(UiAsync.Idle, vm.state.value.detail)

        vm.dispatch(AuditIntent.LoadDetail)
        vm.dispatch(AuditIntent.LoadDetail)
        advanceUntilIdle()
        assertEquals(listOf("m2"), repository.detailCalls)
        assertEquals(detail("m2"), assertIs<UiAsync.Success<AuditDetail>>(vm.state.value.detail).value)

        // Ir a otro y volver: lo ya leído no se vuelve a pedir (un movimiento escrito no cambia).
        vm.dispatch(AuditIntent.Select("m1"))
        advanceUntilIdle()
        assertEquals(UiAsync.Idle, vm.state.value.detail)
        vm.dispatch(AuditIntent.Select("m2"))
        advanceUntilIdle()
        assertIs<UiAsync.Success<AuditDetail>>(vm.state.value.detail)
        assertEquals(listOf("m2"), repository.detailCalls)

        // Un movimiento que no está en lo cargado no se abre.
        vm.dispatch(AuditIntent.Select("fantasma"))
        advanceUntilIdle()
        assertEquals("m2", vm.state.value.selectedId)
    }

    @Test
    fun `a failed detail says error and can be retried`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1")), null), failDetail = true)
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AuditIntent.LoadDetail)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.detail)

        repository.failDetail = false
        vm.dispatch(AuditIntent.LoadDetail)
        advanceUntilIdle()
        assertIs<UiAsync.Success<AuditDetail>>(vm.state.value.detail)
        assertEquals(listOf("m1", "m1"), repository.detailCalls)
    }

    // ------------------------------------------------------------------------------------------------ respuestas tardías

    @Test
    fun `a list that arrives late does not replace the answer to a newer filter`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m0")), null))
        repository.pagesByAction = mapOf(baja to AuditPage(listOf(movement("b1")), "cb"), alta to AuditPage(listOf(movement("a1", action = alta)), null))
        val vm = viewModel(repository)
        advanceUntilIdle()

        // La primera pregunta se queda esperando; la segunda contesta enseguida.
        val slow = repository.holdNextList()
        vm.dispatch(AuditIntent.SelectAction(baja))
        advanceUntilIdle()
        assertEquals(UiAsync.Loading, vm.state.value.movements, "con un filtro nuevo lo anterior no se queda haciéndose pasar por el resultado")
        vm.dispatch(AuditIntent.SelectAction(alta))
        advanceUntilIdle()
        assertEquals(listOf("a1"), vm.state.value.shownIds())

        slow.complete(Unit)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(alta, state.actionFilter)
        assertEquals(listOf("a1"), state.shownIds(), "la respuesta tardía de «baja» se descarta")
        assertNull(state.nextCursor, "y su cursor tampoco se queda")
        assertEquals("a1", state.selectedId)
    }

    @Test
    fun `a page of load more that arrives after a filter change is not appended`() = runTest {
        val repository = FakeRepository(
            firstPage = AuditPage(listOf(movement("m1")), "c1"),
            morePages = mapOf("c1" to AuditPage(listOf(movement("m2")), null)),
        )
        repository.pagesByAction = mapOf(alta to AuditPage(listOf(movement("a1", action = alta)), null))
        val vm = viewModel(repository)
        advanceUntilIdle()

        val slow = repository.holdNextList()
        vm.dispatch(AuditIntent.LoadMore)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadingMore)
        vm.dispatch(AuditIntent.SelectAction(alta))
        advanceUntilIdle()

        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a1"), vm.state.value.shownIds(), "la página de la lista anterior no se mezcla con la nueva")
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `a detail that arrives late is not painted on another movement`() = runTest {
        val repository = FakeRepository(firstPage = AuditPage(listOf(movement("m1"), movement("m2")), null))
        val vm = viewModel(repository)
        advanceUntilIdle()

        val slow = repository.holdNextDetail()
        vm.dispatch(AuditIntent.LoadDetail)
        advanceUntilIdle()
        assertEquals(UiAsync.Loading, vm.state.value.detail)
        vm.dispatch(AuditIntent.Select("m2"))
        advanceUntilIdle()

        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals("m2", vm.state.value.selectedId)
        assertEquals(UiAsync.Idle, vm.state.value.detail, "los cambios de m1 no aparecen en la ficha de m2")

        // No se ha perdido: al volver a m1 está ahí, sin pedirlo otra vez.
        vm.dispatch(AuditIntent.Select("m1"))
        advanceUntilIdle()
        assertEquals("m1", assertIs<UiAsync.Success<AuditDetail>>(vm.state.value.detail).value.id)
        assertEquals(listOf("m1"), repository.detailCalls)
    }

    // ------------------------------------------------------------------------------------------------ dobles

    private fun TestScope.viewModel(repository: FakeRepository) = AuditViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        auditRepository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
        // Una zona fija: los instantes del filtro no dependen de dónde corra la prueba.
        timeZone = TimeZone.UTC,
    )

    private inner class FakeRepository(
        var firstPage: AuditPage = AuditPage(emptyList(), null),
        val morePages: Map<String, AuditPage> = emptyMap(),
        var failList: Boolean = false,
        var failDetail: Boolean = false,
    ) : AuditRepository {
        val listCalls = mutableListOf<Pair<AuditFilter, String?>>()
        val detailCalls = mutableListOf<String>()

        /** Primera página según el filtro de acción; lo que no esté aquí contesta [firstPage]. */
        var pagesByAction: Map<String, AuditPage> = emptyMap()
        private var listGate: CompletableDeferred<Unit>? = null
        private var detailGate: CompletableDeferred<Unit>? = null

        /** La PRÓXIMA lectura de la lista no contesta hasta que se complete lo devuelto. */
        fun holdNextList(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { listGate = it }

        fun holdNextDetail(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { detailGate = it }

        override suspend fun fetchMovements(filter: AuditFilter, cursor: String?): AppResult<AuditPage> {
            listCalls += filter to cursor
            // La respuesta se decide al PEDIR (como un servidor): lo que cambie después no la altera.
            val answer: AppResult<AuditPage> = when {
                failList -> AppResult.Failure(AppError.Network("timeout"))
                cursor != null -> AppResult.Success(morePages.getValue(cursor))
                else -> AppResult.Success(pagesByAction[filter.action] ?: firstPage)
            }
            listGate?.also { listGate = null }?.await()
            return answer
        }

        override suspend fun fetchDetail(id: String): AppResult<AuditDetail> {
            detailCalls += id
            val answer: AppResult<AuditDetail> = if (failDetail) AppResult.Failure(AppError.Network("timeout")) else AppResult.Success(detail(id))
            detailGate?.also { detailGate = null }?.await()
            return answer
        }
    }
}
