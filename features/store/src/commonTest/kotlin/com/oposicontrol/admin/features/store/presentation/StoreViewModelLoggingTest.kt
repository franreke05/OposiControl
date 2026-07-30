package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.InMemoryLogger
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.repository.CommerceRepository
import com.oposicontrol.admin.domain.usecase.commerce.ObserveBurstCampaignsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveLimitedResourcesUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreItemsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreOffersUseCase
import com.oposicontrol.admin.domain.usecase.commerce.RefreshCommerceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertBurstCampaignUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertLimitedResourceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreItemUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreOfferUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class StoreViewModelLoggingTest {
    @Test
    fun `item save logs safe start and terminal result`() = runTest {
        val repository = CommerceTestRepository()
        val logger = InMemoryLogger()
        val viewModel = StoreViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeStoreItemsUseCase = ObserveStoreItemsUseCase(repository),
            observeStoreOffersUseCase = ObserveStoreOffersUseCase(repository),
            observeLimitedResourcesUseCase = ObserveLimitedResourcesUseCase(repository),
            observeBurstCampaignsUseCase = ObserveBurstCampaignsUseCase(repository),
            refreshCommerceUseCase = RefreshCommerceUseCase(repository),
            upsertStoreItemUseCase = UpsertStoreItemUseCase(repository),
            upsertStoreOfferUseCase = UpsertStoreOfferUseCase(repository),
            upsertLimitedResourceUseCase = UpsertLimitedResourceUseCase(repository),
            upsertBurstCampaignUseCase = UpsertBurstCampaignUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
            logger = logger,
        )
        advanceUntilIdle()

        viewModel.dispatch(StoreIntent.CreateNew)
        viewModel.dispatch(StoreIntent.UpdateName("Producto confidencial"))
        viewModel.dispatch(StoreIntent.UpdateDescription("Descripcion privada"))
        viewModel.dispatch(StoreIntent.UpdatePriceTickets("25"))
        viewModel.dispatch(StoreIntent.Save)
        advanceUntilIdle()

        val start = logger.entries.single { "event=commerce.item.save.start " in it.message }
        val success = logger.entries.single { "event=commerce.item.save.success " in it.message }
        assertEquals("OC-VM", start.tag)
        assertEquals(storeCorrelationId(start.message), storeCorrelationId(success.message))
        val rendered = logger.entries.joinToString("\n") { it.message }
        assertFalse("Producto confidencial" in rendered)
        assertFalse("Descripcion privada" in rendered)
        assertFalse("item-private" in rendered)
    }
}

private class CommerceTestRepository : CommerceRepository {
    private val items = MutableStateFlow<List<StoreItem>>(emptyList())
    private val offers = MutableStateFlow<List<StoreOffer>>(emptyList())
    private val limitedResources = MutableStateFlow<List<LimitedResourceWindow>>(emptyList())
    private val bursts = MutableStateFlow<List<BurstCampaign>>(emptyList())

    override fun observeStoreItems(): Flow<List<StoreItem>> = items
    override fun observeStoreOffers(): Flow<List<StoreOffer>> = offers
    override fun observeLimitedResources(): Flow<List<LimitedResourceWindow>> = limitedResources
    override fun observeBurstCampaigns(): Flow<List<BurstCampaign>> = bursts
    override suspend fun refreshCommerce(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> {
        val saved = item.copy(id = item.id.ifBlank { "item-private" })
        items.value = items.value.filterNot { it.id == saved.id } + saved
        return AppResult.Success(saved)
    }

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> =
        AppResult.Success(offer)

    override suspend fun upsertLimitedResource(
        window: LimitedResourceWindow,
    ): AppResult<LimitedResourceWindow> = AppResult.Success(window)

    override suspend fun upsertBurstCampaign(
        campaign: BurstCampaign,
    ): AppResult<BurstCampaign> = AppResult.Success(campaign)
}

private fun storeCorrelationId(message: String): String =
    Regex("correlationId=([^ ]+)").find(message)?.groupValues?.get(1).orEmpty()
