package com.oposicontrol.admin.features.controlv1.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumBlockPattern
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumChannelValidator
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumModeration
import com.oposicontrol.admin.domain.model.ForumQueueItem
import com.oposicontrol.admin.domain.model.ForumQueueState
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.repository.ControlV1Repository

// ====================================================================================================================
// Cola de moderación
// ====================================================================================================================

/** La acción elegida sobre un contenido, a la espera de motivo y confirmación. */
data class ForumPendingAction(
    val item: ForumQueueItem,
    val action: ForumAction,
    val reason: String = "",
    val days: String = ForumModeration.MUTE_DAYS_DEFAULT.toString(),
    val error: String? = null,
)

data class ForumQueueUiState(
    val type: ForumContentType = ForumContentType.POST,
    val queue: ForumQueueState = ForumQueueState.PENDING,
    val items: UiAsync<List<ForumQueueItem>> = UiAsync.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val pending: ForumPendingAction? = null,
    val feedback: UiAsync<String> = UiAsync.Idle,
)

sealed interface ForumQueueIntent {
    data object Refresh : ForumQueueIntent
    data class SetType(val type: ForumContentType) : ForumQueueIntent
    data class SetQueue(val queue: ForumQueueState) : ForumQueueIntent
    data object LoadMore : ForumQueueIntent
    data class Choose(val itemId: String, val action: ForumAction) : ForumQueueIntent
    data class UpdateReason(val text: String) : ForumQueueIntent
    data class UpdateDays(val text: String) : ForumQueueIntent
    data object Confirm : ForumQueueIntent
    data object Dismiss : ForumQueueIntent
}

/**
 * Cola de moderación del foro. Las cuatro reglas que la gobiernan:
 *
 * 1. Solo se ofrecen las acciones que tienen sentido para el ESTADO del contenido ([ForumModeration.actionsFor]).
 * 2. Rechazar, ocultar y silenciar exigen motivo; silenciar exige además días (1..365).
 * 3. Nada se ejecuta sin pasar por [ForumQueueIntent.Confirm]: elegir la acción solo abre el diálogo.
 * 4. Tras cada acción se relee la cola: lo que ya no está pendiente desaparece, lo que falló sigue ahí.
 */
class ForumQueueViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<ForumQueueUiState, ForumQueueIntent>(ForumQueueUiState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(ForumQueueIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ForumQueueIntent) {
        when (intent) {
            ForumQueueIntent.Refresh -> load(reset = true)
            is ForumQueueIntent.SetType -> {
                updateState { it.copy(type = intent.type, items = UiAsync.Loading, nextCursor = null, pending = null) }
                load(reset = true)
            }
            is ForumQueueIntent.SetQueue -> {
                updateState { it.copy(queue = intent.queue, items = UiAsync.Loading, nextCursor = null, pending = null) }
                load(reset = true)
            }
            ForumQueueIntent.LoadMore -> load(reset = false)
            is ForumQueueIntent.Choose -> updateState { state ->
                val item = (state.items as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.itemId }
                if (item == null || intent.action !in ForumModeration.actionsFor(item.state)) state
                else state.copy(pending = ForumPendingAction(item, intent.action), feedback = UiAsync.Idle)
            }
            is ForumQueueIntent.UpdateReason -> updateState { it.copy(pending = it.pending?.copy(reason = intent.text, error = null)) }
            is ForumQueueIntent.UpdateDays -> updateState { it.copy(pending = it.pending?.copy(days = intent.text, error = null)) }
            ForumQueueIntent.Dismiss -> updateState { it.copy(pending = null) }
            ForumQueueIntent.Confirm -> confirm()
        }
    }

    private suspend fun load(reset: Boolean) {
        val state = currentState()
        val stateWire = state.queue.wireFor(state.type)
        if (!reset) {
            val cursor = state.nextCursor ?: return
            if (state.loadingMore) return
            updateState { it.copy(loadingMore = true) }
            when (val r = repository.forumQueue(state.type, stateWire, cursor)) {
                is AppResult.Failure -> updateState { it.copy(loadingMore = false, feedback = UiAsync.Error(r.error)) }
                is AppResult.Success -> updateState {
                    val merged = (it.items as? UiAsync.Success)?.value.orEmpty() + r.value.items
                    it.copy(items = UiAsync.Success(merged.distinctBy { item -> item.id }), nextCursor = r.value.nextCursor, loadingMore = false)
                }
            }
            return
        }
        when (val r = repository.forumQueue(state.type, stateWire, null)) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(r.error), nextCursor = null) }
            is AppResult.Success -> updateState {
                it.copy(items = if (r.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value.items), nextCursor = r.value.nextCursor)
            }
        }
    }

    private suspend fun confirm() {
        if (writing) return
        val pending = currentState().pending ?: return
        val error = ForumModeration.check(pending.action, pending.reason, pending.days)
        if (error != null) {
            updateState { it.copy(pending = pending.copy(error = error)) }
            return
        }
        writing = true
        try {
            updateState { it.copy(feedback = UiAsync.Loading) }
            val days = if (pending.action == ForumAction.MUTE) pending.days.trim().toInt() else null
            when (val r = repository.moderateForum(pending.item.type, pending.item.id, pending.action, pending.reason.trim().ifEmpty { null }, days)) {
                is AppResult.Failure -> updateState { it.copy(feedback = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(pending = null, feedback = UiAsync.Success("${pending.action.label}: hecho.")) }
                    load(reset = true)
                }
            }
        } finally {
            writing = false
        }
    }
}

