package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreItemType
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.repository.CommerceRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Store admin operations proxied through the Ktor boundary. Replaces
 * [SupabaseCommerceRepositoryImpl], which called Supabase PostgREST directly
 * from Desktop with the anon key + user JWT -- the only repository in
 * [com.oposicontrol.admin.data.di.dataModule] bypassing Ktor. Backend side:
 * AdminV1Routes.kt `/api/v1/admin/tienda` routes.
 */
class KtorCommerceRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : CommerceRepository {
    private val items = MutableStateFlow<List<StoreItem>>(emptyList())
    private val offers = MutableStateFlow<List<StoreOffer>>(emptyList())
    private val limited = MutableStateFlow<List<LimitedResourceWindow>>(emptyList())
    private val bursts = MutableStateFlow<List<BurstCampaign>>(emptyList())

    override fun observeStoreItems(): Flow<List<StoreItem>> = items.asStateFlow()
    override fun observeStoreOffers(): Flow<List<StoreOffer>> = offers.asStateFlow()
    override fun observeLimitedResources(): Flow<List<LimitedResourceWindow>> = limited.asStateFlow()
    override fun observeBurstCampaigns(): Flow<List<BurstCampaign>> = bursts.asStateFlow()

    override suspend fun refreshCommerce(): AppResult<Unit> = adapter.safeCall {
        val itemsResponse = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)) { backendHeaders() }
        itemsResponse.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)
        items.value = itemsResponse.body<KtorStoreItemsResponse>().data.map { it.toDomain() }

        offers.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)
            r.body<KtorStoreOffersResponse>().data.map { it.toDomain() }
        }.getOrElse { emptyList() }

        limited.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)
            r.body<KtorLimitedResourcesResponse>().data.map { it.toDomain() }
        }.getOrElse { emptyList() }

        bursts.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)
            r.body<KtorBurstCampaignsResponse>().data.map { it.toDomain() }
        }.getOrElse { emptyList() }
        Unit
    }

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(item.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)
            r.body<KtorStoreItemResponse>().data.toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(offer.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)
            r.body<KtorStoreOfferResponse>().data.toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertLimitedResource(window: LimitedResourceWindow): AppResult<LimitedResourceWindow> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(window.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)
            r.body<KtorLimitedResourceResponse>().data.toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertBurstCampaign(campaign: BurstCampaign): AppResult<BurstCampaign> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(campaign.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)
            r.body<KtorBurstCampaignResponse>().data.toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    private fun url(path: String) = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() } ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

// Backend wraps every list/single resource in {"data": ...} (see AdminV1Routes.kt's
// AdminArticulosTiendaResponse/AdminArticuloTiendaResponse and its siblings for the
// other 3 resources, Task 6) -- matching every other AdminV1 response DTO
// (AdminEspecialidadesResponse, ReportesV1Response in KtorModerationRepositoryImpl,
// etc). These unwrap that envelope; do not deserialize the bare row list/object.
@Serializable
private data class KtorStoreItemsResponse(val data: List<KtorStoreItemRowDto>)
@Serializable
private data class KtorStoreItemResponse(val data: KtorStoreItemRowDto)
@Serializable
private data class KtorStoreOffersResponse(val data: List<KtorStoreOfferRowDto>)
@Serializable
private data class KtorStoreOfferResponse(val data: KtorStoreOfferRowDto)
@Serializable
private data class KtorLimitedResourcesResponse(val data: List<KtorLimitedResourceRowDto>)
@Serializable
private data class KtorLimitedResourceResponse(val data: KtorLimitedResourceRowDto)
@Serializable
private data class KtorBurstCampaignsResponse(val data: List<KtorBurstCampaignRowDto>)
@Serializable
private data class KtorBurstCampaignResponse(val data: KtorBurstCampaignRowDto)

// Defaults mirror AdminV1Routes.kt's server-side DTOs exactly. The backend
// response Json (Application.kt) does not set encodeDefaults = true, so a
// field whose value equals its Kotlin default is omitted from the wire
// response entirely -- these row DTOs must tolerate that the same way every
// other KtorXxxRepositoryImpl DTO in this codebase does (e.g.
// KtorAdminV1ContentRepositoryImpl's AdminEspecialidadDto).
@Serializable
private data class KtorStoreItemRowDto(
    val id: Int? = null,
    val nombre: String = "",
    @SerialName("tipo_articulo") val type: String = "",
    @SerialName("precio_tickets") val priceTickets: Int = 0,
    val activo: Boolean = true,
)

@Serializable
private data class KtorStoreOfferRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val itemId: Int = 0,
    val titulo: String = "",
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("special_price_tickets") val specialPriceTickets: Int? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val status: String = "draft",
)

