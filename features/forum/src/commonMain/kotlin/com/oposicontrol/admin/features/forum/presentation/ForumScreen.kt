package com.oposicontrol.admin.features.forum.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ForumThread
import kotlin.time.Clock

@Composable
fun ForumScreen(
    viewModel: ForumViewModel,
    onOpenThread: (String) -> Unit,
    onOpenPendingReports: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ForumContent(
        state = state,
        onOpenThread = onOpenThread,
        onOpenPendingReports = onOpenPendingReports,
        onSetMainFilter = { viewModel.dispatch(ForumIntent.SetMainFilter(it)) },
        onSetCategoryFilter = { viewModel.dispatch(ForumIntent.SetCategoryFilter(it)) },
        onPullToRefresh = { viewModel.dispatch(ForumIntent.PullToRefresh) },
        onLoadMore = { viewModel.dispatch(ForumIntent.LoadMore) },
        onRetry = { viewModel.dispatch(ForumIntent.Load) },
    )
}

@Composable
private fun ForumContent(
    state: ForumState,
    onOpenThread: (String) -> Unit,
    onOpenPendingReports: () -> Unit,
    onSetMainFilter: (ForumMainFilter) -> Unit,
    onSetCategoryFilter: (ForumCategoryOption?) -> Unit,
    onPullToRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    var showFilterSheet by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPullToRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val pendingCount = state.pendingReportsCount
            if (pendingCount != null && pendingCount > 0) {
                PendingReportsBanner(count = pendingCount, onClick = onOpenPendingReports)
            }
            FilterRow(
                mainFilter = state.mainFilter,
                categoryFilter = state.categoryFilter,
                filtersActive = state.categoryFilter != null,
                onSetMainFilter = onSetMainFilter,
                onOpenFilterSheet = { showFilterSheet = true },
            )
            when (val threadsState = state.threads) {
                UiAsync.Idle, UiAsync.Loading -> LoadingList()
                UiAsync.Empty -> EmptyForumState(mainFilter = state.mainFilter)
                is UiAsync.Error -> ErrorForumState(error = threadsState.error, onRetry = onRetry)
                is UiAsync.Success -> ThreadsList(
                    threads = threadsState.value,
                    loadingMore = state.loadingMore,
                    onOpenThread = onOpenThread,
                    onApproachingEnd = onLoadMore,
                )
            }
        }
    }

    if (showFilterSheet) {
        CategoryFilterSheet(
            availableCategories = state.knownCategories,
            current = state.categoryFilter,
            onApply = {
                onSetCategoryFilter(it)
                showFilterSheet = false
            },
            onClear = {
                onSetCategoryFilter(null)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }
}

@Composable
private fun PendingReportsBanner(count: Int, onClick: () -> Unit) {
    val label = if (count == 1) "1 reporte pendiente" else "$count reportes pendientes"
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label. Abrir informes." },
        shape = RoundedCornerShape(16.dp),
        tint = GlassTheme.tokens.accent.copy(alpha = 0.14f),
        strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.4f),
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassTheme.tokens.accent,
            )
            Text("›", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.accent)
        }
    }
}

@Composable
private fun FilterRow(
    mainFilter: ForumMainFilter,
    categoryFilter: ForumCategoryOption?,
    filtersActive: Boolean,
    onSetMainFilter: (ForumMainFilter) -> Unit,
    onOpenFilterSheet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = "Recientes",
            selected = mainFilter == ForumMainFilter.RECENT,
            onClick = { onSetMainFilter(ForumMainFilter.RECENT) },
        )
        FilterPill(
            label = "Reportados",
            selected = mainFilter == ForumMainFilter.REPORTED,
            onClick = { onSetMainFilter(ForumMainFilter.REPORTED) },
        )
        Box(modifier = Modifier.weight(1f))
        FilterIconButton(active = filtersActive, onClick = onOpenFilterSheet)
    }
    categoryFilter?.let {
        Text(
            text = "Categoría: ${it.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            // Reserva 48dp táctiles sin agrandar la píldora visible (que sigue
            // midiendo 40dp): mismo criterio que
            // TesterListScreen.FilterPill — ver el comentario allí.
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            },
        shape = RoundedCornerShape(20.dp),
        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.18f) else GlassTheme.tokens.glassFill,
        strokeColor = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.5f) else GlassTheme.tokens.glassStroke,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FilterIconButton(active: Boolean, onClick: () -> Unit) = com.oposicontrol.admin.core.ui.components.FilterIconButton(active, onClick)

