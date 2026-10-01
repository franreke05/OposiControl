package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorCommunitiesRepositoryImpl
import com.oposicontrol.admin.domain.model.Community
import kotlinx.datetime.Instant

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommunitiesRepositoryImpl.kt"
private const val ROUTE = "/api/v1/admin/comunidades-autonomas"

private fun community(id: String) =
    Community(id = id, name = "n", code = "c", slug = "n", isActive = true, sortOrder = 1, createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0))

/** Filas del registrador (R4-REC-B): KtorCommunitiesRepositoryImpl. */
internal val ROWS_B_COMMUNITIES_REPO: List<CallRow> = listOf(
    CallRow(FILE, "refreshCommunities", get(ROUTE)) {
        KtorCommunitiesRepositoryImpl(client, appConfig, session, adapter).refreshCommunities()
    },
    CallRow(FILE, "upsertCommunity(create)", post(ROUTE)) {
        KtorCommunitiesRepositoryImpl(client, appConfig, session, adapter).upsertCommunity(community(""))
    },
    CallRow(FILE, "upsertCommunity(update)", patch("$ROUTE/{}")) {
        KtorCommunitiesRepositoryImpl(client, appConfig, session, adapter).upsertCommunity(community("ID0"))
    },
    // deleteCommunity: DELETE y despues el refresco que encadena
    CallRow(FILE, "deleteCommunity", listOf(delete("$ROUTE/{}"), get(ROUTE))) {
        KtorCommunitiesRepositoryImpl(client, appConfig, session, adapter).deleteCommunity("ID0")
    },
)
