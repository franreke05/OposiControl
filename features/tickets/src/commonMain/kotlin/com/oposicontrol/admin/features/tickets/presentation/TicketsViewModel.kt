package com.oposicontrol.admin.features.tickets.presentation

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
import com.oposicontrol.admin.core.common.presentation.MviEffectViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class TicketsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val logger: Logger = NoOpLogger,
) : MviEffectViewModel<TicketsState, TicketsIntent, TicketsEffect>(
    initialState = TicketsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var wallets: List<WalletRow> = emptyList()
    private var walletRefreshJob: Job? = null
    private var walletRefreshGeneration = 0L
    private var transactionJob: Job? = null
    private var transactionGeneration = 0L

    init {
        analyticsTracker.trackScreen("tickets")
        dispatch(TicketsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: TicketsIntent) {
        when (intent) {
            TicketsIntent.Refresh -> refresh()
            TicketsIntent.ApplyAdjustment -> applyAdjustment()
            TicketsIntent.PrepareAdjustment -> updateState {
                it.copy(adjustmentAmount = "", adjustmentReason = "", actionState = UiAsync.Idle)
            }

            is TicketsIntent.SelectWallet -> selectWallet(intent.userId)
            is TicketsIntent.UpdateAdjustmentAmount -> updateState {
                it.copy(adjustmentAmount = intent.value, actionState = UiAsync.Idle)
            }

            is TicketsIntent.UpdateAdjustmentReason -> updateState {
                it.copy(adjustmentReason = intent.value, actionState = UiAsync.Idle)
            }

            is TicketsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishWallets()
            }
        }
    }

    private fun publishWallets() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = wallets.filter { wallet ->
            query.isBlank() || wallet.userName.lowercase().contains(query) || wallet.specialty.lowercase().contains(query)
        }
        updateState { it.copy(wallets = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private fun refresh() {
        val generation = ++walletRefreshGeneration
        walletRefreshJob?.cancel()
        updateState {
            it.copy(
                wallets = if (it.wallets is UiAsync.Success || it.wallets is UiAsync.Empty) {
                    it.wallets
                } else {
                    UiAsync.Loading
                },
                isRefreshing = true,
                refreshError = null,
            )
        }
        walletRefreshJob = scope.launch {
            val result = traceOperation(
                operation = "tickets.refresh",
                successCount = { it.size },
            ) {
                repository.getTicketWallets()
            }
            // WHY: cancelled network work can still finish, so stale wallet lists are ignored.
            if (generation != walletRefreshGeneration) return@launch
            when (result) {
                is AppResult.Failure -> updateState {
                    it.copy(
                        wallets = if (it.wallets is UiAsync.Success || it.wallets is UiAsync.Empty) {
                            it.wallets
                        } else {
                            UiAsync.Error(result.error)
                        },
                        isRefreshing = false,
                        refreshError = result.error,
                    )
                }

                is AppResult.Success -> {
                    wallets = result.value.map { it.toRow() }
                    publishWallets()
                    val state = currentState()
                    if (state.selectedUserId == null) {
                        wallets.firstOrNull()?.let { selectWallet(it.userId) }
                    } else if (wallets.none { it.userId == state.selectedUserId }) {
                        wallets.firstOrNull()?.let { selectWallet(it.userId) } ?: clearSelection()
                    }
                    updateState { it.copy(isRefreshing = false, refreshError = null) }
                }
            }
        }
    }

    private fun clearSelection() {
        transactionGeneration += 1
        transactionJob?.cancel()
        updateState {
            it.copy(
                selectedUserId = null,
                transactions = UiAsync.Empty,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun selectWallet(
        userId: WalletUserId,
        preserveActionState: Boolean = false,
    ) {
        val generation = ++transactionGeneration
        transactionJob?.cancel()
        updateState {
            val keepTransactions =
                it.selectedUserId == userId &&
                    (it.transactions is UiAsync.Success || it.transactions is UiAsync.Empty)
            it.copy(
                selectedUserId = userId,
                transactions = if (keepTransactions) it.transactions else UiAsync.Loading,
                actionState = if (preserveActionState) it.actionState else UiAsync.Idle,
            )
        }
        transactionJob = scope.launch {
            val result = traceOperation(
                operation = "tickets.transactions.load",
                successCount = { it.size },
            ) {
                repository.getTicketTransactions(userId.value)
            }
            // WHY: only the latest wallet selection owns the transaction pane.
            if (generation != transactionGeneration || currentState().selectedUserId != userId) return@launch
            when (result) {
                is AppResult.Failure -> updateState { it.copy(transactions = UiAsync.Error(result.error)) }
                is AppResult.Success -> updateState {
                    it.copy(
                        transactions = if (result.value.isEmpty()) {
                            UiAsync.Empty
                        } else {
                            UiAsync.Success(result.value.map { tx -> tx.toMovement() })
                        },
                    )
                }
            }
        }
    }

    private suspend fun applyAdjustment() {
        val selectedUserId = currentState().selectedUserId
            ?: run {
                updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una cartera antes de ajustar saldo."))) }
                return
            }
        val state = currentState()
        if (
            state.adjustmentAmount.isBlank() &&
            state.adjustmentReason.isBlank() &&
            state.actionState is UiAsync.Success
        ) {
            return
        }
        val amount = state.adjustmentAmount.toLongOrNull()
        val reason = state.adjustmentReason.trim()
        if (amount == null || reason.isBlank()) {
            updateState {
                it.copy(
                    actionState = UiAsync.Error(
                        AppError.Validation("Introduce un importe entero y una razon."),
                    ),
                )
            }
            return
        }

        updateState { it.copy(actionState = UiAsync.Loading) }
        when (
            val result = traceOperation(operation = "tickets.adjust") {
                repository.appendTicketAdjustment(selectedUserId.value, amount, reason)
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val movement = result.value.toMovement()
                analyticsTracker.trackAction(
                    "tickets_adjust",
                    mapOf("user_id" to selectedUserId.value, "amount" to amount.toString()),
                )
                updateState {
                    val existing = (it.transactions as? UiAsync.Success)?.value.orEmpty()
                    it.copy(
                        transactions = UiAsync.Success(
                            (listOf(movement) + existing).distinctBy(TicketMovement::id),
                        ),
                        adjustmentAmount = "",
                        adjustmentReason = "",
                        actionState = UiAsync.Success(Unit),
                    )
                }
                emitEffect(TicketsEffect.AdjustmentApplied(selectedUserId, movement.id))
                refresh()
                selectWallet(selectedUserId, preserveActionState = true)
            }
        }
    }

    private fun TicketWallet.toRow() = WalletRow(
        userId = WalletUserId(userId),
        userName = userName,
        specialty = specialty,
        balance = balance,
        status = status,
    )

    private fun TicketTransaction.toMovement() = TicketMovement(
        id = TicketMovementId(id),
        type = type,
        amount = amount,
        reason = reason,
        occurredAt = occurredAt,
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
        screen = "tickets",
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