// ====================================================================================================================
// Canales
// ====================================================================================================================

data class ForumChannelsUiState(
    val channels: UiAsync<List<ForumChannel>> = UiAsync.Loading,
    /** `null` con [editing] falso = nada abierto. */
    val selectedId: String? = null,
    val editing: Boolean = false,
    val draft: ForumChannelDraft = ForumChannelDraft.EMPTY,
    val errors: Map<String, String> = emptyMap(),
    val slugEdited: Boolean = false,
    val save: UiAsync<Unit> = UiAsync.Idle,
)

sealed interface ForumChannelsIntent {
    data object Refresh : ForumChannelsIntent
    data object New : ForumChannelsIntent
    data class Select(val id: String) : ForumChannelsIntent
    data class Update(val draft: ForumChannelDraft, val slugEdited: Boolean = false) : ForumChannelsIntent
    data object Save : ForumChannelsIntent
    data object Cancel : ForumChannelsIntent
}

class ForumChannelsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<ForumChannelsUiState, ForumChannelsIntent>(ForumChannelsUiState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(ForumChannelsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ForumChannelsIntent) {
        when (intent) {
            ForumChannelsIntent.Refresh -> refresh()
            ForumChannelsIntent.New -> updateState {
                it.copy(editing = true, selectedId = null, draft = ForumChannelDraft.EMPTY, errors = emptyMap(), slugEdited = false, save = UiAsync.Idle)
            }
            is ForumChannelsIntent.Select -> updateState { state ->
                val channel = (state.channels as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.id }
                if (channel == null) state
                else state.copy(editing = true, selectedId = channel.id, draft = ForumChannelDraft.from(channel), errors = emptyMap(), slugEdited = true, save = UiAsync.Idle)
            }
            is ForumChannelsIntent.Update -> updateState { state ->
                // En un canal nuevo el identificador se sugiere desde el nombre mientras nadie lo toque a mano.
                val slugTouched = state.slugEdited || intent.slugEdited
                val draft = if (!slugTouched && state.selectedId == null) {
                    intent.draft.copy(slug = ForumChannelValidator.suggestSlug(intent.draft.name))
                } else {
                    intent.draft
                }
                state.copy(draft = draft, slugEdited = slugTouched, errors = emptyMap(), save = UiAsync.Idle)
            }
            ForumChannelsIntent.Cancel -> updateState { it.copy(editing = false, selectedId = null, errors = emptyMap(), save = UiAsync.Idle) }
            ForumChannelsIntent.Save -> save()
        }
    }

    private suspend fun refresh() {
        if (currentState().channels !is UiAsync.Success) updateState { it.copy(channels = UiAsync.Loading) }
        when (val r = repository.forumChannels()) {
            is AppResult.Failure -> updateState { it.copy(channels = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState { it.copy(channels = if (r.value.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value)) }
        }
    }

    private suspend fun save() {
        if (writing) return
        val state = currentState()
        if (!state.editing) return
        val errors = ForumChannelValidator.check(state.draft)
        if (errors.isNotEmpty()) {
            updateState { it.copy(errors = errors) }
            return
        }
        writing = true
        try {
            updateState { it.copy(save = UiAsync.Loading) }
            val result = if (state.selectedId == null) repository.createForumChannel(state.draft)
            else repository.updateForumChannel(state.selectedId, state.draft)
            when (result) {
                is AppResult.Failure -> updateState { it.copy(save = UiAsync.Error(result.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(editing = false, selectedId = null, save = UiAsync.Success(Unit)) }
                    refresh()
                }
            }
        } finally {
            writing = false
        }
    }
}

// ====================================================================================================================
// Lista de bloqueo y restricciones
// ====================================================================================================================

data class ForumBlocksUiState(
    val patterns: UiAsync<List<String>> = UiAsync.Loading,
    val restrictions: UiAsync<List<ForumRestriction>> = UiAsync.Loading,
    val newPattern: String = "",
    val patternError: String? = null,
    val confirmRemove: String? = null,
    val confirmLift: ForumRestriction? = null,
    val feedback: UiAsync<String> = UiAsync.Idle,
)

sealed interface ForumBlocksIntent {
    data object Refresh : ForumBlocksIntent
    data class UpdatePattern(val text: String) : ForumBlocksIntent
    data object Add : ForumBlocksIntent
    data class RequestRemove(val pattern: String) : ForumBlocksIntent
    data object ConfirmRemove : ForumBlocksIntent
    data class RequestLift(val userId: String) : ForumBlocksIntent
    data object ConfirmLift : ForumBlocksIntent
    data object Dismiss : ForumBlocksIntent
}

class ForumBlocksViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<ForumBlocksUiState, ForumBlocksIntent>(ForumBlocksUiState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(ForumBlocksIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ForumBlocksIntent) {
        when (intent) {
            ForumBlocksIntent.Refresh -> refresh()
            is ForumBlocksIntent.UpdatePattern -> updateState { it.copy(newPattern = intent.text, patternError = null) }
            ForumBlocksIntent.Add -> add()
            is ForumBlocksIntent.RequestRemove -> updateState { it.copy(confirmRemove = intent.pattern) }
            ForumBlocksIntent.ConfirmRemove -> remove()
            is ForumBlocksIntent.RequestLift -> updateState { state ->
                val r = (state.restrictions as? UiAsync.Success)?.value?.firstOrNull { it.userId == intent.userId }
                if (r == null) state else state.copy(confirmLift = r)
            }
            ForumBlocksIntent.ConfirmLift -> lift()
            ForumBlocksIntent.Dismiss -> updateState { it.copy(confirmRemove = null, confirmLift = null) }
        }
    }

    private suspend fun refresh() {
        val patterns = repository.forumBlockList()
        val restrictions = repository.forumRestrictions()
        updateState {
            it.copy(
                patterns = when (patterns) {
                    is AppResult.Failure -> UiAsync.Error(patterns.error)
                    is AppResult.Success -> if (patterns.value.isEmpty()) UiAsync.Empty else UiAsync.Success(patterns.value)
                },
                restrictions = when (restrictions) {
                    is AppResult.Failure -> UiAsync.Error(restrictions.error)
                    is AppResult.Success -> if (restrictions.value.isEmpty()) UiAsync.Empty else UiAsync.Success(restrictions.value)
                },
            )
        }
    }

    private suspend fun add() {
        if (writing) return
        val raw = currentState().newPattern
        val error = ForumBlockPattern.check(raw)
        if (error != null) {
            updateState { it.copy(patternError = error) }
            return
        }
        writing = true
        try {
            when (val r = repository.addForumBlock(ForumBlockPattern.normalize(raw))) {
                is AppResult.Failure -> updateState { it.copy(feedback = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(newPattern = "", feedback = UiAsync.Success("Patrón añadido.")) }
                    refresh()
                }
            }
        } finally {
            writing = false
        }
    }

    private suspend fun remove() {
        if (writing) return
        val pattern = currentState().confirmRemove ?: return
        writing = true
        try {
            updateState { it.copy(confirmRemove = null) }
            when (val r = repository.removeForumBlock(pattern)) {
                is AppResult.Failure -> updateState { it.copy(feedback = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(feedback = UiAsync.Success("Patrón retirado.")) }
                    refresh()
                }
            }
        } finally {
            writing = false
        }
    }

    private suspend fun lift() {
        if (writing) return
        val target = currentState().confirmLift ?: return
        writing = true
        try {
            updateState { it.copy(confirmLift = null) }
            when (val r = repository.liftForumRestriction(target.userId)) {
                is AppResult.Failure -> updateState { it.copy(feedback = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(feedback = UiAsync.Success("Restricción levantada.")) }
                    refresh()
                }
            }
        } finally {
            writing = false
        }
    }
}

