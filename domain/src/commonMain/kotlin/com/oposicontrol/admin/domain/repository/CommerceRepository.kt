package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreOffer
import kotlinx.coroutines.flow.Flow

interface CommerceRepository {
    fun observeStoreItems(): Flow<List<StoreItem>>
    fun observeStoreOffers(): Flow<List<StoreOffer>>
    fun observeLimitedResources(): Flow<List<LimitedResourceWindow>>
    fun observeBurstCampaigns(): Flow<List<BurstCampaign>>

    suspend fun refreshCommerce(): AppResult<Unit>
    suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem>
    suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer>
    suspend fun upsertLimitedResource(window: LimitedResourceWindow): AppResult<LimitedResourceWindow>
    suspend fun upsertBurstCampaign(campaign: BurstCampaign): AppResult<BurstCampaign>
}
