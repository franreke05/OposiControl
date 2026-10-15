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
            is SupportIntent.UpdateReplyText -> updateState { it.copy(replyText = intent.value) }
            is SupportIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishConversations()
            }
        }
    }

    private fun publishConversations() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allConversations.filter { conversation ->
            (query.isBlank() || conversation.userName.lowercase().contains(query) || conversation.subject.lowercase().contains(query))
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
        if (allConversations.none { it.id == id }) return
        updateState {
            it.copy(
                selectedConversationId = id,
                replyText = "",
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
        // Un envio en curso bloquea el siguiente: el boton ya se deshabilita, esto cubre el doble intent.
        if (currentState().actionState is UiAsync.Loading) return
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (
            val result = repository.sendSupportMessage(
                conversationId = conversationId,
                // El backend ignora el responsable: no se envia ninguno.
                assignee = "",
                text = currentState().replyText,
                internal = false, // MASTER7-API-006: the backend cannot store internal notes; never request one.
            )
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "support_send_reply",
                    mapOf("conversation_id" to conversationId),
                )
                updateState { it.copy(replyText = "") }
                refresh()
                selectConversation(conversationId)
                // Tras releer el hilo se confirma el envio (seleccionar limpia el estado de la accion).
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun SupportConversationSummary.toConversation() = SupportConversation(
        id = id,
        userName = userName,
        subject = subject,
        status = status,
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
