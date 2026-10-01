package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorCommerceRepositoryImpl
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreItemType
import com.oposicontrol.admin.domain.model.StoreOffer
import kotlinx.datetime.Instant

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommerceRepositoryImpl.kt"

private val T0 = Instant.fromEpochMilliseconds(0)

private fun Env.repo() = KtorCommerceRepositoryImpl(client, appConfig, session, adapter)

private const val T = "/api/v1/admin/tienda"

/** Los 4 GET de `refreshCommerce` salen siempre en este orden; cada sitio del fichero es uno de ellos. */
private val REFRESH_CHAIN = listOf(get("$T/articulos"), get("$T/ofertas"), get("$T/recursos-temporales"), get("$T/rafagas"))

/**
 * Filas del registrador (R4-REC-B): KtorCommerceRepositoryImpl (8 sitios). Los 4 sitios de `refreshCommerce` son GET
 * encadenados dentro de UN metodo: una fila por sitio, las cuatro declaran la cadena completa; cada `upsert*` es un POST
 * (la respuesta minima no mapea, asi que el refresco posterior no se encadena).
 */
internal val ROWS_B_COMMERCE_REPO: List<CallRow> = listOf(
    CallRow(FILE, "refreshCommerce(articulos)", REFRESH_CHAIN) { repo().refreshCommerce() },
    CallRow(FILE, "refreshCommerce(ofertas)", REFRESH_CHAIN) { repo().refreshCommerce() },
    CallRow(FILE, "refreshCommerce(recursos-temporales)", REFRESH_CHAIN) { repo().refreshCommerce() },
    CallRow(FILE, "refreshCommerce(rafagas)", REFRESH_CHAIN) { repo().refreshCommerce() },
    CallRow(FILE, "upsertStoreItem", post("$T/articulos")) {
        repo().upsertStoreItem(StoreItem("ID0", "n", "d", StoreItemType.CONSUMABLE, 1, CommerceStatus.ACTIVE, 1, T0, T0))
    },
    CallRow(FILE, "upsertStoreOffer", post("$T/ofertas")) {
        repo().upsertStoreOffer(StoreOffer("ID0", "ID1", "t", null, 1, null, null, CommerceStatus.ACTIVE))
    },
    CallRow(FILE, "upsertLimitedResource", post("$T/recursos-temporales")) {
        repo().upsertLimitedResource(LimitedResourceWindow("ID0", "ID1", "ID2", "t", null, null, 1, CommerceStatus.ACTIVE))
    },
    CallRow(FILE, "upsertBurstCampaign", post("$T/rafagas")) {
        repo().upsertBurstCampaign(BurstCampaign("ID0", "t", "d", BurstCampaignType.EXAM_BURST, null, null, 1.0, 1, null, null, CommerceStatus.ACTIVE))
    },
)
