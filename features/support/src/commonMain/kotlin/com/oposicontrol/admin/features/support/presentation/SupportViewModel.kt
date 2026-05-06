package com.oposicontrol.admin.features.support.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository

class SupportViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
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
        when (val result = repository.getSupportConversations()) {
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
        when (val result = repository.getSupportMessages(id)) {
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
            val result = repository.sendSupportMessage(
                conversationId = conversationId,
                assignee = currentState().assignee,
                text = currentState().replyText,
                internal = currentState().sendAsInternalNote,
            )
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
}
