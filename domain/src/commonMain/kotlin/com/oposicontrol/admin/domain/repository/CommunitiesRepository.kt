package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Community
import kotlinx.coroutines.flow.Flow

/** CRUD de comunidades autonomas (tabla `comunidad_autonoma`, ver migracion). */
interface CommunitiesRepository {
    fun observeCommunities(): Flow<List<Community>>
    suspend fun refreshCommunities(): AppResult<List<Community>>
    suspend fun upsertCommunity(community: Community): AppResult<Community>
    suspend fun deleteCommunity(communityId: String): AppResult<Unit>
}
