package com.oposicontrol.admin.features.opositester.presentation

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
import androidx.compose.material3.Icon
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
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.TesterReport
import kotlin.time.Clock
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_filter
import org.jetbrains.compose.resources.painterResource

@Composable
fun TesterListScreen(
    viewModel: TesterListViewModel,
    onOpenReport: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    TesterListContent(
        state = state,
        onOpenReport = onOpenReport,
        onSetMainFilter = { viewModel.dispatch(TesterIntent.SetMainFilter(it)) },
        onSetTypeFilter = { viewModel.dispatch(TesterIntent.SetTypeFilter(it)) },
        onPullToRefresh = { viewModel.dispatch(TesterIntent.PullToRefresh) },
        onLoadMore = { viewModel.dispatch(TesterIntent.LoadMore) },
        onRetry = { viewModel.dispatch(TesterIntent.Load) },
    )
}

@Composable
private fun TesterListContent(
    state: TesterListState,
    onOpenReport: (String) -> Unit,
    onSetMainFilter: (TesterMainFilter) -> Unit,
    onSetTypeFilter: (TesterTypeOption?) -> Unit,
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
            FilterRow(
                mainFilter = state.mainFilter,
                typeFilter = state.typeFilter,
                filtersActive = state.typeFilter != null,
                onSetMainFilter = onSetMainFilter,
                onOpenFilterSheet = { showFilterSheet = true },
            )
            when (val reportsState = state.reports) {
                UiAsync.Idle, UiAsync.Loading -> LoadingList()
                UiAsync.Empty -> EmptyTesterState(mainFilter = state.mainFilter)
                is UiAsync.Error -> ErrorTesterState(error = reportsState.error, onRetry = onRetry)
                is UiAsync.Success -> ReportsList(
                    reports = reportsState.value,
                    loadingMore = state.loadingMore,
                    onOpenReport = onOpenReport,
                    onApproachingEnd = onLoadMore,
                )
            }
        }
    }

    if (showFilterSheet) {
        TypeFilterSheet(
            availableTypes = state.knownTypes,
            current = state.typeFilter,
            onApply = {
                onSetTypeFilter(it)
                showFilterSheet = false
            },
            onClear = {
                onSetTypeFilter(null)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }
}

@Composable
private fun FilterRow(
    mainFilter: TesterMainFilter,
    typeFilter: TesterTypeOption?,
    filtersActive: Boolean,
    onSetMainFilter: (TesterMainFilter) -> Unit,
    onOpenFilterSheet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = "Pendientes",
            selected = mainFilter == TesterMainFilter.PENDING,
            onClick = { onSetMainFilter(TesterMainFilter.PENDING) },
        )
        FilterPill(
            label = "Todos",
            selected = mainFilter == TesterMainFilter.ALL,
            onClick = { onSetMainFilter(TesterMainFilter.ALL) },
        )
        Box(modifier = Modifier.weight(1f))
        FilterIconButton(active = filtersActive, onClick = onOpenFilterSheet)
    }
    typeFilter?.let {
        Text(
            text = "Tipo: ${it.label}",
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
            // midiendo 40dp): Compose ya expande el hit-test de clickable() al
            // mínimo del sistema, pero minimumInteractiveComponentSize() lo deja
            // garantizado y explícito en vez de depender de que el Row tenga
            // hueco de sobra porque el hermano FilterIconButton mide 48dp.
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
private fun FilterIconButton(active: Boolean, onClick: () -> Unit) {
    // Tamaño exacto, no defaultMinSize(): mismo motivo documentado en
    // ForumScreen.FilterIconButton (ambigüedad de alto no acotado dentro de
    // una Row/Column no ponderada).
    GlassSurface(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (active) "Filtro, con filtros aplicados" else "Filtro"
            },
        shape = RoundedCornerShape(16.dp),
        tint = if (active) GlassTheme.tokens.accent.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (active) GlassTheme.tokens.accent.copy(alpha = 0.45f) else GlassTheme.tokens.glassStroke,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(Res.drawable.ic_filter),
                contentDescription = null,
                tint = if (active) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ReportsList(
    reports: List<TesterReport>,
    loadingMore: Boolean,
    onOpenReport: (String) -> Unit,
    onApproachingEnd: () -> Unit,
) {
    val now = remember(reports) { Clock.System.now() }
    val listState = rememberLazyListState()

    LaunchedEffect(listState, reports.size) {
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
        items(reports, key = { it.id }) { report ->
            ReportCard(item = report.toUi(now), onClick = { onOpenReport(report.id) })
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
private fun ReportCard(item: TesterReportUi, onClick: () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor(item.statusKind)))
                Text(
                    text = item.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor(item.statusKind),
                )
            }
            Text(
                text = item.typeLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.authorName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.descriptionPreview.isNotBlank()) {
                Text(
                    text = item.descriptionPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // weight(1f) mantiene la flecha siempre pegada a la derecha,
                // tenga o no texto la etiqueta de fecha (nunca se fabrica una
                // fecha si el timestamp real es inválido — puede venir vacía).
                Box(modifier = Modifier.weight(1f)) {
                    if (item.timeLabel.isNotBlank()) {
                        Text(
                            text = item.timeLabel.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.accent)
            }
        }
    }
}

@Composable
private fun statusColor(kind: TesterReportStatusKind) = when (kind) {
    TesterReportStatusKind.PENDING -> GlassTheme.tokens.accent
    TesterReportStatusKind.RESOLVED -> GlassTheme.tokens.success
    TesterReportStatusKind.DISCARDED, TesterReportStatusKind.REVIEWED, TesterReportStatusKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun LoadingList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 3)
    }
}

@Composable
private fun EmptyTesterState(mainFilter: TesterMainFilter) {
    if (mainFilter == TesterMainFilter.PENDING) {
        EmptyStateCard(
            title = "Todo al día",
            message = "No hay incidencias pendientes de revisión.",
        )
    } else {
        EmptyStateCard(
            title = "Sin incidencias",
            message = "Las nuevas incidencias aparecerán aquí.",
        )
    }
}

@Composable
private fun ErrorTesterState(error: AppError, onRetry: () -> Unit) {
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
private fun TypeFilterSheet(
    availableTypes: List<TesterTypeOption>,
    current: TesterTypeOption?,
    onApply: (TesterTypeOption?) -> Unit,
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
            if (availableTypes.isEmpty()) {
                Text(
                    "No hay más filtros disponibles para las incidencias cargadas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tipo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableTypes.forEach { type ->
                        val checked = selection?.value == type.value
                        FilterPill(
                            label = type.label,
                            selected = checked,
                            onClick = { selection = if (checked) null else type },
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
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar OposiTester"
}

private fun friendlyErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "No se puede conectar. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar las incidencias."
    is AppError.NotFound -> "La información solicitada no está disponible ahora."
    is AppError.Validation, is AppError.Unknown -> "No hemos podido cargar las incidencias. Puedes intentarlo de nuevo."
}
