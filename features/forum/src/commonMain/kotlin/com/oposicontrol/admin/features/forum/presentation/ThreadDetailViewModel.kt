package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.repository.ForumRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ThreadDetailViewModel(
    private val threadId: String,
    dispatcherProvider: DispatcherProvider,
    private val forumRepository: ForumRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ThreadDetailState, ThreadDetailIntent>(
    initialState = ThreadDetailState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("forum_thread_detail")
        dispatch(ThreadDetailIntent.Load)
    }

    override suspend fun handleIntent(intent: ThreadDetailIntent) {
        when (intent) {
            ThreadDetailIntent.Load -> loadAll()
            ThreadDetailIntent.PullToRefresh -> refresh()
            ThreadDetailIntent.LoadMoreReplies -> loadMoreReplies()
        }
    }

    // El hilo y la primera página de respuestas son independientes entre sí:
    // se piden en paralelo (coroutineScope + launch) para no hacer esperar al
    // usuario dos round-trips seguidos por nada.
    /**
     * [preserveContent] mantiene el hilo y las respuestas ya visibles mientras
     * se recargan. Solo lo usa el pull-to-refresh: ahí el indicador de
     * `PullToRefreshBox` ya comunica la carga, así que poner además
     * `thread`/`replies = Loading` borraba el contenido y mostraba el esqueleto
     * *debajo* del propio spinner (doble loader). Mismo criterio que
     * ReportsViewModel.load(asPullToRefresh).
     */
    private suspend fun loadAll(preserveContent: Boolean = false) {
        updateState {
            it.copy(
                thread = if (preserveContent) it.thread else UiAsync.Loading,
                replies = if (preserveContent) it.replies else UiAsync.Loading,
                nextRepliesCursor = null,
            )
        }
        coroutineScope {
            launch { loadThread() }
            launch { loadFirstReplyPage() }
        }
    }

    private suspend fun loadThread() {
        when (val result = forumRepository.getThread(threadId)) {
            is AppResult.Failure -> updateState { it.copy(thread = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(thread = UiAsync.Success(result.value)) }
        }
    }

    private suspend fun loadFirstReplyPage() {
        when (val result = forumRepository.listReplies(threadId, cursor = null)) {
            is AppResult.Failure -> updateState { it.copy(replies = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    replies = if (result.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.items),
                    nextRepliesCursor = result.value.nextCursor,
                )
            }
        }
    }

    private suspend fun refresh() {
        updateState { it.copy(refreshing = true) }
        loadAll(preserveContent = true)
        updateState { it.copy(refreshing = false) }
    }

    private suspend fun loadMoreReplies() {
        val state = currentState()
        val cursor = state.nextRepliesCursor
        // Guardas antes de cualquier punto de suspensión: en un dispatcher
        // confinado a un solo hilo (Main real, o StandardTestDispatcher en
        // tests) esto basta para que dos LoadMoreReplies casi simultáneos
        // nunca lancen dos páginas a la vez (mismo razonamiento que
        // ForumViewModel.loadMore).
        if (cursor == null || state.loadingMoreReplies || state.replies !is UiAsync.Success) return
        updateState { it.copy(loadingMoreReplies = true) }
        when (val result = forumRepository.listReplies(threadId, cursor = cursor)) {
            is AppResult.Failure -> updateState { it.copy(loadingMoreReplies = false) }
            is AppResult.Success -> updateState {
                val existing = (it.replies as? UiAsync.Success)?.value.orEmpty()
                val merged = (existing + result.value.items).distinctBy(ForumReply::id)
                it.copy(
                    replies = UiAsync.Success(merged),
                    nextRepliesCursor = result.value.nextCursor,
                    loadingMoreReplies = false,
                )
            }
        }
    }
}
