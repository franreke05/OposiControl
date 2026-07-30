package com.oposicontrol.admin.features.moderation.presentation

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
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

class ModerationViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val logger: Logger = NoOpLogger,
) : MviViewModel<ModerationState, ModerationIntent>(
    initialState = ModerationState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allActions: List<ModerationActionRow> = emptyList()

    init {
        analyticsTracker.trackScreen("moderation")
        dispatch(ModerationIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ModerationIntent) {
        when (intent) {
            ModerationIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorActionType = "warning",
                    editorTargetUser = "",
                    editorExecutor = "Admin principal",
                    editorReason = "",
                    actionState = UiAsync.Idle,
                )
            }

            ModerationIntent.Refresh -> refresh()
            ModerationIntent.Save -> save()
            is ModerationIntent.Select -> select(intent.id)
            is ModerationIntent.UpdateActionType -> updateState { it.copy(editorActionType = intent.value) }
            is ModerationIntent.UpdateExecutor -> updateState { it.copy(editorExecutor = intent.value) }
            is ModerationIntent.UpdateReason -> updateState { it.copy(editorReason = intent.value) }
            is ModerationIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is ModerationIntent.UpdateTargetUser -> updateState { it.copy(editorTargetUser = intent.value) }
        }
    }

    private fun select(id: String) {
        val item = allActions.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorActionType = item.actionType,
                editorTargetUser = item.targetUser,
                editorExecutor = item.executor,
                editorReason = item.reason,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allActions.filter { action ->
            query.isBlank() || action.targetUser.lowercase().contains(query) || action.actionType.lowercase().contains(query)
        }
        updateState { it.copy(actions = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        updateState { it.copy(actions = UiAsync.Loading) }
        when (
            val result = traceOperation(
                operation = "moderation.refresh",
                successCount = { it.size },
            ) {
                repository.getModerationActions()
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allActions = result.value.map { it.toRow() }
                publish()
                if (currentState().selectedId == null) allActions.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private suspend fun save() {
        if (currentState().editorTargetUser.isBlank() || currentState().editorReason.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Usuario y razon son obligatorios."))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val row = ModerationAction(
            id = currentState().selectedId.orEmpty(),
            actionType = currentState().editorActionType,
            targetUser = currentState().editorTargetUser,
            executor = currentState().editorExecutor,
            reason = currentState().editorReason,
            createdAt = "",
        )
        when (
            val result = traceOperation(operation = "moderation.action.save") {
                repository.appendModerationAction(row)
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value.toRow()
                allActions = listOf(saved) + allActions.filterNot { it.id == saved.id }
                analyticsTracker.trackAction("moderation_action_save", mapOf("action_id" to saved.id))
                updateState { it.copy(selectedId = saved.id, actionState = UiAsync.Success(Unit)) }
                publish()
                select(saved.id)
            }
        }
    }

    private fun ModerationAction.toRow() = ModerationActionRow(
        id = id,
        actionType = actionType,
        targetUser = targetUser,
        executor = executor,
        reason = reason,
        createdAt = createdAt,
    )

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
        screen = "moderation",
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