@Serializable
private data class KtorLimitedResourceRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val itemId: Int = 0,
    @SerialName("recurso_id") val resourceId: Int = 0,
    val titulo: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("access_duration_hours") val accessDurationHours: Int = 0,
    val status: String = "draft",
)

@Serializable
private data class KtorBurstCampaignRowDto(
    val id: Int? = null,
    val titulo: String = "",
    val descripcion: String = "",
    val tipo: String = "daily_streak",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val multiplier: Double = 1.0,
    @SerialName("reward_tickets") val rewardTickets: Int = 0,
    @SerialName("linked_exam_id") val linkedExamId: Int? = null,
    @SerialName("linked_store_item_id") val linkedStoreItemId: Int? = null,
    val status: String = "draft",
)

private fun KtorStoreItemRowDto.toDomain() = StoreItem(
    id = id?.toString().orEmpty(),
    name = nombre,
    description = "",
    type = type.toStoreItemType(),
    priceTickets = priceTickets,
    status = if (activo) CommerceStatus.ACTIVE else CommerceStatus.DRAFT,
    sortOrder = id ?: 0,
    createdAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0),
)

private fun StoreItem.toRowDto() = KtorStoreItemRowDto(
    id = id.toIntOrNull(),
    nombre = name,
    type = type.toDatabaseType(),
    priceTickets = priceTickets,
    activo = status == CommerceStatus.ACTIVE,
)

private fun KtorStoreOfferRowDto.toDomain() = StoreOffer(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    title = titulo,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    status = status.toCommerceStatus(),
)

private fun StoreOffer.toRowDto() = KtorStoreOfferRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    titulo = title,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    status = status.name.lowercase(),
)

private fun KtorLimitedResourceRowDto.toDomain() = LimitedResourceWindow(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    resourceId = resourceId.toString(),
    title = titulo,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    accessDurationHours = accessDurationHours,
    status = status.toCommerceStatus(),
)

private fun LimitedResourceWindow.toRowDto() = KtorLimitedResourceRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    resourceId = resourceId.toIntOrNull() ?: 0,
    titulo = title,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    accessDurationHours = accessDurationHours,
    status = status.name.lowercase(),
)

private fun KtorBurstCampaignRowDto.toDomain() = BurstCampaign(
    id = id?.toString().orEmpty(),
    title = titulo,
    description = descripcion,
    type = tipo.toBurstCampaignType(),
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    multiplier = multiplier,
    rewardTickets = rewardTickets,
    linkedExamId = linkedExamId?.toString(),
    linkedStoreItemId = linkedStoreItemId?.toString(),
    status = status.toCommerceStatus(),
)

private fun BurstCampaign.toRowDto() = KtorBurstCampaignRowDto(
    id = id.toIntOrNull(),
    titulo = title,
    descripcion = description,
    tipo = type.name.lowercase(),
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    multiplier = multiplier,
    rewardTickets = rewardTickets,
    linkedExamId = linkedExamId?.toIntOrNull(),
    linkedStoreItemId = linkedStoreItemId?.toIntOrNull(),
    status = status.name.lowercase(),
)

private fun String.toStoreItemType() = when (lowercase()) {
    "protector" -> StoreItemType.PROTECTOR
    "streak", "racha" -> StoreItemType.STREAK
    "potenciador", "potentiator" -> StoreItemType.POTENTIATOR
    else -> StoreItemType.CONSUMABLE
}

private fun StoreItemType.toDatabaseType() = when (this) {
    StoreItemType.PROTECTOR -> "protector"
    StoreItemType.STREAK -> "racha"
    StoreItemType.POTENTIATOR -> "potenciador"
    StoreItemType.CONSUMABLE,
    StoreItemType.EXAM_ACCESS,
    StoreItemType.RESOURCE,
    -> "consumible"
}

private fun String.toBurstCampaignType() = when (lowercase()) {
    "exam_burst" -> BurstCampaignType.EXAM_BURST
    "ticket_multiplier" -> BurstCampaignType.TICKET_MULTIPLIER
    "resource_unlock" -> BurstCampaignType.RESOURCE_UNLOCK
    else -> BurstCampaignType.DAILY_STREAK
}

private fun String.toCommerceStatus() = when (lowercase()) {
    "active" -> CommerceStatus.ACTIVE
    "scheduled" -> CommerceStatus.SCHEDULED
    "expired" -> CommerceStatus.EXPIRED
    "archived" -> CommerceStatus.ARCHIVED
    else -> CommerceStatus.DRAFT
}
