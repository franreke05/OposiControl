package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class SupabaseCommerceRepositoryImpl(
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

    override suspend fun refreshCommerce(): AppResult<Unit> {
        val token = requireAccessToken("cargar tienda") ?: return authFailure("cargar tienda")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            items.value = client.get("${appConfig.supabaseUrl}/rest/v1/articulo_tienda?select=*&deleted_at=is.null&order=nombre.asc") {
                supabaseHeaders(token)
            }.body<List<StoreItemRowDto>>().map { it.toDomain() }
            offers.value = runCatching {
                client.get("${appConfig.supabaseUrl}/rest/v1/oferta_tienda?select=*&deleted_at=is.null&order=starts_at.desc") {
                    supabaseHeaders(token)
                }.body<List<StoreOfferRowDto>>().map { it.toDomain() }
            }.getOrElse { emptyList() }
            limited.value = runCatching {
                client.get("${appConfig.supabaseUrl}/rest/v1/recurso_temporal_tienda?select=*&deleted_at=is.null&order=starts_at.desc") {
                    supabaseHeaders(token)
                }.body<List<LimitedResourceRowDto>>().map { it.toDomain() }
            }.getOrElse { emptyList() }
            bursts.value = runCatching {
                client.get("${appConfig.supabaseUrl}/rest/v1/campana_rafaga?select=*&deleted_at=is.null&order=starts_at.desc") {
                    supabaseHeaders(token)
                }.body<List<BurstCampaignRowDto>>().map { it.toDomain() }
            }.getOrElse { emptyList() }
            Unit
        }
    }

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> {
        val token = requireAccessToken("guardar articulo") ?: return authFailure("guardar articulo")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val saved = adapter.safeCall {
            client.post("${appConfig.supabaseUrl}/rest/v1/articulo_tienda?on_conflict=id") {
                supabaseHeaders(token)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(item.toRowDto()))
            }.body<List<StoreItemRowDto>>().first().toDomain()
        }
        if (saved is AppResult.Success) refreshCommerce()
        return saved
    }

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> = upsert(
        action = "guardar oferta",
        path = "oferta_tienda",
        value = offer,
        toRow = StoreOffer::toRowDto,
        fromRows = { it.body<List<StoreOfferRowDto>>().first().toDomain() },
    )

    override suspend fun upsertLimitedResource(window: LimitedResourceWindow): AppResult<LimitedResourceWindow> = upsert(
        action = "guardar recurso temporal",
        path = "recurso_temporal_tienda",
        value = window,
        toRow = LimitedResourceWindow::toRowDto,
        fromRows = { it.body<List<LimitedResourceRowDto>>().first().toDomain() },
    )

    override suspend fun upsertBurstCampaign(campaign: BurstCampaign): AppResult<BurstCampaign> = upsert(
        action = "guardar rafaga",
        path = "campana_rafaga",
        value = campaign,
        toRow = BurstCampaign::toRowDto,
        fromRows = { it.body<List<BurstCampaignRowDto>>().first().toDomain() },
    )

    private suspend fun <T, R> upsert(
        action: String,
        path: String,
        value: T,
        toRow: (T) -> R,
        fromRows: suspend (io.ktor.client.statement.HttpResponse) -> T,
    ): AppResult<T> {
        val token = requireAccessToken(action) ?: return authFailure(action)
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val response = client.post("${appConfig.supabaseUrl}/rest/v1/$path?on_conflict=id") {
                supabaseHeaders(token)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(toRow(value)))
            }
            fromRows(response)
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    private fun HttpRequestBuilder.supabaseHeaders(accessToken: String) {
        header("apikey", appConfig.supabaseAnonKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun requireAccessToken(action: String): String? = sessionLocalDataSource.currentSession()?.accessToken

    private fun authFailure(action: String): AppResult.Failure {
        return AppResult.Failure(AppError.Authentication("Necesitas iniciar sesion para $action."))
    }

    private fun ensureConfigured(): AppError.Validation? {
        if (appConfig.supabaseUrl.isBlank()) return AppError.Validation("SUPABASE_URL no esta configurado.")
        if (appConfig.supabaseAnonKey.isBlank()) return AppError.Validation("SUPABASE_ANON_KEY no esta configurado.")
        return null
    }
}

@Serializable
private data class StoreItemRowDto(
    val id: Int? = null,
    val nombre: String,
    @SerialName("tipo_articulo")
    val type: String,
    @SerialName("precio_tickets")
    val priceTickets: Int,
    val activo: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
private data class StoreOfferRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id")
    val itemId: Int,
    val titulo: String,
    @SerialName("discount_percent")
    val discountPercent: Int? = null,
    @SerialName("special_price_tickets")
    val specialPriceTickets: Int? = null,
    @SerialName("starts_at")
    val startsAt: String? = null,
    @SerialName("ends_at")
    val endsAt: String? = null,
    val status: String,
)

@Serializable
private data class LimitedResourceRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id")
    val itemId: Int,
    @SerialName("recurso_id")
    val resourceId: Int,
    val titulo: String,
    @SerialName("starts_at")
    val startsAt: String? = null,
    @SerialName("ends_at")
    val endsAt: String? = null,
    @SerialName("access_duration_hours")
    val accessDurationHours: Int,
    val status: String,
)

@Serializable
private data class BurstCampaignRowDto(
    val id: Int? = null,
    val titulo: String,
    val descripcion: String = "",
    val tipo: String,
    @SerialName("starts_at")
    val startsAt: String? = null,
    @SerialName("ends_at")
    val endsAt: String? = null,
    val multiplier: Double,
    @SerialName("reward_tickets")
    val rewardTickets: Int,
    @SerialName("linked_exam_id")
    val linkedExamId: Int? = null,
    @SerialName("linked_store_item_id")
    val linkedStoreItemId: Int? = null,
    val status: String,
)

private fun StoreItemRowDto.toDomain() = StoreItem(
    id = id?.toString().orEmpty(),
    name = nombre,
    description = "",
    type = type.toStoreItemType(),
    priceTickets = priceTickets,
    status = if (activo) CommerceStatus.ACTIVE else CommerceStatus.DRAFT,
    sortOrder = id ?: 0,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

private fun StoreItem.toRowDto() = StoreItemRowDto(
    id = id.toIntOrNull(),
    nombre = name,
    type = type.toDatabaseType(),
    priceTickets = priceTickets,
    activo = status == CommerceStatus.ACTIVE,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun StoreOfferRowDto.toDomain() = StoreOffer(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    title = titulo,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    status = status.toCommerceStatus(),
)

private fun StoreOffer.toRowDto() = StoreOfferRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    titulo = title,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    status = status.name.lowercase(),
)

private fun LimitedResourceRowDto.toDomain() = LimitedResourceWindow(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    resourceId = resourceId.toString(),
    title = titulo,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    accessDurationHours = accessDurationHours,
    status = status.toCommerceStatus(),
)

private fun LimitedResourceWindow.toRowDto() = LimitedResourceRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    resourceId = resourceId.toIntOrNull() ?: 0,
    titulo = title,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    accessDurationHours = accessDurationHours,
    status = status.name.lowercase(),
)

private fun BurstCampaignRowDto.toDomain() = BurstCampaign(
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

private fun BurstCampaign.toRowDto() = BurstCampaignRowDto(
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
