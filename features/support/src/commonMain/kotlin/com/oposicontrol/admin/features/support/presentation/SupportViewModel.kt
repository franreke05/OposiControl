package com.oposicontrol.admin.features.support.presentation

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
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

class SupportViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val logger: Logger = NoOpLogger,
) : MviViewModel<SupportState, SupportIntent>(
    initialState = SupportState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allConversations: List<SupportConversation> = emptyList()

    init {
        analyticsTracker.trackScreen("support")
        dispatch(SupportIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SupportIntent) {
        when (intent) {
            SupportIntent.Refresh -> refresh()
            SupportIntent.SendReply -> sendReply()
            is SupportIntent.SelectConversation -> selectConversation(intent.id)
            is SupportIntent.ToggleInternalNote -> updateState { it.copy(sendAsInternalNote = intent.value) }
            is SupportIntent.UpdateAssignee -> updateState { it.copy(assignee = intent.value) }
            is SupportIntent.UpdateReplyText -> updateState { it.copy(replyText = intent.value) }
            is SupportIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishConversations()
            }

            is SupportIntent.UpdateStatusFilter -> {
                updateState { it.copy(statusFilter = intent.value) }
                publishConversations()
            }
        }
    }

    private fun publishConversations() {
        val query = currentState().searchQuery.trim().lowercase()
        val status = currentState().statusFilter.trim().lowercase()
        val filtered = allConversations.filter { conversation ->
            (query.isBlank() || conversation.userName.lowercase().contains(query) || conversation.subject.lowercase().contains(query)) &&
                (status == "all" || conversation.status.lowercase() == status)
        }
        updateState { it.copy(conversations = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        updateState { it.copy(conversations = UiAsync.Loading) }
        when (
            val result = traceOperation(
                operation = "support.refresh",
                successCount = { it.size },
            ) {
                repository.getSupportConversations()
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(conversations = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allConversations = result.value.map { it.toConversation() }
                publishConversations()
                if (currentState().selectedConversationId == null) allConversations.firstOrNull()?.let { selectConversation(it.id) }
            }
        }
    }

    private suspend fun selectConversation(id: String) {
        val conversation = allConversations.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedConversationId = id,
                assignee = conversation.assignedTo,
                replyText = "",
                sendAsInternalNote = false,
                messages = UiAsync.Loading,
                actionState = UiAsync.Idle,
            )
        }
        when (
            val result = traceOperation(
                operation = "support.messages.load",
                successCount = { it.size },
            ) {
                repository.getSupportMessages(id)
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(messages = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(messages = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.map { message -> message.toItem() }))
            }
        }
    }

    private suspend fun sendReply() {
        val conversationId = currentState().selectedConversationId
            ?: run {
                updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una conversacion."))) }
                return
            }
        if (currentState().replyText.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("El mensaje no puede estar vacio."))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (
            val result = traceOperation(operation = "support.reply.send") {
                repository.sendSupportMessage(
                    conversationId = conversationId,
                    assignee = currentState().assignee,
                    text = currentState().replyText,
                    internal = currentState().sendAsInternalNote,
                )
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "support_send_reply",
                    mapOf("conversation_id" to conversationId, "internal" to currentState().sendAsInternalNote.toString()),
                )
                updateState { it.copy(replyText = "", sendAsInternalNote = false, actionState = UiAsync.Success(Unit)) }
                refresh()
                selectConversation(conversationId)
            }
        }
    }

    private fun SupportConversationSummary.toConversation() = SupportConversation(
        id = id,
        userName = userName,
        subject = subject,
        status = status,
        assignedTo = assignedTo,
        lastMessage = lastMessage,
    )

    private fun SupportMessage.toItem() = SupportMessageItem(
        id = id,
        authorName = authorName,
        text = text,
        timestamp = timestamp,
        internal = internal,
        attachmentLabel = attachmentLabel,
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
        screen = "support",
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
