package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUse
import com.oposicontrol.admin.domain.model.MarketPromotionUsesPage
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Los cuatro catálogos editables de OposiMarket a través del ViewModel, contra un repositorio en memoria. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModelEditorsTest {

    private class FakeMarket : MarketCatalogRepository {
        var packs = listOf(MarketTicketPack("p1", "pack_500", "500 OposiTickets", ticketAmount = 500, priceCents = 499))
        var promotions = listOf(
            MarketPromotion("pr1", "OTONO25", "Otoño", "porcentaje", 15.0, uses = 3),
            MarketPromotion("pr2", "INVIERNO", "Invierno", "tickets_extra", 50.0, uses = 0),
        )
        var saveFailure: AppError? = null
        var usesFailure: AppError? = null
        val reads = mutableListOf<String>()
        val saved = mutableListOf<Any>()
        val usesRequests = mutableListOf<Pair<String, String?>>()

        private fun <T> write(entity: T, withId: (T) -> T): AppResult<T> {
            saveFailure?.let { return AppResult.Failure(it) }
            saved += entity as Any
            return AppResult.Success(withId(entity))
        }

        override suspend fun getTicketPacks(): AppResult<List<MarketTicketPack>> {
            reads += "packs"
            return AppResult.Success(packs)
        }
        override suspend fun saveTicketPack(pack: MarketTicketPack) = write(pack) { it.copy(id = it.id.ifBlank { "p-new" }) }

        override suspend fun getServices(): AppResult<List<MarketService>> {
            reads += "services"
            return AppResult.Success(emptyList())
        }
        override suspend fun saveService(service: MarketService) = write(service) { it.copy(id = it.id.ifBlank { "s-new" }) }

        override suspend fun getPromotions(): AppResult<List<MarketPromotion>> {
            reads += "promotions"
            return AppResult.Success(promotions)
        }
        override suspend fun savePromotion(promotion: MarketPromotion) = write(promotion) { it.copy(id = it.id.ifBlank { "pr-new" }, uses = null) }

        override suspend fun getPromotionUses(promotionId: String, cursor: String?): AppResult<MarketPromotionUsesPage> {
            usesRequests += promotionId to cursor
            usesFailure?.let { return AppResult.Failure(it) }
            if (promotionId == "pr2") return AppResult.Success(MarketPromotionUsesPage(items = emptyList()))
            return AppResult.Success(
                if (cursor == null) {
                    MarketPromotionUsesPage(listOf(use("u1"), use("u2")), nextCursor = "c2", uses = 3, conversions = 2, renewals = 1)
                } else {
                    MarketPromotionUsesPage(listOf(use("u3")), nextCursor = null, uses = 3, conversions = 2, renewals = 1)
                },
            )
        }

        override suspend fun getPopupRules(): AppResult<List<MarketPopupRule>> {
            reads += "popups"
            return AppResult.Success(emptyList())
        }
        override suspend fun savePopupRule(rule: MarketPopupRule) = write(rule) { it.copy(id = it.id.ifBlank { "r-new" }) }

        private fun use(userId: String) = MarketPromotionUse(userId, usedAt = "2026-10-09T14:32:10Z", generatedPurchase = true)
    }

    private fun TestScope.viewModel(repository: FakeMarket) = MarketViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        examsSource = PublishedExamsSource { AppResult.Success(PublishedCatalog(exams = emptyList(), communities = emptyList())) },
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun `each section is read the first time it is opened and not again when coming back`() = runTest {
        val repository = FakeMarket()
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertTrue(repository.reads.isEmpty())

        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        vm.dispatch(MarketIntent.SelectSection(MarketSection.SERVICES))
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        advanceUntilIdle()

        assertEquals(listOf("packs", "services"), repository.reads)
        assertEquals("p1", vm.state.value.packs.selectedId)
        assertIs<UiAsync.Empty>(vm.state.value.services.items)

        vm.dispatch(MarketIntent.Refresh)
        advanceUntilIdle()
        assertEquals(listOf("packs", "services", "packs"), repository.reads)
    }

    @Test
    fun `a draft survives a visit to another section`() = runTest {
        val vm = viewModel(FakeMarket())
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        advanceUntilIdle()
        val draft = vm.state.value.packs.draft.copy(name = "A medias")
        vm.dispatch(MarketIntent.Packs(EditorAction.UpdateDraft(draft)))
        vm.dispatch(MarketIntent.SelectSection(MarketSection.POPUPS))
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        advanceUntilIdle()

        assertEquals("A medias", vm.state.value.packs.draft.name)
    }

    @Test
    fun `saving a pack sends it by id and an invalid one is not sent`() = runTest {
        val repository = FakeMarket()
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        advanceUntilIdle()

        vm.dispatch(MarketIntent.Packs(EditorAction.UpdateDraft(vm.state.value.packs.draft.copy(ticketAmount = "0"))))
        vm.dispatch(MarketIntent.Packs(EditorAction.Save))
        advanceUntilIdle()
        assertTrue(repository.saved.isEmpty())
        assertIs<AppError.Validation>(assertIs<UiAsync.Error>(vm.state.value.packs.actionState).error)

        vm.dispatch(MarketIntent.Packs(EditorAction.UpdateDraft(vm.state.value.packs.draft.copy(ticketAmount = "600", price = "5,49"))))
        vm.dispatch(MarketIntent.Packs(EditorAction.Save))
        advanceUntilIdle()

        val sent = repository.saved.single() as MarketTicketPack
        assertEquals("p1", sent.id)
        assertEquals(600 to 549, sent.ticketAmount to sent.priceCents)
        assertIs<UiAsync.Success<*>>(vm.state.value.packs.actionState)
        assertFalse(vm.state.value.packs.dirty(PackSpec))
    }

    @Test
    fun `a new service is sent with a blank id, joins an empty list and stays open`() = runTest {
        val repository = FakeMarket()
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.SERVICES))
        vm.dispatch(MarketIntent.Services(EditorAction.Open(null)))
        vm.dispatch(MarketIntent.Services(EditorAction.UpdateDraft(ServiceDraft("protector", "Protector", type = "protector_racha", priceTickets = "30"))))
        vm.dispatch(MarketIntent.Services(EditorAction.Save))
        advanceUntilIdle()

        val sent = repository.saved.single() as MarketService
        assertEquals("", sent.id)
        assertEquals(30, sent.priceTickets)
        val services = vm.state.value.services
        assertEquals(listOf("s-new"), services.all.map { it.id })
        assertEquals("s-new", services.selectedId)
        assertFalse(services.creatingNew)
    }

    @Test
    fun `a failed save keeps what was typed`() = runTest {
        val repository = FakeMarket().apply { saveFailure = AppError.Network("sin red") }
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PACKS))
        advanceUntilIdle()
        vm.dispatch(MarketIntent.Packs(EditorAction.UpdateDraft(vm.state.value.packs.draft.copy(name = "Otro nombre"))))
        vm.dispatch(MarketIntent.Packs(EditorAction.Save))
        advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.packs.actionState)
        assertEquals("Otro nombre", vm.state.value.packs.draft.name)
        assertTrue(vm.state.value.packs.dirty(PackSpec))
    }

    @Test
    fun `promotion uses are read when the tab is opened, page by page, and belong to one promotion`() = runTest {
        val repository = FakeMarket()
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PROMOTIONS))
        advanceUntilIdle()
        // Abrir la sección no pide usos: sólo se leen si alguien los mira.
        assertTrue(repository.usesRequests.isEmpty())

        vm.dispatch(MarketIntent.SelectPromotionTab(PromotionTab.USES))
        advanceUntilIdle()
        val first = assertIs<UiAsync.Success<MarketPromotionUsesPage>>(vm.state.value.promotionUses).value
        assertEquals(listOf("u1", "u2"), first.items.map { it.userId })
        assertEquals(3, first.uses)

        vm.dispatch(MarketIntent.LoadMorePromotionUses)
        advanceUntilIdle()
        val all = assertIs<UiAsync.Success<MarketPromotionUsesPage>>(vm.state.value.promotionUses).value
        assertEquals(listOf("u1", "u2", "u3"), all.items.map { it.userId })
        assertNull(all.nextCursor)
        assertEquals(listOf("pr1" to null, "pr1" to "c2"), repository.usesRequests)

        // Al abrir otra promoción los usos de la anterior desaparecen y se vuelve a la ficha.
        vm.dispatch(MarketIntent.Promotions(EditorAction.Open("pr2")))
        advanceUntilIdle()
        assertEquals(PromotionTab.PROMOTION, vm.state.value.promotionTab)
        assertIs<UiAsync.Idle>(vm.state.value.promotionUses)

        vm.dispatch(MarketIntent.SelectPromotionTab(PromotionTab.USES))
        advanceUntilIdle()
        assertIs<UiAsync.Empty>(vm.state.value.promotionUses)
    }

    @Test
    fun `uses that cannot be read are an error with a retry, and a failed next page keeps the ones shown`() = runTest {
        val repository = FakeMarket().apply { usesFailure = AppError.Network("sin red") }
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.PROMOTIONS))
        vm.dispatch(MarketIntent.SelectPromotionTab(PromotionTab.USES))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.promotionUses)

        repository.usesFailure = null
        vm.dispatch(MarketIntent.LoadPromotionUses)
        advanceUntilIdle()
        assertIs<UiAsync.Success<*>>(vm.state.value.promotionUses)

        repository.usesFailure = AppError.Network("sin red")
        vm.dispatch(MarketIntent.LoadMorePromotionUses)
        advanceUntilIdle()
        val kept = assertIs<UiAsync.Success<MarketPromotionUsesPage>>(vm.state.value.promotionUses).value
        assertEquals(2, kept.items.size)
        assertTrue(vm.state.value.moreUsesFailed)
        assertFalse(vm.state.value.loadingMoreUses)
    }

    @Test
    fun `a new rule is created with the table defaults and the code it was given`() = runTest {
        val repository = FakeMarket()
        val vm = viewModel(repository)
        vm.dispatch(MarketIntent.SelectSection(MarketSection.POPUPS))
        vm.dispatch(MarketIntent.Popups(EditorAction.Open(null)))
        vm.dispatch(MarketIntent.Popups(EditorAction.UpdateDraft(PopupDraft(code = "sin_tickets", trigger = "sin_tickets", title = "Sin tickets", message = "Compra un pack"))))
        vm.dispatch(MarketIntent.Popups(EditorAction.Save))
        advanceUntilIdle()

        val sent = repository.saved.single() as MarketPopupRule
        assertEquals("", sent.id)
        assertEquals(24 to 50, sent.minimumFrequencyHours to sent.priority)
        assertNull(sent.screen)
        assertEquals("r-new", vm.state.value.popups.selectedId)
    }
}
