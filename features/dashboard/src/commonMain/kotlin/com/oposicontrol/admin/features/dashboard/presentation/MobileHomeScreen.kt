package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.DashboardMetrics
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_exams
import oposicontrol.core.ui.generated.resources.ic_support
import oposicontrol.core.ui.generated.resources.ic_upload
import oposicontrol.core.ui.generated.resources.ic_users
import org.jetbrains.compose.resources.painterResource

@Composable
fun MobileHomeScreen(
    viewModel: DashboardViewModel,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    MobileHomeContent(
        state = state,
        onRefresh = { viewModel.dispatch(DashboardIntent.Refresh) },
        onQuickAction = onQuickAction,
        onToggleQuickActions = { viewModel.dispatch(DashboardIntent.ToggleQuickActions) },
        onToggleSummary = { viewModel.dispatch(DashboardIntent.ToggleSummary) },
    )
}

@Composable
private fun MobileHomeContent(
    state: DashboardState,
    onRefresh: () -> Unit,
    onQuickAction: (DashboardQuickAction) -> Unit,
    onToggleQuickActions: () -> Unit,
    onToggleSummary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MobileHomeHeader(onRefresh = onRefresh)
        when (val metricsState = state.metrics) {
            UiAsync.Idle,
            UiAsync.Loading,
            -> MobileHomeLoading()

            UiAsync.Empty -> MobileHomeEmpty(
                onRefresh = onRefresh,
                onQuickAction = onQuickAction,
                quickActionsExpanded = state.quickActionsExpanded,
                onToggleQuickActions = onToggleQuickActions,
            )
            is UiAsync.Error -> MobileHomeError(metricsState.error, onRefresh)
            is UiAsync.Success -> MobileHomeLoaded(
                metrics = metricsState.value,
                onQuickAction = onQuickAction,
                quickActionsExpanded = state.quickActionsExpanded,
                onToggleQuickActions = onToggleQuickActions,
                summaryExpanded = state.summaryExpanded,
                onToggleSummary = onToggleSummary,
            )
        }
    }
}

@Composable
private fun MobileHomeHeader(onRefresh: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tint = GlassTheme.tokens.glassFillStrong,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(GlassTheme.tokens.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("OC", color = GlassTheme.tokens.accent, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Hola Arturo 👑", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Panel operativo", style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.success)
            }
            HomeHeaderAction(onRefresh = onRefresh)
        }
    }
}

@Composable
private fun HomeHeaderAction(onRefresh: () -> Unit) {
    // No opaque fill here: a subtle stroke is enough affordance while letting the
    // header's own glass show through underneath (no "white box on glass").
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, GlassTheme.tokens.glassStroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onRefresh)
            .semantics { contentDescription = "Actualizar Home" },
        contentAlignment = Alignment.Center,
    ) {
        Text("↻", style = MaterialTheme.typography.titleLarge, color = GlassTheme.tokens.accent)
    }
}

@Composable
private fun MobileHomeLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 4)
        LoadingStateCard(rows = 3)
    }
}

@Composable
private fun MobileHomeError(error: AppError, onRefresh: () -> Unit) {
    EmptyStateCard(
        title = friendlyErrorTitle(error),
        message = friendlyErrorMessage(error),
        action = { MobileRetryButton(onRefresh) },
    )
}

@Composable
private fun MobileHomeEmpty(
    onRefresh: () -> Unit,
    onQuickAction: (DashboardQuickAction) -> Unit,
    quickActionsExpanded: Boolean,
    onToggleQuickActions: () -> Unit,
) {
    HomeSectionCard {
        HomeSectionTitle("Necesita tu atención")
        Text("Sin informes de OposiRed pendientes", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.success, fontWeight = FontWeight.SemiBold)
        Text("No se han recibido informes de moderación pendientes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    MobileQuickActions(
        expanded = quickActionsExpanded,
        onToggleExpanded = onToggleQuickActions,
        onQuickAction = onQuickAction,
    )
    EmptyStateCard(
        title = "Sin datos todavía",
        message = "El resumen aparecerá cuando exista actividad operativa.",
        action = { MobileRetryButton(onRefresh) },
    )
}

@Composable
private fun MobileHomeLoaded(
    metrics: DashboardMetrics,
    onQuickAction: (DashboardQuickAction) -> Unit,
    quickActionsExpanded: Boolean,
    onToggleQuickActions: () -> Unit,
    summaryExpanded: Boolean,
    onToggleSummary: () -> Unit,
) {
    MobileAttentionSection(metrics = metrics, onQuickAction = onQuickAction)
    MobileQuickActions(
        expanded = quickActionsExpanded,
        onToggleExpanded = onToggleQuickActions,
        onQuickAction = onQuickAction,
    )
    MobileTodaySummary(metrics = metrics, expanded = summaryExpanded, onToggleExpanded = onToggleSummary)
    if (metrics.recentEvents.isNotEmpty()) {
        MobileRecentActivity(metrics = metrics)
    }
}

@Composable
private fun MobileAttentionSection(
    metrics: DashboardMetrics,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    HomeSectionCard {
        HomeSectionTitle("Necesita tu atención")
        if (metrics.pendingForumReports == 0) {
            Text("Sin informes de OposiRed pendientes", style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.success, fontWeight = FontWeight.SemiBold)
            Text("No se han recibido informes de moderación pendientes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            HomeAttentionItem(
                title = "${metrics.pendingForumReports} informes de OposiRed",
                detail = "Revisa los informes pendientes de moderación.",
                actionLabel = "Abrir foro",
                onClick = { onQuickAction(DashboardQuickAction.REVIEW_FORUM_REPORTS) },
            )
        }
    }
}

@Composable
private fun HomeAttentionItem(
    title: String,
    detail: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassTheme.tokens.accent.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("›", color = GlassTheme.tokens.accent, style = MaterialTheme.typography.titleLarge)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = GlassTheme.tokens.accent)
    }
}