@Composable
private fun ThreadsList(
    threads: List<ForumThread>,
    loadingMore: Boolean,
    onOpenThread: (String) -> Unit,
    onApproachingEnd: () -> Unit,
) {
    val now = remember(threads) { Clock.System.now() }
    val listState = rememberLazyListState()

    LaunchedEffect(listState, threads.size) {
        snapshotFlow { listState.layoutInfo }.collect { layoutInfo ->
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@collect
            val total = layoutInfo.totalItemsCount
            if (total > 0 && lastVisible >= total - 3) onApproachingEnd()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(threads, key = { it.id }) { thread ->
            ThreadCard(item = thread.toUi(now), onClick = { onOpenThread(thread.id) })
        }
        if (loadingMore) {
            item(key = "loading-more") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = GlassTheme.tokens.accent)
                }
            }
        }
    }
}

@Composable
private fun ThreadCard(item: ForumThreadUi, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = item.semanticsLabel },
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.categoryLabel != null || item.pinned) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.categoryLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTheme.tokens.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.pinned) {
                        Text(
                            text = "Fijado",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.snippet.isNotBlank()) {
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.hasPendingReport) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                    Text(
                        text = buildString {
                            append(item.replyCountLabel)
                            if (item.timeLabel.isNotBlank()) {
                                append(" · ")
                                append(item.timeLabel)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.accent)
            }
        }
    }
}

@Composable
fun LoadingList() = com.oposicontrol.admin.core.ui.components.LoadingList()

@Composable
private fun EmptyForumState(mainFilter: ForumMainFilter) {
    if (mainFilter == ForumMainFilter.RECENT) {
        EmptyStateCard(
            title = "No hay actividad en OposiRed",
            message = "Los nuevos hilos aparecerán aquí.",
        )
    } else {
        EmptyStateCard(
            title = "Sin contenido reportado",
            message = "No hay publicaciones que requieran revisión.",
        )
    }
}

@Composable
private fun ErrorForumState(error: AppError, onRetry: () -> Unit) {
    EmptyStateCard(
        title = friendlyErrorTitle(error),
        message = friendlyErrorMessage(error),
        action = {
            Text(
                text = "Reintentar",
                style = MaterialTheme.typography.labelLarge,
                color = GlassTheme.tokens.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onRetry)
                    .padding(top = 6.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterSheet(
    availableCategories: List<ForumCategoryOption>,
    current: ForumCategoryOption?,
    onApply: (ForumCategoryOption?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(current) { mutableStateOf(current) }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Filtros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (availableCategories.isEmpty()) {
                Text(
                    "No hay más filtros disponibles para los hilos cargados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Categoría",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableCategories.forEach { category ->
                        val checked = selection?.id == category.id
                        FilterPill(
                            label = category.name,
                            selected = checked,
                            onClick = { selection = if (checked) null else category },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Limpiar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onClear() }
                        .padding(vertical = 14.dp),
                )
                Text(
                    text = "Aplicar",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassTheme.tokens.accent)
                        .clickable { onApply(selection) }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

private fun friendlyErrorTitle(error: AppError): String = when (error) {
    is AppError.Network -> "No se pudo conectar"
    is AppError.Authentication -> "Sesión no disponible"
    is AppError.Authorization -> "Acceso no disponible"
    is AppError.NotFound -> "Datos no disponibles"
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar OposiRed"
}

private fun friendlyErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "No se puede conectar. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar el foro."
    is AppError.NotFound -> "La información solicitada no está disponible ahora."
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar OposiRed. Puedes intentarlo de nuevo."
}
