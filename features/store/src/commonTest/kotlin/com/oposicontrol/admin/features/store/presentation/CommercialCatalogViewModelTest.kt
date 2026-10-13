package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommercialCatalogViewModelTest {

    private fun TestScope.viewModel(repository: FakeCatalogRepository) = CommercialCatalogViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun `opening the screen loads every product and the plans, and opens the first product`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(PRODUCT_PACK, PRODUCT_SUB), state.allProducts.map { it.id })
        assertEquals(PRODUCT_PACK, state.selectedId)
        assertEquals("4,99", state.draft.price)
        assertEquals(2, state.plans.size)
        assertFalse(state.dirty)
    }

    @Test
    fun `an empty catalogue is empty and a failed read is an error, never a silent blank list`() = runTest {
        val empty = viewModel(FakeCatalogRepository(products = emptyList()))
        advanceUntilIdle()
        assertIs<UiAsync.Empty>(empty.state.value.products)

        val down = viewModel(FakeCatalogRepository().apply { productsFailure = AppError.Network("sin red") })
        advanceUntilIdle()
        assertIs<UiAsync.Error>(down.state.value.products)
    }

    @Test
    fun `plans that cannot be read do not take the catalogue down`() = runTest {
        val vm = viewModel(FakeCatalogRepository().apply { plansFailure = AppError.Network("sin red") })
        advanceUntilIdle()

        assertIs<UiAsync.Success<*>>(vm.state.value.products)
        assertTrue(vm.state.value.plansUnavailable)

        vm.dispatch(CommercialCatalogIntent.SyncPlans(samplePlans()))
        advanceUntilIdle()
        assertFalse(vm.state.value.plansUnavailable)
        assertEquals(2, vm.state.value.plans.size)
    }

    @Test
    fun `search and type filter narrow the downloaded list and return to the first page`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.SetPage(3))
        vm.dispatch(CommercialCatalogIntent.UpdateTypeFilter("suscripcion"))
        advanceUntilIdle()

        assertEquals(listOf(PRODUCT_SUB), vm.state.value.visibleProducts.map { it.id })
        assertEquals(0, vm.state.value.pageIndex)

        vm.dispatch(CommercialCatalogIntent.UpdateSearch("PACK"))
        advanceUntilIdle()
        assertTrue(vm.state.value.visibleProducts.isEmpty())
    }

    @Test
    fun `editing makes the record dirty and saving sends the product by its id`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.UpdatePrice("5,49"))
        advanceUntilIdle()
        assertTrue(vm.state.value.dirty)

        vm.dispatch(CommercialCatalogIntent.Save)
        advanceUntilIdle()

        val sent = repository.savedProducts.single()
        assertEquals(PRODUCT_PACK, sent.id)
        assertEquals("pack_500", sent.code)
        assertEquals(549, sent.priceCents)
        assertFalse(vm.state.value.dirty)
        assertIs<UiAsync.Success<*>>(vm.state.value.actionState)
        assertEquals(549, vm.state.value.selected?.priceCents)
    }

    @Test
    fun `an invalid record is not sent and says why`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.UpdatePrice("5,499"))
        vm.dispatch(CommercialCatalogIntent.Save)
        advanceUntilIdle()

        assertTrue(repository.savedProducts.isEmpty())
        val error = assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertIs<AppError.Validation>(error.error)
    }

    @Test
    fun `a new product is sent with a blank id, joins the list and stays open`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.Open(null))
        advanceUntilIdle()
        assertTrue(vm.state.value.creatingNew)
        assertNull(vm.state.value.selected)

        vm.dispatch(CommercialCatalogIntent.UpdateCode("pack_500"))
        vm.dispatch(CommercialCatalogIntent.UpdateName("Otro pack"))
        vm.dispatch(CommercialCatalogIntent.UpdateType("pack_tickets"))
        vm.dispatch(CommercialCatalogIntent.UpdateTicketAmount("900"))
        advanceUntilIdle()
        assertEquals("Ya existe un producto con ese código.", vm.state.value.problem)

        vm.dispatch(CommercialCatalogIntent.UpdateCode("pack_900"))
        vm.dispatch(CommercialCatalogIntent.Save)
        advanceUntilIdle()

        val sent = repository.savedProducts.single()
        assertEquals("", sent.id)
        assertEquals("pack_900", sent.code)
        assertEquals(900, sent.ticketAmount)
        val state = vm.state.value
        assertFalse(state.creatingNew)
        assertEquals(3, state.allProducts.size)
        assertEquals("pack_900", state.selected?.code)
    }

    @Test
    fun `leaving a record with unsaved changes asks first`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.UpdateName("Nombre a medias"))
        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        advanceUntilIdle()

        assertEquals(PRODUCT_PACK, vm.state.value.selectedId)
        assertEquals(PendingOpen(PRODUCT_SUB), vm.state.value.pendingOpen)

        vm.dispatch(CommercialCatalogIntent.DismissOpen)
        advanceUntilIdle()
        assertEquals("Nombre a medias", vm.state.value.draft.name)
        assertNull(vm.state.value.pendingOpen)

        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        vm.dispatch(CommercialCatalogIntent.ConfirmOpen)
        advanceUntilIdle()
        assertEquals(PRODUCT_SUB, vm.state.value.selectedId)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `a failed save keeps what was typed`() = runTest {
        val repository = FakeCatalogRepository().apply { saveFailure = AppError.Network("sin red") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.UpdateName("500 OT de otoño"))
        vm.dispatch(CommercialCatalogIntent.Save)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals("500 OT de otoño", vm.state.value.draft.name)
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `a refresh never throws away a record that is being edited`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.UpdateName("A medias"))
        vm.dispatch(CommercialCatalogIntent.Refresh)
        advanceUntilIdle()

        assertEquals(2, repository.productReads)
        assertEquals("A medias", vm.state.value.draft.name)
    }

    @Test
    fun `a refresh keeps the open tab`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        vm.dispatch(CommercialCatalogIntent.SelectTab(ProductTab.TARIFFS))
        vm.dispatch(CommercialCatalogIntent.Refresh)
        advanceUntilIdle()

        assertEquals(ProductTab.TARIFFS, vm.state.value.tab)
    }

    @Test
    fun `a tariff that repeats period and phase is rejected and a new one joins the product`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        vm.dispatch(CommercialCatalogIntent.EditTariff(null))
        vm.dispatch(CommercialCatalogIntent.UpdateTariff(TariffDraft(period = "mensual", phase = "first_year", price = "3,99")))
        vm.dispatch(CommercialCatalogIntent.SaveTariff)
        advanceUntilIdle()

        assertTrue(repository.savedTariffs.isEmpty())
        assertIs<UiAsync.Error>(vm.state.value.tariffActionState)

        vm.dispatch(CommercialCatalogIntent.UpdateTariff(TariffDraft(period = "mensual", phase = "renewal", price = "4,99", position = "2")))
        vm.dispatch(CommercialCatalogIntent.SaveTariff)
        advanceUntilIdle()

        val (productId, tariff) = repository.savedTariffs.single()
        assertEquals(PRODUCT_SUB, productId)
        assertEquals("", tariff.id)
        assertEquals(499, tariff.priceCents)
        assertEquals(listOf("first_year", "renewal"), vm.state.value.selected?.tariffs?.map { it.pricePhase })
        assertNull(vm.state.value.tariffDraft)
    }

    @Test
    fun `deleting a product asks first, removes it from the list and opens the next one`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.AskDeleteProduct)
        advanceUntilIdle()
        assertEquals(PendingCatalogDelete(PRODUCT_PACK), vm.state.value.pendingDelete)
        assertTrue(repository.deletedProducts.isEmpty(), "pedir confirmación no borra nada")

        vm.dispatch(CommercialCatalogIntent.ConfirmDelete)
        vm.dispatch(CommercialCatalogIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(PRODUCT_PACK), repository.deletedProducts)
        val state = vm.state.value
        assertEquals(listOf(PRODUCT_SUB), state.allProducts.map { it.id })
        assertEquals(PRODUCT_SUB, state.selectedId)
        assertFalse(state.deleting)
        assertTrue(state.notice.orEmpty().contains("500 OposiTickets"))
    }

    @Test
    fun `a product the server refuses to delete stays on screen with the server reason`() = runTest {
        val repository = FakeCatalogRepository().apply { deleteFailure = AppError.Validation("No se puede borrar: lo usan 3 suscripciones y 0 compras.") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.AskDeleteProduct)
        vm.dispatch(CommercialCatalogIntent.ConfirmDelete)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.allProducts.size)
        assertEquals(PRODUCT_PACK, state.selectedId)
        assertTrue(assertIs<UiAsync.Error>(state.actionState).error.message.contains("3 suscripciones"))
        assertFalse(state.deleting)
    }

    @Test
    fun `deleting a tariff removes only that tariff and closes its form`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        vm.dispatch(CommercialCatalogIntent.EditTariff("t1"))
        vm.dispatch(CommercialCatalogIntent.AskDeleteTariff("no-existe"))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.pendingDelete)

        vm.dispatch(CommercialCatalogIntent.AskDeleteTariff("t1"))
        vm.dispatch(CommercialCatalogIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(PRODUCT_SUB to "t1"), repository.deletedTariffs)
        val state = vm.state.value
        assertTrue(state.selected!!.tariffs.isEmpty())
        assertEquals(null, state.tariffDraft)
        assertEquals(2, state.allProducts.size, "el producto sigue ahí")
    }

    @Test
    fun `dismissing the confirmation deletes nothing`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialCatalogIntent.AskDeleteProduct)
        vm.dispatch(CommercialCatalogIntent.DismissDelete)
        vm.dispatch(CommercialCatalogIntent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(repository.deletedProducts.isEmpty())
        assertEquals(2, vm.state.value.allProducts.size)
    }

    @Test
    fun `editing a tariff keeps its id and its natural key`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.Open(PRODUCT_SUB))
        vm.dispatch(CommercialCatalogIntent.EditTariff("t1"))
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.tariffDraft)
        assertEquals("2,99", draft.price)

        vm.dispatch(CommercialCatalogIntent.UpdateTariff(draft.copy(price = "3,49", active = false)))
        vm.dispatch(CommercialCatalogIntent.SaveTariff)
        advanceUntilIdle()

        val tariff = repository.savedTariffs.single().second
        assertEquals("t1", tariff.id)
        assertEquals("mensual" to "first_year", tariff.billingPeriod to tariff.pricePhase)
        assertEquals(349, tariff.priceCents)
        assertFalse(tariff.active)
        assertEquals(1, vm.state.value.selected?.tariffs?.size)
    }

    @Test
    fun `a refresh without local changes shows what another administrator saved`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        repository.products = repository.products.map { if (it.id == PRODUCT_PACK) it.copy(priceCents = 599) else it }
        vm.dispatch(CommercialCatalogIntent.Refresh)
        advanceUntilIdle()

        assertEquals("5,99", vm.state.value.draft.price)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `a failed refresh keeps the catalogue on screen and says so`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        repository.productsFailure = AppError.Network("sin red")
        vm.dispatch(CommercialCatalogIntent.Refresh)
        advanceUntilIdle()

        assertIs<UiAsync.Success<*>>(vm.state.value.products)
        assertTrue(vm.state.value.refreshFailed)
        assertFalse(vm.state.value.refreshing)
        assertEquals(PRODUCT_PACK, vm.state.value.selectedId)
    }

    @Test
    fun `an optional field the server kept is reported instead of calling it saved`() = runTest {
        val stored = sampleProducts().map { if (it.id == PRODUCT_PACK) it.copy(description = "Texto antiguo") else it }
        // Como el servidor de hoy: devuelve la ficha con la descripcion que ya tenia aunque se envie vacia.
        val repository = object : CommercialCatalogRepository by FakeCatalogRepository(products = stored) {
            override suspend fun saveProduct(product: CommercialProduct) = AppResult.Success(product.copy(description = "Texto antiguo"))
        }
        val vm = CommercialCatalogViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repository, NoOpAnalyticsTracker)
        advanceUntilIdle()
        vm.dispatch(CommercialCatalogIntent.UpdateDescription(""))
        vm.dispatch(CommercialCatalogIntent.Save)
        advanceUntilIdle()

        assertEquals("El servidor conservó el valor anterior de la descripción: vaciar ese campo todavía no se guarda.", vm.state.value.notice)
        assertEquals("Texto antiguo", vm.state.value.draft.description)
    }
}
