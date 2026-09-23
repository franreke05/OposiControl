package com.oposicontrol.admin.features.forum.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ForumReply
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import kotlin.time.Clock
import kotlinx.datetime.Instant

@Composable
fun ThreadDetailScreen(
    viewModel: ThreadDetailViewModel,
    onOpenAssociatedReports: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ThreadDetailContent(
        state = state,
        onOpenAssociatedReports = onOpenAssociatedReports,
        onBack = onBack,
        onRetry = { viewModel.dispatch(ThreadDetailIntent.Load) },
        onPullToRefresh = { viewModel.dispatch(ThreadDetailIntent.PullToRefresh) },
        onLoadMoreReplies = { viewModel.dispatch(ThreadDetailIntent.LoadMoreReplies) },
    )
}

@Composable
private fun ThreadDetailContent(
    state: ThreadDetailState,
    onOpenAssociatedReports: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPullToRefresh: () -> Unit,
    onLoadMoreReplies: () -> Unit,
) {
    val now = remember { Clock.System.now() }
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.replies) {
        if (state.replies !is UiAsync.Success) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo }.collect { layoutInfo ->
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@collect
            val total = layoutInfo.totalItemsCount
            if (total > 0 && lastVisible >= total - 3) onLoadMoreReplies()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPullToRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val threadState = state.thread) {
                UiAsync.Idle, UiAsync.Loading -> item(key = "loading") { LoadingThread() }
                UiAsync.Empty -> item(key = "not-found") { ThreadNotFoundState(onBack) }
                is UiAsync.Error -> item(key = "error") {
                    if (threadState.error is AppError.NotFound) {
                        ThreadNotFoundState(onBack)
                    } else {
                        ThreadErrorState(error = threadState.error, onRetry = onRetry)
                    }
                }
                is UiAsync.Success -> threadContent(
                    thread = threadState.value,
                    now = now,
                    replies = state.replies,
                    loadingMoreReplies = state.loadingMoreReplies,
                    onOpenAssociatedReports = onOpenAssociatedReports,
                    onRetryReplies = onRetry,
                )
            }
        }
    }
}

private fun LazyListScope.threadContent(
    thread: ForumThreadDetail,
    now: Instant,
    replies: UiAsync<List<ForumReply>>,
    loadingMoreReplies: Boolean,
    onOpenAssociatedReports: () -> Unit,
    onRetryReplies: () -> Unit,
) {
    val ui = thread.toUi(now)

    item(key = "header") { ThreadHeader(ui) }
    item(key = "content") { ThreadContentCard(ui.content) }
    if (ui.associatedReportsCount > 0) {
        item(key = "reports") { AssociatedReportsCard(count = ui.associatedReportsCount, onClick = onOpenAssociatedReports) }
    }
    item(key = "replies-header") {
        Text(
            text = ui.replyCountLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    when (replies) {
        UiAsync.Idle, UiAsync.Loading -> item(key = "replies-loading") { LoadingReplies() }
        UiAsync.Empty -> item(key = "replies-empty") {
            EmptyStateCard(
                title = "Aún no hay respuestas",
                message = "Este hilo todavía no tiene comentarios.",
            )
        }
        is UiAsync.Error -> item(key = "replies-error") {
            EmptyStateCard(
                title = friendlyErrorTitle(replies.error),
                message = friendlyErrorMessage(replies.error),
                action = { GlassActionButton(text = "Reintentar", onClick = onRetryReplies) },
            )
        }
        is UiAsync.Success -> {
            items(replies.value, key = { it.id }) { reply -> ReplyCard(item = reply.toUi(now)) }
            if (loadingMoreReplies) {
                item(key = "loading-more-replies") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = GlassTheme.tokens.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadHeader(ui: ThreadDetailUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.categoryLabel != null || ui.pinned) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ui.categoryLabel?.let {
                    GlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        tint = GlassTheme.tokens.accent.copy(alpha = 0.16f),
                        strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.4f),
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTheme.tokens.accent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
                if (ui.pinned) {
                    Text(
                        text = "Fijado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(text = ui.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = buildString {
                append(ui.authorName)
                if (ui.timeLabel.isNotBlank()) {
                    append(" · ")
                    append(ui.timeLabel.replaceFirstChar { c -> c.uppercase() })
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThreadContentCard(content: String) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Text(
            text = content.ifBlank { "Este hilo no tiene contenido." },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(18.dp),
        )
    }
}

@Composable
private fun AssociatedReportsCard(count: Int, onClick: () -> Unit) {
    val label = if (count == 1) "1 reporte asociado" else "$count reportes asociados"
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label. Abrir informes." },
        shape = RoundedCornerShape(16.dp),
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        strokeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReplyCard(item: ForumReplyUi) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = item.semanticsLabel },
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = buildString {
                    append(item.authorName)
                    if (item.timeLabel.isNotBlank()) {
                        append(" · ")
                        append(item.timeLabel)
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = item.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingThread() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingStateCard(rows = 2)
        LoadingStateCard(rows = 4)
        LoadingStateCard(rows = 2)
    }
}

@Composable
private fun LoadingReplies() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingStateCard(rows = 2)
        LoadingStateCard(rows = 2)
    }
}

@Composable
private fun ThreadNotFoundState(onBack: () -> Unit) {
    EmptyStateCard(
        title = "Hilo no disponible",
        message = "Puede haber sido eliminado o ya no estar disponible.",
        action = { GlassActionButton(text = "Volver al Foro", onClick = onBack) },
    )
}

@Composable
private fun ThreadErrorState(error: AppError, onRetry: () -> Unit) {
    EmptyStateCard(
        title = friendlyErrorTitle(error),
        message = friendlyErrorMessage(error),
        action = { GlassActionButton(text = "Reintentar", onClick = onRetry) },
    )
}

private fun friendlyErrorTitle(error: AppError): String = when (error) {
    is AppError.Network -> "No se pudo conectar"
    is AppError.Authentication -> "Sesión no disponible"
    is AppError.Authorization -> "Acceso no disponible"
    is AppError.NotFound -> "Hilo no disponible"
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar el hilo"
}

private fun friendlyErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "No se puede conectar. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar este hilo."
    is AppError.NotFound -> "Puede haber sido eliminado o ya no estar disponible."
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar el hilo. Puedes intentarlo de nuevo."
}
