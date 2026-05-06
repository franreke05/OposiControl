package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreItemType
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.repository.CommerceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

class FakeCommerceRepositoryImpl : CommerceRepository {
    private val now = Instant.parse("2026-04-15T09:00:00Z")
    private val items = MutableStateFlow(
        listOf(
            StoreItem("art-1", "Protector basico", "Evita perder progreso en una respuesta critica.", StoreItemType.PROTECTOR, 40, CommerceStatus.ACTIVE, 0, now, now),
            StoreItem("art-2", "Racha x2", "Multiplica la recompensa de una sesion diaria.", StoreItemType.STREAK, 60, CommerceStatus.ACTIVE, 1, now, now),
            StoreItem("art-3", "Consumible energia", "Recarga para sesiones intensivas.", StoreItemType.CONSUMABLE, 20, CommerceStatus.DRAFT, 2, now, now),
        ),
    )
    private val offers = MutableStateFlow(
        listOf(
            StoreOffer("offer-1", "art-2", "Fin de semana x2", 25, null, Instant.parse("2026-04-18T00:00:00Z"), Instant.parse("2026-04-21T00:00:00Z"), CommerceStatus.SCHEDULED),
        ),
    )
    private val limited = MutableStateFlow(
        listOf(
            LimitedResourceWindow("limited-1", "art-1", "resource-tcae-1", "Guia TCAE 72h", Instant.parse("2026-04-20T08:00:00Z"), Instant.parse("2026-04-23T08:00:00Z"), 72, CommerceStatus.SCHEDULED),
        ),
    )
    private val bursts = MutableStateFlow(
        listOf(
            BurstCampaign("burst-1", "Rafaga diaria", "Premia 5 dias seguidos.", BurstCampaignType.DAILY_STREAK, Instant.parse("2026-04-20T08:00:00Z"), Instant.parse("2026-05-01T08:00:00Z"), 2.0, 30, null, "art-2", CommerceStatus.SCHEDULED),
        ),
    )

    override fun observeStoreItems(): Flow<List<StoreItem>> = items.asStateFlow()
    override fun observeStoreOffers(): Flow<List<StoreOffer>> = offers.asStateFlow()
    override fun observeLimitedResources(): Flow<List<LimitedResourceWindow>> = limited.asStateFlow()
    override fun observeBurstCampaigns(): Flow<List<BurstCampaign>> = bursts.asStateFlow()

    override suspend fun refreshCommerce(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> {
        val saved = item.copy(id = item.id.ifBlank { randomUuid() }, updatedAt = now, createdAt = item.createdAt)
        items.value = (items.value.filterNot { it.id == saved.id } + saved).sortedBy { it.sortOrder }
        return AppResult.Success(saved)
    }

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> {
        val saved = offer.copy(id = offer.id.ifBlank { randomUuid() })
        offers.value = listOf(saved) + offers.value.filterNot { it.id == saved.id }
        return AppResult.Success(saved)
    }

    override suspend fun upsertLimitedResource(window: LimitedResourceWindow): AppResult<LimitedResourceWindow> {
        val saved = window.copy(id = window.id.ifBlank { randomUuid() })
        limited.value = listOf(saved) + limited.value.filterNot { it.id == saved.id }
        return AppResult.Success(saved)
    }

    override suspend fun upsertBurstCampaign(campaign: BurstCampaign): AppResult<BurstCampaign> {
        val saved = campaign.copy(id = campaign.id.ifBlank { randomUuid() })
        bursts.value = listOf(saved) + bursts.value.filterNot { it.id == saved.id }
        return AppResult.Success(saved)
    }
}