@Composable
private fun MobileQuickActions(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeDisclosureHeader(title = "Acciones rápidas", expanded = expanded, onToggle = onToggleExpanded)
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(160)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeActionTile("Subir contenido", "upload", true, Modifier.weight(1f)) { onQuickAction(DashboardQuickAction.IMPORT_QUESTIONS) }
                    HomeActionTile("Exámenes", "exams", false, Modifier.weight(1f)) { onQuickAction(DashboardQuickAction.CREATE_EXAM) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeActionTile("Usuarios", "users", false, Modifier.weight(1f)) { onQuickAction(DashboardQuickAction.OPEN_USERS) }
                    HomeActionTile("Soporte", "support", false, Modifier.weight(1f)) { onQuickAction(DashboardQuickAction.OPEN_SUPPORT) }
                }
            }
        }
    }
}

/** Shared expand/collapse header for "Acciones rápidas" and "Resumen". */
@Composable
private fun HomeDisclosureHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = title
                stateDescription = if (expanded) "Expandido" else "Contraído"
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            if (expanded) "⌃" else "⌄",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun HomeActionTile(
    label: String,
    icon: String,
    emphasized: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        tint = if (emphasized) GlassTheme.tokens.accent.copy(alpha = 0.14f) else GlassTheme.tokens.glassFill,
        strokeColor = if (emphasized) GlassTheme.tokens.accent.copy(alpha = 0.45f) else GlassTheme.tokens.glassStroke,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                HomeActionIcon(icon, if (emphasized) GlassTheme.tokens.accent else GlassTheme.tokens.textSecondary)
                Text(
                    "↗",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Real, licensed icon set (Lucide — https://lucide.dev, ISC license), bundled as
// Android vector drawables under composeResources/drawable/ic_*.xml. Replaces the
// earlier hand-drawn Canvas glyphs.
private val HomeActionIconDrawables = mapOf(
    "upload" to Res.drawable.ic_upload,
    "exams" to Res.drawable.ic_exams,
    "users" to Res.drawable.ic_users,
    "support" to Res.drawable.ic_support,
)

@Composable
private fun HomeActionIcon(icon: String, color: Color) {
    val drawable = HomeActionIconDrawables[icon] ?: return
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun MobileTodaySummary(
    metrics: DashboardMetrics,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeDisclosureHeader(title = "Resumen", expanded = expanded, onToggle = onToggleExpanded)
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(160)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeMetricTile("Exámenes publicados", metrics.publishedExams, Modifier.weight(1f))
                    HomeMetricTile("Preguntas", metrics.totalQuestions, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeMetricTile("Usuarios activos", metrics.activeUsers30d, Modifier.weight(1f), detail = "Últimos 30 días")
                    HomeMetricTile("Intentos totales", metrics.totalAttempts, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeMetricTile(label: String, value: Int, modifier: Modifier, detail: String? = null) {
    GlassSurface(modifier = modifier.heightIn(min = 92.dp), tint = GlassTheme.tokens.glassFill, shadowElevation = 6.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MobileRecentActivity(metrics: DashboardMetrics) {
    HomeSectionCard {
        HomeSectionTitle("Actividad reciente")
        metrics.recentEvents.take(5).forEach { event ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeSectionCard(content: @Composable () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun HomeSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MobileRetryButton(onRefresh: () -> Unit) {
    GlassActionButton(text = "Reintentar", modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onClick = onRefresh)
}

private fun friendlyErrorTitle(error: AppError): String = when (error) {
    is AppError.Network -> "No se pudo conectar"
    is AppError.Authentication -> "Sesión no disponible"
    is AppError.Authorization -> "Acceso no disponible"
    is AppError.NotFound -> "Datos no disponibles"
    is AppError.Validation, is AppError.Unknown -> "No se pudo cargar el Home"
}

private fun friendlyErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "Comprueba la conexión y vuelve a intentarlo."
    is AppError.Authentication -> "Tu sesión necesita renovarse. Vuelve a iniciar sesión."
    is AppError.Authorization -> "Tu cuenta no tiene permisos para consultar este panel."
    is AppError.NotFound -> "La información solicitada no está disponible ahora."
    is AppError.Validation, is AppError.Unknown -> "Ha ocurrido un problema inesperado. Puedes intentarlo de nuevo."
}
