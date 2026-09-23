package com.oposicontrol.admin.features.reports.presentation

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ForumReport
import kotlin.time.Clock
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_filter
import org.jetbrains.compose.resources.painterResource

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onOpenReport: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ReportsContent(
        state = state,
        onOpenReport = onOpenReport,
        onSetMainFilter = { viewModel.dispatch(ReportsIntent.SetMainFilter(it)) },
        onPullToRefresh = { viewModel.dispatch(ReportsIntent.PullToRefresh) },
        onRetry = { viewModel.dispatch(ReportsIntent.Refresh) },
        onApplySecondaryFilters = { viewModel.dispatch(ReportsIntent.ApplySecondaryFilters(it)) },
        onClearSecondaryFilters = { viewModel.dispatch(ReportsIntent.ClearSecondaryFilters) },
    )
}

@Composable
private fun ReportsContent(
    state: ReportsState,
    onOpenReport: (String) -> Unit,
    onSetMainFilter: (ReportsMainFilter) -> Unit,
    onPullToRefresh: () -> Unit,
    onRetry: () -> Unit,
    onApplySecondaryFilters: (ReportSecondaryFilters) -> Unit,
    onClearSecondaryFilters: () -> Unit,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val loadedReports = (state.reports as? UiAsync.Success)?.value.orEmpty()
    val availableEntityTypes = remember(loadedReports) { loadedReports.map { it.entityType }.filter { it.isNotBlank() }.distinct() }

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
                pendingCount = loadedReports.size.takeIf { state.mainFilter == ReportsMainFilter.PENDING && state.reports is UiAsync.Success },
                allCount = loadedReports.size.takeIf { state.mainFilter == ReportsMainFilter.ALL && state.reports is UiAsync.Success },
                secondaryFiltersActive = state.secondaryFilters.selectedEntityTypes.isNotEmpty(),
                onSetMainFilter = onSetMainFilter,
                onOpenFilterSheet = { showFilterSheet = true },
            )
            when (val reportsState = state.reports) {
                UiAsync.Idle, UiAsync.Loading -> LoadingList()
                UiAsync.Empty -> EmptyReportsState(mainFilter = state.mainFilter)
                is UiAsync.Error -> ErrorReportsState(error = reportsState.error, onRetry = onRetry)
                is UiAsync.Success -> {
                    val visible = reportsState.value.filterBySecondary(state.secondaryFilters)
                    if (visible.isEmpty()) {
                        FilteredEmptyState(onClear = onClearSecondaryFilters)
                    } else {
                        ReportsList(reports = visible, onOpenReport = onOpenReport)
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        SecondaryFilterSheet(
            availableEntityTypes = availableEntityTypes,
            current = state.secondaryFilters,
            onApply = {
                onApplySecondaryFilters(it)
                showFilterSheet = false
            },
            onClear = {
                onClearSecondaryFilters()
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }
}

private fun List<ForumReport>.filterBySecondary(filters: ReportSecondaryFilters): List<ForumReport> {
    if (filters.selectedEntityTypes.isEmpty()) return this
    return filter { it.entityType in filters.selectedEntityTypes }
}

@Composable
private fun FilterRow(
    mainFilter: ReportsMainFilter,
    pendingCount: Int?,
    allCount: Int?,
    secondaryFiltersActive: Boolean,
    onSetMainFilter: (ReportsMainFilter) -> Unit,
    onOpenFilterSheet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill(
            label = if (pendingCount != null) "Pendientes $pendingCount" else "Pendientes",
            selected = mainFilter == ReportsMainFilter.PENDING,
            onClick = { onSetMainFilter(ReportsMainFilter.PENDING) },
        )
        FilterPill(
            label = if (allCount != null) "Todos $allCount" else "Todos",
            selected = mainFilter == ReportsMainFilter.ALL,
            onClick = { onSetMainFilter(ReportsMainFilter.ALL) },
        )
        Box(modifier = Modifier.weight(1f))
        FilterIconButton(active = secondaryFiltersActive, onClick = onOpenFilterSheet)
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
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
    // Exact .size(), not defaultMinSize(): as a non-weighted Row child inside a
    // non-weighted Column child, this can be measured with an unbounded height:
    // a fillMaxSize() box inside GlassSurface with only a *minimum* constraint
    // resolved to a huge (not zero) height under that ambiguity, stretching the
    // whole row. An exact size removes the ambiguity outright.
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
private fun ReportsList(reports: List<ForumReport>, onOpenReport: (String) -> Unit) {
    val now = remember(reports) { Clock.System.now() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(reports, key = { it.id }) { report ->
            ReportCard(item = report.toUi(now), onClick = { onOpenReport(report.id) })
        }
    }
}

@Composable
private fun ReportCard(item: ReportItemUi, onClick: () -> Unit) {
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
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor(item.statusKind)),
                )
                Text(
                    text = item.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor(item.statusKind),
                )
            }
            Text(
                text = item.reason,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotBlank() && item.description != item.reason) {
                Text(
                    text = item.description,
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
                Text(
                    text = buildString {
                        append(item.entityTypeLabel)
                        if (item.reporterName.isNotBlank()) append(" · ${item.reporterName}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.timeLabel.isNotBlank()) {
                        Text(
                            text = item.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.accent)
                }
            }
        }
    }
}

@Composable
private fun statusColor(kind: ReportStatusKind) = when (kind) {
    ReportStatusKind.PENDING -> GlassTheme.tokens.accent
    ReportStatusKind.RESOLVED -> GlassTheme.tokens.success
    ReportStatusKind.DISCARDED -> MaterialTheme.colorScheme.onSurfaceVariant
    ReportStatusKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun EmptyReportsState(mainFilter: ReportsMainFilter) {
    if (mainFilter == ReportsMainFilter.PENDING) {
        EmptyStateCard(
            title = "✓ Sin informes pendientes",
            message = "No hay incidencias esperando revisión.",
        )
    } else {
        EmptyStateCard(
            title = "Sin informes",
            message = "Cuando los usuarios reporten contenido aparecerá aquí.",
        )
    }
}

@Composable
private fun FilteredEmptyState(onClear: () -> Unit) {
    EmptyStateCard(
        title = "Sin resultados",
        message = "Ningún informe coincide con el filtro seleccionado.",
        action = {
            Text(
                text = "Quitar filtro",
                style = MaterialTheme.typography.labelLarge,
                color = GlassTheme.tokens.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onClear)
                    .padding(top = 6.dp),
            )
        },
    )
}

@Composable
private fun ErrorReportsState(error: AppError, onRetry: () -> Unit) {
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
private fun SecondaryFilterSheet(
    availableEntityTypes: List<String>,
    current: ReportSecondaryFilters,
    onApply: (ReportSecondaryFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(current) { mutableStateOf(current.selectedEntityTypes) }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Filtros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (availableEntityTypes.isEmpty()) {
                Text(
                    "No hay más filtros disponibles para los informes cargados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tipo de contenido",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableEntityTypes.forEach { type ->
                        val checked = type in selection
                        FilterPill(
                            label = type.replaceFirstChar { it.uppercase() },
                            selected = checked,
                            onClick = {
                                selection = if (checked) selection - type else selection + type
                            },
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
                        .clickable { onApply(ReportSecondaryFilters(selection)) }
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
    is AppError.Validation, is AppError.Unknown -> "No se pudieron cargar los informes"
}

private fun friendlyErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "No hemos podido cargar los informes. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar los informes."
    is AppError.NotFound -> "La información solicitada no está disponible ahora."
    is AppError.Validation, is AppError.Unknown -> "Ha ocurrido un problema inesperado. Puedes intentarlo de nuevo."
}
