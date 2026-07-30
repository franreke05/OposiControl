package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.api.AdminApiHeaders
import com.oposicontrol.admin.core.api.AdminApiRoutes
import com.oposicontrol.admin.core.api.BurstCampaignResponse
import com.oposicontrol.admin.core.api.BurstCampaignType as ApiBurstCampaignType
import com.oposicontrol.admin.core.api.CommerceStatus as ApiCommerceStatus
import com.oposicontrol.admin.core.api.CursorPage
import com.oposicontrol.admin.core.api.LimitedResourceResponse
import com.oposicontrol.admin.core.api.StoreItemResponse
import com.oposicontrol.admin.core.api.StoreItemType as ApiStoreItemType
import com.oposicontrol.admin.core.api.StoreOfferResponse
import com.oposicontrol.admin.core.api.UpsertBurstCampaignRequest
import com.oposicontrol.admin.core.api.UpsertLimitedResourceRequest
import com.oposicontrol.admin.core.api.UpsertStoreItemRequest
import com.oposicontrol.admin.core.api.UpsertStoreOfferRequest
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.core.network.BackendHttpException
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
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

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

    override suspend fun refreshCommerce(): AppResult<Unit> {
        val token = requireAccessToken() ?: return authFailure("cargar tienda")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall(operation = "commerce.refresh") {
            items.value = loadPage<StoreItemResponse>(AdminApiRoutes.STORE_ITEMS, token)
                .map(StoreItemResponse::toDomain)
            offers.value = loadPage<StoreOfferResponse>(AdminApiRoutes.STORE_OFFERS, token)
                .map(StoreOfferResponse::toDomain)
            limited.value = loadPage<LimitedResourceResponse>(AdminApiRoutes.LIMITED_RESOURCES, token)
                .map(LimitedResourceResponse::toDomain)
            bursts.value = loadPage<BurstCampaignResponse>(AdminApiRoutes.BURST_CAMPAIGNS, token)
                .map(BurstCampaignResponse::toDomain)
            Unit
        }
    }

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> = upsert(
        operation = "commerce.item.save",
        collectionPath = AdminApiRoutes.STORE_ITEMS,
        id = item.id,
        request = UpsertStoreItemRequest(
            name = item.name,
            description = item.description,
            type = item.type.toApi(),
            priceTickets = item.priceTickets.toLong(),
            status = item.status.toApi(),
            sortOrder = item.sortOrder,
            expectedVersion = item.version.takeIf { item.id.isNotBlank() },
        ),
        transform = StoreItemResponse::toDomain,
    )

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> = upsert(
        operation = "commerce.offer.save",
        collectionPath = AdminApiRoutes.STORE_OFFERS,
        id = offer.id,
        request = UpsertStoreOfferRequest(
            itemId = offer.itemId.toLongOrNull() ?: 0,
            title = offer.title,
            discountPercent = offer.discountPercent,
            specialPriceTickets = offer.specialPriceTickets?.toLong(),
            startsAt = offer.startsAt?.toString(),
            endsAt = offer.endsAt?.toString(),
            status = offer.status.toApi(),
            expectedVersion = offer.version.takeIf { offer.id.isNotBlank() },
        ),
        transform = StoreOfferResponse::toDomain,
    )

    override suspend fun upsertLimitedResource(
        window: LimitedResourceWindow,
    ): AppResult<LimitedResourceWindow> = upsert(
        operation = "commerce.limited-resource.save",
        collectionPath = AdminApiRoutes.LIMITED_RESOURCES,
        id = window.id,
        request = UpsertLimitedResourceRequest(
            itemId = window.itemId.toLongOrNull() ?: 0,
            resourceId = window.resourceId.toLongOrNull() ?: 0,
            title = window.title,
            startsAt = window.startsAt?.toString(),
            endsAt = window.endsAt?.toString(),
            accessDurationHours = window.accessDurationHours,
            status = window.status.toApi(),
            expectedVersion = window.version.takeIf { window.id.isNotBlank() },
        ),
        transform = LimitedResourceResponse::toDomain,
    )

    override suspend fun upsertBurstCampaign(
        campaign: BurstCampaign,
    ): AppResult<BurstCampaign> = upsert(
        operation = "commerce.burst-campaign.save",
        collectionPath = AdminApiRoutes.BURST_CAMPAIGNS,
        id = campaign.id,
        request = UpsertBurstCampaignRequest(
            title = campaign.title,
            description = campaign.description,
            type = campaign.type.toApi(),
            startsAt = campaign.startsAt?.toString(),
            endsAt = campaign.endsAt?.toString(),
            multiplier = campaign.multiplier,
            rewardTickets = campaign.rewardTickets.toLong(),
            linkedExamId = campaign.linkedExamId?.toLongOrNull(),
            linkedStoreItemId = campaign.linkedStoreItemId?.toLongOrNull(),
            status = campaign.status.toApi(),
            expectedVersion = campaign.version.takeIf { campaign.id.isNotBlank() },
        ),
        transform = BurstCampaignResponse::toDomain,
    )

    private suspend inline fun <reified Request : Any, reified Response : Any, Domain> upsert(
        operation: String,
        collectionPath: String,
        id: String,
        request: Request,
        crossinline transform: (Response) -> Domain,
    ): AppResult<Domain> {
        val token = requireAccessToken() ?: return authFailure("guardar cambios")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall(operation = operation) {
            val idempotencyKey = OperationContext().correlationId
            val response = if (id.isBlank()) {
                client.post(backendUrl(collectionPath)) {
                    expectSuccess = false
                    backendHeaders(token)
                    header(AdminApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            } else {
                val numericId = requireNotNull(id.toLongOrNull()) { "El ID comercial no es valido." }
                client.patch(backendUrl("$collectionPath/$numericId")) {
                    expectSuccess = false
                    backendHeaders(token)
                    header(AdminApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
            response.requireBackendSuccess(collectionPath)
            transform(response.body<Response>())
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    private suspend inline fun <reified Response : Any> loadPage(
        path: String,
        accessToken: String,
    ): List<Response> {
        val response = client.get(backendUrl(path)) {
            expectSuccess = false
            backendHeaders(accessToken)
        }
        response.requireBackendSuccess(path)
        return response.body<CursorPage<Response>>().items
    }

    private fun HttpRequestBuilder.backendHeaders(accessToken: String) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun requireAccessToken(): String? = sessionLocalDataSource.currentSession()?.accessToken
        ?.takeIf(String::isNotBlank)

    private fun authFailure(action: String): AppResult.Failure {
        return AppResult.Failure(AppError.Authentication("Necesitas iniciar sesion para $action."))
    }

    private fun ensureConfigured(): AppError.Validation? {
        if (appConfig.baseUrl.isBlank()) return AppError.Validation("BASE_URL no esta configurado.")
        return null
    }

    private fun backendUrl(path: String): String = "${appConfig.baseUrl.trimEnd('/')}$path"

    private suspend fun HttpResponse.requireBackendSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            throw BackendHttpException(status.value)
        }
    }
}

private fun StoreItemResponse.toDomain() = StoreItem(
    id = id.toString(),
    name = name,
    description = description,
    type = type.toDomain(),
    priceTickets = priceTickets.toInt(),
    status = status.toDomain(),
    sortOrder = sortOrder,
    createdAt = createdAt.toInstantOrEpoch(),
    updatedAt = updatedAt.toInstantOrEpoch(),
    version = version,
)

private fun StoreOfferResponse.toDomain() = StoreOffer(
    id = id.toString(),
    itemId = itemId.toString(),
    title = title,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets?.toInt(),
    startsAt = startsAt.toInstantOrNull(),
    endsAt = endsAt.toInstantOrNull(),
    status = status.toDomain(),
    version = version,
)

private fun LimitedResourceResponse.toDomain() = LimitedResourceWindow(
    id = id.toString(),
    itemId = itemId.toString(),
    resourceId = resourceId.toString(),
    title = title,
    startsAt = startsAt.toInstantOrNull(),
    endsAt = endsAt.toInstantOrNull(),
    accessDurationHours = accessDurationHours,
    status = status.toDomain(),
    version = version,
)

private fun BurstCampaignResponse.toDomain() = BurstCampaign(
    id = id.toString(),
    title = title,
    description = description,
    type = type.toDomain(),
    startsAt = startsAt.toInstantOrNull(),
    endsAt = endsAt.toInstantOrNull(),
    multiplier = multiplier,
    rewardTickets = rewardTickets.toInt(),
    linkedExamId = linkedExamId?.toString(),
    linkedStoreItemId = linkedStoreItemId?.toString(),
    status = status.toDomain(),
    version = version,
)

private fun StoreItemType.toApi() = when (this) {
    StoreItemType.CONSUMABLE -> ApiStoreItemType.CONSUMABLE
    StoreItemType.PROTECTOR -> ApiStoreItemType.PROTECTOR
    StoreItemType.STREAK -> ApiStoreItemType.STREAK
    StoreItemType.POTENTIATOR -> ApiStoreItemType.POTENTIATOR
    StoreItemType.EXAM_ACCESS -> ApiStoreItemType.EXAM_ACCESS
    StoreItemType.RESOURCE -> ApiStoreItemType.RESOURCE
}

private fun ApiStoreItemType.toDomain() = when (this) {
    ApiStoreItemType.CONSUMABLE -> StoreItemType.CONSUMABLE
    ApiStoreItemType.PROTECTOR -> StoreItemType.PROTECTOR
    ApiStoreItemType.STREAK -> StoreItemType.STREAK
    ApiStoreItemType.POTENTIATOR -> StoreItemType.POTENTIATOR
    ApiStoreItemType.EXAM_ACCESS -> StoreItemType.EXAM_ACCESS
    ApiStoreItemType.RESOURCE -> StoreItemType.RESOURCE
}

private fun BurstCampaignType.toApi() = when (this) {
    BurstCampaignType.DAILY_STREAK -> ApiBurstCampaignType.DAILY_STREAK
    BurstCampaignType.EXAM_BURST -> ApiBurstCampaignType.EXAM_BURST
    BurstCampaignType.TICKET_MULTIPLIER -> ApiBurstCampaignType.TICKET_MULTIPLIER
    BurstCampaignType.RESOURCE_UNLOCK -> ApiBurstCampaignType.RESOURCE_UNLOCK
}

private fun ApiBurstCampaignType.toDomain() = when (this) {
    ApiBurstCampaignType.DAILY_STREAK -> BurstCampaignType.DAILY_STREAK
    ApiBurstCampaignType.EXAM_BURST -> BurstCampaignType.EXAM_BURST
    ApiBurstCampaignType.TICKET_MULTIPLIER -> BurstCampaignType.TICKET_MULTIPLIER
    ApiBurstCampaignType.RESOURCE_UNLOCK -> BurstCampaignType.RESOURCE_UNLOCK
}

private fun CommerceStatus.toApi() = when (this) {
    CommerceStatus.DRAFT -> ApiCommerceStatus.DRAFT
    CommerceStatus.ACTIVE -> ApiCommerceStatus.ACTIVE
    CommerceStatus.SCHEDULED -> ApiCommerceStatus.SCHEDULED
    CommerceStatus.EXPIRED -> ApiCommerceStatus.EXPIRED
    CommerceStatus.ARCHIVED -> ApiCommerceStatus.ARCHIVED
}

private fun ApiCommerceStatus.toDomain() = when (this) {
    ApiCommerceStatus.DRAFT -> CommerceStatus.DRAFT
    ApiCommerceStatus.ACTIVE -> CommerceStatus.ACTIVE
    ApiCommerceStatus.SCHEDULED -> CommerceStatus.SCHEDULED
    ApiCommerceStatus.EXPIRED -> CommerceStatus.EXPIRED
    ApiCommerceStatus.ARCHIVED -> CommerceStatus.ARCHIVED
}

private fun String?.toInstantOrNull(): Instant? = this?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

private fun String.toInstantOrEpoch(): Instant = toInstantOrNull()
    ?: Instant.fromEpochMilliseconds(0)
