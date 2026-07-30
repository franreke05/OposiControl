package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.logging.SafeLogField
import com.oposicontrol.admin.core.common.logging.withOperationContext
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreItemType
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.usecase.commerce.ObserveBurstCampaignsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveLimitedResourcesUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreItemsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreOffersUseCase
import com.oposicontrol.admin.domain.usecase.commerce.RefreshCommerceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertBurstCampaignUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertLimitedResourceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreItemUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreOfferUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.time.Clock
import kotlin.time.TimeSource

class StoreViewModel(
    dispatcherProvider: DispatcherProvider,
    observeStoreItemsUseCase: ObserveStoreItemsUseCase,
    observeStoreOffersUseCase: ObserveStoreOffersUseCase,
    observeLimitedResourcesUseCase: ObserveLimitedResourcesUseCase,
    observeBurstCampaignsUseCase: ObserveBurstCampaignsUseCase,
    private val refreshCommerceUseCase: RefreshCommerceUseCase,
    private val upsertStoreItemUseCase: UpsertStoreItemUseCase,
    private val upsertStoreOfferUseCase: UpsertStoreOfferUseCase,
    private val upsertLimitedResourceUseCase: UpsertLimitedResourceUseCase,
    private val upsertBurstCampaignUseCase: UpsertBurstCampaignUseCase,
    private val analyticsTracker: AnalyticsTracker,
    private val logger: Logger = NoOpLogger,
) : MviViewModel<StoreState, StoreIntent>(
    initialState = StoreState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<StoreItem> = emptyList()
    private var allOffers: List<StoreOffer> = emptyList()
    private var allLimitedResources: List<LimitedResourceWindow> = emptyList()
    private var allBursts: List<BurstCampaign> = emptyList()

    init {
        scope.launch {
            observeStoreItemsUseCase().collectLatest {
                allItems = it
                publish()
                if (currentState().selectedId == null) it.firstOrNull()?.let { item -> select(item.id) }
            }
        }
        scope.launch {
            observeStoreOffersUseCase().collectLatest {
                allOffers = it
                publish()
            }
        }
        scope.launch {
            observeLimitedResourcesUseCase().collectLatest {
                allLimitedResources = it
                publish()
            }
        }
        scope.launch {
            observeBurstCampaignsUseCase().collectLatest {
                allBursts = it
                publish()
            }
        }
        scope.launch {
            traceOperation(operation = "commerce.refresh") {
                refreshCommerceUseCase()
            }
        }
        analyticsTracker.trackScreen("store")
    }

    override suspend fun handleIntent(intent: StoreIntent) {
        when (intent) {
            StoreIntent.CreateNew -> createNew()
            StoreIntent.Save -> save()
            StoreIntent.SaveOffer -> saveOffer()
            StoreIntent.SaveLimitedResource -> saveLimitedResource()
            StoreIntent.SaveBurst -> saveBurst()
            is StoreIntent.Select -> select(intent.id)
            is StoreIntent.UpdateDescription -> updateState { it.copy(editorDescription = intent.value) }
            is StoreIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is StoreIntent.UpdatePriceTickets -> updateState { it.copy(editorPriceTickets = intent.value) }
            is StoreIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
            is StoreIntent.UpdateStatus -> updateState { it.copy(editorStatus = intent.value) }
            is StoreIntent.UpdateType -> updateState { it.copy(editorType = intent.value) }
            is StoreIntent.UpdateOfferTitle -> updateState { it.copy(offerTitle = intent.value) }
            is StoreIntent.UpdateOfferDiscountPercent -> updateState { it.copy(offerDiscountPercent = intent.value) }
            is StoreIntent.UpdateOfferSpecialPriceTickets -> updateState { it.copy(offerSpecialPriceTickets = intent.value) }
            is StoreIntent.UpdateOfferStartsAt -> updateState { it.copy(offerStartsAt = intent.value) }
            is StoreIntent.UpdateOfferEndsAt -> updateState { it.copy(offerEndsAt = intent.value) }
            is StoreIntent.UpdateLimitedTitle -> updateState { it.copy(limitedTitle = intent.value) }
            is StoreIntent.UpdateLimitedResourceId -> updateState { it.copy(limitedResourceId = intent.value) }
            is StoreIntent.UpdateLimitedStartsAt -> updateState { it.copy(limitedStartsAt = intent.value) }
            is StoreIntent.UpdateLimitedEndsAt -> updateState { it.copy(limitedEndsAt = intent.value) }
            is StoreIntent.UpdateLimitedAccessHours -> updateState { it.copy(limitedAccessHours = intent.value) }
            is StoreIntent.UpdateBurstTitle -> updateState { it.copy(burstTitle = intent.value) }
            is StoreIntent.UpdateBurstDescription -> updateState { it.copy(burstDescription = intent.value) }
            is StoreIntent.UpdateBurstType -> updateState { it.copy(burstType = intent.value) }
            is StoreIntent.UpdateBurstLinkedExamId -> updateState { it.copy(burstLinkedExamId = intent.value) }
            is StoreIntent.UpdateBurstMultiplier -> updateState { it.copy(burstMultiplier = intent.value) }
            is StoreIntent.UpdateBurstRewardTickets -> updateState { it.copy(burstRewardTickets = intent.value) }
            is StoreIntent.UpdateBurstStartsAt -> updateState { it.copy(burstStartsAt = intent.value) }
            is StoreIntent.UpdateBurstEndsAt -> updateState { it.copy(burstEndsAt = intent.value) }
        }
    }

    private fun createNew() {
        updateState {
            it.copy(
                selectedId = null,
                editorName = "",
                editorDescription = "",
                editorType = "consumible",
                editorPriceTickets = "0",
                editorStatus = "draft",
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorName = item.name,
                editorDescription = item.description,
                editorType = item.type.name.lowercase(),
                editorPriceTickets = item.priceTickets.toString(),
                editorStatus = item.status.name.lowercase(),
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filteredItems = allItems.filter { item ->
            query.isBlank() || item.name.lowercase().contains(query) || item.type.name.lowercase().contains(query)
        }
        updateState {
            it.copy(
                items = if (filteredItems.isEmpty()) UiAsync.Empty else UiAsync.Success(filteredItems.map { item -> item.toRow() }),
                offers = if (allOffers.isEmpty()) UiAsync.Empty else UiAsync.Success(allOffers.map { offer -> offer.toRow() }),
                limitedResources = if (allLimitedResources.isEmpty()) UiAsync.Empty else UiAsync.Success(allLimitedResources.map { resource -> resource.toRow() }),
                bursts = if (allBursts.isEmpty()) UiAsync.Empty else UiAsync.Success(allBursts.map { burst -> burst.toRow() }),
            )
        }
    }

    private suspend fun save() {
        val price = currentState().editorPriceTickets.toIntOrNull()
        if (price == null) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("El precio debe ser numerico."))) }
            return
        }
        val existing = allItems.firstOrNull { it.id == currentState().selectedId }
        val now = Clock.System.now()
        val item = StoreItem(
            id = existing?.id.orEmpty(),
            name = currentState().editorName.trim(),
            description = currentState().editorDescription.trim(),
            type = currentState().editorType.toStoreItemType(),
            priceTickets = price,
            status = currentState().editorStatus.toCommerceStatus(),
            sortOrder = existing?.sortOrder ?: allItems.size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (
            val result = traceOperation(operation = "commerce.item.save") {
                upsertStoreItemUseCase(item)
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("store_item_save", mapOf("item_id" to result.value.id))
                updateState { it.copy(selectedId = result.value.id, actionState = UiAsync.Success(Unit)) }
                select(result.value.id, resetActionState = false)
            }
        }
    }

    private suspend fun saveOffer() {
        val itemId = currentState().selectedId ?: return missingItem()
        val offer = StoreOffer(
            id = "",
            itemId = itemId,
            title = currentState().offerTitle.trim(),
            discountPercent = currentState().offerDiscountPercent.toIntOrNull(),
            specialPriceTickets = currentState().offerSpecialPriceTickets.toIntOrNull(),
            startsAt = currentState().offerStartsAt.toInstantOrNull(),
            endsAt = currentState().offerEndsAt.toInstantOrNull(),
            status = CommerceStatus.SCHEDULED,
        )
        saveChild(
            operation = "commerce.offer.save",
            analyticsAction = "store_offer_save",
        ) {
            upsertStoreOfferUseCase(offer)
        }
    }

    private suspend fun saveLimitedResource() {
        val itemId = currentState().selectedId ?: return missingItem()
        val window = LimitedResourceWindow(
            id = "",
            itemId = itemId,
            resourceId = currentState().limitedResourceId.trim(),
            title = currentState().limitedTitle.trim(),
            startsAt = currentState().limitedStartsAt.toInstantOrNull(),
            endsAt = currentState().limitedEndsAt.toInstantOrNull(),
            accessDurationHours = currentState().limitedAccessHours.toIntOrNull() ?: 72,
            status = CommerceStatus.SCHEDULED,
        )
        saveChild(
            operation = "commerce.limited_resource.save",
            analyticsAction = "limited_resource_save",
        ) {
            upsertLimitedResourceUseCase(window)
        }
    }

    private suspend fun saveBurst() {
        val linkedExamId = currentState().burstLinkedExamId.trim()
        if (linkedExamId.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("La rafaga necesita un examen vinculado."))) }
            return
        }
        val campaign = BurstCampaign(
            id = "",
            title = currentState().burstTitle.trim(),
            description = currentState().burstDescription.trim(),
            type = BurstCampaignType.EXAM_BURST,
            startsAt = currentState().burstStartsAt.toInstantOrNull(),
            endsAt = currentState().burstEndsAt.toInstantOrNull(),
            multiplier = currentState().burstMultiplier.toDoubleOrNull() ?: 1.0,
            rewardTickets = currentState().burstRewardTickets.toIntOrNull() ?: 0,
            linkedExamId = linkedExamId,
            linkedStoreItemId = null,
            status = CommerceStatus.SCHEDULED,
        )
        saveChild(
            operation = "commerce.burst.save",
            analyticsAction = "burst_campaign_save",
        ) {
            upsertBurstCampaignUseCase(campaign)
        }
    }

    private fun missingItem() {
        updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona o guarda un articulo antes."))) }
    }

    private suspend fun <T> saveChild(
        operation: String,
        analyticsAction: String,
        block: suspend () -> AppResult<T>,
    ) {
        val result = traceOperation(operation = operation, block = block)
        when (result) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(analyticsAction)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun StoreItem.toRow() = StoreItemRow(
        id = id,
        name = name,
        type = type.name.lowercase(),
        priceTickets = priceTickets,
        status = status.name.lowercase(),
    )

    private fun StoreOffer.toRow() = StoreOfferRow(
        id = id,
        itemId = itemId,
        title = title,
        benefit = discountPercent?.let { "$it%" } ?: "${specialPriceTickets ?: 0} tickets",
        window = "${startsAt?.toString().orEmpty()} -> ${endsAt?.toString().orEmpty()}",
        status = status.name.lowercase(),
    )

    private fun LimitedResourceWindow.toRow() = LimitedResourceRow(
        id = id,
        itemId = itemId,
        title = title,
        durationHours = accessDurationHours,
        window = "${startsAt?.toString().orEmpty()} -> ${endsAt?.toString().orEmpty()}",
        status = status.name.lowercase(),
    )

    private fun BurstCampaign.toRow() = BurstCampaignRow(
        id = id,
        title = title,
        type = type.name.lowercase(),
        reward = "x$multiplier + $rewardTickets tickets",
        linkedExamId = linkedExamId,
        linkedItemId = linkedStoreItemId,
        status = status.name.lowercase(),
    )

    private fun String.toStoreItemType() = when (lowercase()) {
        "protector" -> StoreItemType.PROTECTOR
        "streak", "racha" -> StoreItemType.STREAK
        "potenciador", "potentiator" -> StoreItemType.POTENTIATOR
        else -> StoreItemType.CONSUMABLE
    }

    private fun String.toCommerceStatus() = when (lowercase()) {
        "active" -> CommerceStatus.ACTIVE
        "scheduled" -> CommerceStatus.SCHEDULED
        "expired" -> CommerceStatus.EXPIRED
        "archived" -> CommerceStatus.ARCHIVED
        else -> CommerceStatus.DRAFT
    }

    private fun String.toBurstCampaignType() = when (lowercase()) {
        "exam_burst" -> BurstCampaignType.EXAM_BURST
        "ticket_multiplier" -> BurstCampaignType.TICKET_MULTIPLIER
        "resource_unlock" -> BurstCampaignType.RESOURCE_UNLOCK
        else -> BurstCampaignType.DAILY_STREAK
    }

    private fun String.toInstantOrNull(): Instant? = takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }

    private suspend fun <T> traceOperation(
        operation: String,
        successCount: (T) -> Int? = { null },
        block: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val context = OperationContext()
        val startedAt = TimeSource.Monotonic.markNow()
        logger.log(vmEvent(LogLevel.INFO, "$operation.start", operation, context))
        return try {
            val result = withOperationContext(context) { block() }
            when (result) {
                is AppResult.Failure -> logger.log(
                    vmEvent(
                        level = LogLevel.ERROR,
                        event = "$operation.failure",
                        operation = operation,
                        context = context,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                        fields = mapOf(
                            SafeLogField.RESULT to "failure",
                            SafeLogField.ERROR_TYPE to result.error.safeType(),
                            SafeLogField.RETRYABLE to result.error.isRetryable().toString(),
                        ),
                    ),
                )

                is AppResult.Success -> {
                    val fields = mutableMapOf(SafeLogField.RESULT to "success")
                    successCount(result.value)?.let { fields[SafeLogField.COUNT] = it.toString() }
                    logger.log(
                        vmEvent(
                            level = LogLevel.INFO,
                            event = "$operation.success",
                            operation = operation,
                            context = context,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            fields = fields,
                        ),
                    )
                }
            }
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.log(
                vmEvent(
                    level = LogLevel.ERROR,
                    event = "$operation.failure",
                    operation = operation,
                    context = context,
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    fields = mapOf(
                        SafeLogField.RESULT to "failure",
                        SafeLogField.ERROR_TYPE to (throwable::class.simpleName ?: "Throwable"),
                        SafeLogField.RETRYABLE to "false",
                    ),
                ),
            )
            throw throwable
        }
    }

    private fun vmEvent(
        level: LogLevel,
        event: String,
        operation: String,
        context: OperationContext,
        durationMs: Long? = null,
        fields: Map<SafeLogField, String> = emptyMap(),
    ) = LogEvent(
        level = level,
        tag = LogTag.VIEW_MODEL,
        event = event,
        screen = "store",
        layer = LogLayer.VIEW_MODEL,
        operation = operation,
        correlationId = context.correlationId,
        durationMs = durationMs,
        fields = fields,
    )

    private fun AppError.safeType(): String = when (this) {
        is AppError.Network -> "network"
        is AppError.Authentication -> "authentication"
        is AppError.Authorization -> "authorization"
        is AppError.Validation -> "validation"
        is AppError.NotFound -> "not_found"
        is AppError.Conflict -> "conflict"
        is AppError.RateLimited -> "rate_limited"
        is AppError.Unknown -> "unknown"
    }

    private fun AppError.isRetryable(): Boolean =
        this is AppError.Network || this is AppError.RateLimited
}
