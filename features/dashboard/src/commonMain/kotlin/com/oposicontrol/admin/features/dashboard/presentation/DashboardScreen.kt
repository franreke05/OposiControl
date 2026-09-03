package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.layout.DesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.desktopBreakpointFor
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.DashboardActivityEvent
import com.oposicontrol.admin.domain.model.DashboardAlert
import com.oposicontrol.admin.domain.model.DashboardAlertSeverity
import com.oposicontrol.admin.domain.model.DashboardMetricKey
import com.oposicontrol.admin.domain.model.DashboardMetrics
import com.oposicontrol.admin.domain.model.DayCount
import kotlinx.datetime.Instant
import kotlin.math.roundToInt

enum class DashboardQuickAction {
    REVIEW_PENDING,
    REVIEW_FORUM_REPORTS,
    IMPORT_QUESTIONS,
    AUDIT_RESOURCES,
    CREATE_NEWS,
    CREATE_EXAM,
    OPEN_USERS,
    OPEN_SUPPORT,
    ACTIVATE_OFFER,
    REVIEW_TICKETS,
    OPEN_AUDIT,
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    environmentLabel: String = "Entorno operativo",
    onQuickAction: (DashboardQuickAction) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            viewModel.dispatch(DashboardIntent.Refresh)
        }
    }
    DashboardContent(
        state = state,
        environmentLabel = environmentLabel,
        onRefresh = { viewModel.dispatch(DashboardIntent.Refresh) },
        onQuickAction = onQuickAction,
    )
}

@Composable
fun DashboardContent(
    state: DashboardState,
    environmentLabel: String,
    onRefresh: () -> Unit,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 720.dp
        val tablet = maxWidth in 720.dp..1039.dp
        // El tope escala con la ventana en vez de quedarse fijo en 1120dp. Con el
        // tope antiguo, un monitor de 1920 o 2560 mostraba una columna estrecha
        // centrada entre dos franjas vacías enormes — se pagaba el monitor grande
        // sin recibir nada a cambio. Sigue habiendo un tope porque una fila de
        // tarjetas de 2500dp de ancho tampoco se lee bien.
        val contentWidth = when {
            compact -> maxWidth
            tablet -> minOf(maxWidth, 980.dp)
            desktopBreakpointFor(maxWidth) == DesktopBreakpoint.WIDE -> minOf(maxWidth, 1760.dp)
            else -> minOf(maxWidth, 1400.dp)
        }
        val sectionGap = if (compact) 16.dp else 20.dp

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .width(contentWidth)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
                AsyncStatePane(
                    state = state.metrics,
                    emptyTitle = "Todavía no hay métricas",
                    emptyMessage = "El panel aún no ha recibido datos de actividad.",
                    emptyIcon = AppIcon.ANALYTICS,
                    loadingRows = 8,
                    onRetry = onRefresh,
                ) { metrics ->
                    Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                        DashboardOperationalTop(
                            metrics = metrics,
                            environmentLabel = environmentLabel,
                            compact = compact,
                            onRefresh = onRefresh,
                            onReviewPending = { onQuickAction(DashboardQuickAction.REVIEW_PENDING) },
                        )
                        DashboardHero(
                            metrics = metrics,
                            compact = compact,
                            onQuickAction = onQuickAction,
                        )
                        DashboardKpiGrid(metrics = metrics, compact = compact, tablet = tablet)
                        DashboardAlertsSection(metrics.alerts, compact = compact, onQuickAction = onQuickAction)
                        DashboardPerformanceSection(metrics = metrics, compact = compact)
                        DashboardQuickActions(compact = compact, onQuickAction = onQuickAction)
                        DashboardPanelGroup(
                            title = "Estado de contenido",
                            panels = contentPanels(metrics),
                            compact = compact,
                        )
                        DashboardPanelGroup(
                            title = "Tienda y campañas",
                            panels = commercePanels(metrics),
                            compact = compact,
                        )
                        DashboardBottomSection(
                            metrics = metrics,
                            environmentLabel = environmentLabel,
                            compact = compact,
                            onOpenAudit = { onQuickAction(DashboardQuickAction.OPEN_AUDIT) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardOperationalTop(
    metrics: DashboardMetrics,
    environmentLabel: String,
    compact: Boolean,
    onRefresh: () -> Unit,
    onReviewPending: () -> Unit,
) {
    val height = if (compact) 148.dp else 132.dp
    DashboardSectionSurface(modifier = Modifier.height(height), strong = true) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DashboardHeaderText(metrics, compact = true)
                DashboardStatusChips(metrics, environmentLabel)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val primaryWidth = maxWidth - 102.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardExactButton("Revisar pendientes", Modifier.height(44.dp).width(primaryWidth), onReviewPending)
                        DashboardGhostButton("Actualizar", Modifier.height(44.dp).width(92.dp), onRefresh)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 720.dp)) {
                    DashboardHeaderText(metrics, compact = false)
                    DashboardStatusChips(metrics, environmentLabel)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardGhostButton("Actualizar", Modifier.height(44.dp).width(118.dp), onRefresh)
                    DashboardExactButton("Revisar pendientes", Modifier.height(44.dp).width(172.dp), onReviewPending)
                }
            }
        }
    }
}

@Composable
private fun DashboardHeaderText(metrics: DashboardMetrics, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Panel operativo",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (compact) 20.sp else 24.sp, letterSpacing = 0.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${metrics.totalExams} exámenes, ${metrics.totalQuestions} preguntas y ${metrics.publishedNews} noticias bajo control",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (compact) 11.sp else 13.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardStatusChips(metrics: DashboardMetrics, environmentLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            environmentLabel,
            "Backend activo",
            compactInstant(metrics.lastUpdatedAt),
            "${metrics.alerts.size} incidencias",
        ).take(4).forEach { label ->
            DashboardChip(label)
        }
    }
}

@Composable
private fun DashboardHero(
    metrics: DashboardMetrics,
    compact: Boolean,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    val height = if (compact) 212.dp else 188.dp
    DashboardSectionSurface(modifier = Modifier.height(height), strong = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 18.dp),
        ) {
            Text(
                text = if (metrics.alerts.isEmpty()) "Contenido listo para operar" else "${metrics.alerts.size} puntos requieren revisión",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = if (compact) 22.sp else 26.sp,
                    letterSpacing = 0.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Prioriza recursos, importaciones y publicaciones antes de revisar tienda y campañas.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardExactButton("Importar preguntas", Modifier.height(42.dp).fillMaxWidth()) {
                        onQuickAction(DashboardQuickAction.IMPORT_QUESTIONS)
                    }
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val buttonWidth = (maxWidth - 10.dp) / 2
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DashboardGhostButton("Auditar recursos", Modifier.height(42.dp).width(buttonWidth)) {
                                onQuickAction(DashboardQuickAction.AUDIT_RESOURCES)
                            }
                            DashboardGhostButton("Crear noticia", Modifier.height(42.dp).width(buttonWidth)) {
                                onQuickAction(DashboardQuickAction.CREATE_NEWS)
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardExactButton("Importar preguntas", Modifier.height(42.dp).width(156.dp)) {
                        onQuickAction(DashboardQuickAction.IMPORT_QUESTIONS)
                    }
                    DashboardGhostButton("Auditar recursos", Modifier.height(42.dp).width(136.dp)) {
                        onQuickAction(DashboardQuickAction.AUDIT_RESOURCES)
                    }
                    DashboardGhostButton("Crear noticia", Modifier.height(42.dp).width(136.dp)) {
                        onQuickAction(DashboardQuickAction.CREATE_NEWS)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardKpiGrid(metrics: DashboardMetrics, compact: Boolean, tablet: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val columns = if (compact || tablet) 2 else 4
        val cardWidth = if (columns == 4) 262.dp else (maxWidth - gap) / 2
        val items = listOf(
            KpiItem("Exámenes publicados", metrics.publishedExams.toString(), "${metrics.draftExams} borradores"),
            KpiItem("Preguntas sin recurso", metrics.questionsWithoutResources.toString(), "${metrics.linkedResources} recursos enlazados"),
            KpiItem("Noticias en borrador", metrics.draftNews.toString(), "${metrics.publishedNews} publicadas"),
            KpiItem("Ofertas activas", metrics.activeOffers.toString(), "${metrics.scheduledOffers} programadas"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { item ->
                        DashboardKpiCard(item = item, compact = compact, modifier = Modifier.width(cardWidth).height(if (compact) 120.dp else 128.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardKpiCard(item: KpiItem, compact: Boolean, modifier: Modifier) {
    DashboardSectionSurface(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.value,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (compact) 26.sp else 30.sp, letterSpacing = 0.sp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = item.supporting,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardAlertsSection(
    alerts: List<DashboardAlert>,
    compact: Boolean,
    onQuickAction: (DashboardQuickAction) -> Unit,
) {
    DashboardSectionSurface(modifier = Modifier.heightIn(min = if (compact) 204.dp else 220.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardSectionTitle("Requiere atencion")
            val visibleAlerts = alerts.ifEmpty {
                listOf(
                    DashboardAlert(
                        id = "empty",
                        title = "Sin pendientes criticos",
                        detail = "El panel no tiene incidencias prioritarias.",
                        actionLabel = "Actualizar",
                        severity = DashboardAlertSeverity.INFO,
                    ),
                )
            }
            visibleAlerts.take(5).forEach { alert ->
                DashboardAlertRow(alert = alert, onClick = { onQuickAction(actionForAlert(alert)) })
            }
        }
    }
}

@Composable
private fun DashboardPerformanceSection(metrics: DashboardMetrics, compact: Boolean) {
    DashboardSectionSurface(modifier = Modifier.heightIn(min = if (compact) 466.dp else 452.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DashboardSectionTitle("Rendimiento de usuarios")
            AttemptsBarChart(
                attemptsPerDay = metrics.attemptsPerDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 154.dp else 164.dp),
            )
            DashboardPerformanceMetricGrid(metrics = metrics, compact = compact)
        }
    }
}

@Composable
fun AttemptsBarChart(attemptsPerDay: List<DayCount>, modifier: Modifier = Modifier) {
    val days = attemptsPerDay.takeLast(30)
    val maxCount = days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (days.isEmpty()) {
            Text(
                text = "Sin intentos completados",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    DashboardAttemptBar(day = day, maxCount = maxCount)
                }
            }
        }
    }
}

@Composable
private fun DashboardAttemptBar(day: DayCount, maxCount: Int) {
    val barHeight = if (day.count == 0) 4.dp else ((day.count * 84) / maxCount).coerceAtLeast(8).dp
    val barColor = if (day.count == 0) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    } else {
        GlassTheme.tokens.accent
    }
    Column(
        modifier = Modifier
            .width(28.dp)
            .height(130.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = day.count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(92.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(barColor),
            )
        }
        Text(
            text = shortDateLabel(day.date),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardPerformanceMetricGrid(metrics: DashboardMetrics, compact: Boolean) {
    val cardWidth = if (compact) 138.dp else 248.dp
    val cardHeight = if (compact) 86.dp else 92.dp
    // Una métrica que el backend no pudo calcular se enseña como NO_DATA, nunca
    // como el 0 de relleno que viaja en el DTO: en un panel de administración un
    // cero inventado es indistinguible de un cero real y se toman decisiones con él.
    fun kpi(key: String, title: String, supporting: String, format: () -> String) = if (metrics.isAvailable(key)) {
        KpiItem(title, format(), supporting)
    } else {
        KpiItem(title, NO_DATA_LABEL, NO_DATA_SUPPORTING)
    }

    val items = listOf(
        kpi(DashboardMetricKey.ACTIVE_USERS_30D, "Usuarios activos (30d)", "Últimos 30 días") {
            metrics.activeUsers30d.toString()
        },
        kpi(DashboardMetricKey.TOTAL_ATTEMPTS, "Intentos totales", "Completados") {
            metrics.totalAttempts.toString()
        },
        kpi(DashboardMetricKey.AVG_SCORE, "Puntuación media", "Acierto global") {
            "${metrics.avgScore.roundToInt()}%"
        },
        kpi(DashboardMetricKey.GLOBAL_ERROR_RATE, "Tasa de error", "Respuestas falladas") {
            "${metrics.globalErrorRate.roundToInt()}%"
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEachIndexed { index, item ->
                    DashboardPerformanceMetricCard(
                        item = item,
                        accentColor = when (item.title) {
                            "Tasa de error" -> MaterialTheme.colorScheme.error
                            "Puntuación media" -> GlassTheme.tokens.success
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.width(cardWidth).height(cardHeight),
                    )
                    if (rowItems.size == 1 && index == 0) {
                        Box(modifier = Modifier.width(cardWidth).height(cardHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardPerformanceMetricCard(item: KpiItem, accentColor: Color, modifier: Modifier) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.value,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, letterSpacing = 0.sp),
                color = accentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.supporting,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardAlertRow(alert: DashboardAlert, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(alertColor(alert.severity).copy(alpha = 0.08f))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(alertColor(alert.severity)),
        )
        Column(modifier = Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = alert.title,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = alert.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = alert.actionLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardQuickActions(compact: Boolean, onQuickAction: (DashboardQuickAction) -> Unit) {
    val actions = listOf(
        QuickActionItem("Contenido", "Crear examen", DashboardQuickAction.CREATE_EXAM),
        QuickActionItem("Contenido", "Importar preguntas", DashboardQuickAction.IMPORT_QUESTIONS),
        QuickActionItem("Publicación", "Crear noticia", DashboardQuickAction.CREATE_NEWS),
        QuickActionItem("Publicación", "Activar oferta", DashboardQuickAction.ACTIVATE_OFFER),
        QuickActionItem("Control", "Revisar tickets", DashboardQuickAction.REVIEW_TICKETS),
        QuickActionItem("Control", "Ver auditoría", DashboardQuickAction.OPEN_AUDIT),
    )
    DashboardSectionSurface {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DashboardSectionTitle("Accesos rapidos")
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (compact) 2 else 3
                val gap = 12.dp
                val tileWidth = if (compact) (maxWidth - gap) / 2 else 164.dp
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    actions.chunked(columns).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            rowItems.forEach { item ->
                                DashboardQuickTile(
                                    item = item,
                                    modifier = Modifier.width(tileWidth).height(if (compact) 84.dp else 88.dp),
                                    onClick = { onQuickAction(item.action) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardQuickTile(item: QuickActionItem, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.group.take(1),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardPanelGroup(title: String, panels: List<InfoPanel>, compact: Boolean) {
    DashboardSectionSurface {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DashboardSectionTitle(title)
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEach { panel -> DashboardInfoPanel(panel = panel, modifier = Modifier.fillMaxWidth().height(168.dp)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEach { panel -> DashboardInfoPanel(panel = panel, modifier = Modifier.width(340.dp).height(168.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DashboardInfoPanel(panel: InfoPanel, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = panel.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
            )
            panel.lines.take(4).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DashboardBottomSection(
    metrics: DashboardMetrics,
    environmentLabel: String,
    compact: Boolean,
    onOpenAudit: () -> Unit,
) {
    val panels = listOf(
        InfoPanel(
            title = "Actividad reciente",
            lines = metrics.recentEvents.take(6).map { "${it.title}: ${it.detail}" }.ifEmpty { listOf("Sin eventos recientes") },
        ),
        InfoPanel(
            title = "Estado tecnico",
            lines = listOf(
                "Última sync: ${compactInstant(metrics.lastUpdatedAt)}",
                "Origen: $environmentLabel",
                "Backend: activo",
                "Auditoría: disponible",
            ),
        ),
    )
    DashboardSectionSurface {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DashboardSectionTitle("Actividad y sistema")
                Text(
                    text = "Ver auditoría",
                    modifier = Modifier.clickable(onClick = onOpenAudit),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEach { panel -> DashboardInfoPanel(panel = panel, modifier = Modifier.fillMaxWidth().height(168.dp)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEach { panel -> DashboardInfoPanel(panel = panel, modifier = Modifier.width(550.dp).height(168.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DashboardSectionSurface(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tint = if (strong) GlassTheme.tokens.glassFillStrong else GlassTheme.tokens.glassFill,
        shadowElevation = if (strong) 12.dp else 8.dp,
    ) {
        content()
    }
}

@Composable
private fun DashboardSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, letterSpacing = 0.sp),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DashboardChip(label: String) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Los botones propios del dashboard (`DashboardExactButton`, `DashboardGhostButton`
// y su `DashboardButtonSurface`) se han sustituido por los del sistema de diseño.
// No tenían hover ni foco ni estado deshabilitado, y su texto iba forzado a 12sp
// —dos puntos por debajo de cualquier otro botón de la app— así que el dashboard
// parecía usar controles de otra aplicación. Se conservan los nombres como
// envoltorios finos para no reescribir las nueve llamadas.

@Composable
private fun DashboardExactButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    PrimaryButton(text = text, onClick = onClick, modifier = modifier, size = ButtonSize.COMPACT)
}

@Composable
private fun DashboardGhostButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    SecondaryButton(text = text, onClick = onClick, modifier = modifier, size = ButtonSize.COMPACT)
}

@Composable
private fun alertColor(severity: DashboardAlertSeverity): Color {
    return when (severity) {
        DashboardAlertSeverity.INFO -> MaterialTheme.colorScheme.secondary
        DashboardAlertSeverity.WARNING -> GlassTheme.tokens.accent
        DashboardAlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    }
}

private fun actionForAlert(alert: DashboardAlert): DashboardQuickAction {
    return when (alert.id) {
        "questions_without_resources" -> DashboardQuickAction.AUDIT_RESOURCES
        "unlinked_resources" -> DashboardQuickAction.AUDIT_RESOURCES
        "pending_tickets" -> DashboardQuickAction.REVIEW_TICKETS
        "draft_news" -> DashboardQuickAction.CREATE_NEWS
        "scheduled_offers", "inactive_bursts" -> DashboardQuickAction.ACTIVATE_OFFER
        "empty" -> DashboardQuickAction.REVIEW_PENDING
        else -> DashboardQuickAction.AUDIT_RESOURCES
    }
}

private fun contentPanels(metrics: DashboardMetrics): List<InfoPanel> {
    return listOf(
        InfoPanel(
            title = "Exámenes",
            lines = listOf(
                "${metrics.publishedExams} publicados",
                "${metrics.draftExams} borradores",
                "${metrics.totalQuestions} preguntas cargadas",
                metrics.latestImportLabel,
            ),
        ),
        InfoPanel(
            title = "Recursos",
            lines = listOf(
                "${metrics.linkedResources} enlazados",
                "${metrics.pendingResources} pendientes",
                "${metrics.unlinkedResources} sin enlazar",
                "${metrics.questionsWithoutResources} preguntas sin recurso",
            ),
        ),
        InfoPanel(
            title = "Noticias",
            lines = listOf(
                "${metrics.publishedNews} publicadas",
                "${metrics.draftNews} borradores",
                "Editor visual conectado",
                "Capas y fondo persistidos",
            ),
        ),
    )
}

private fun commercePanels(metrics: DashboardMetrics): List<InfoPanel> {
    return listOf(
        InfoPanel(
            title = "Tienda",
            lines = listOf(
                "${metrics.activeStoreItems} items activos",
                "${metrics.activeLimitedResources} recursos temporales",
                "Catálogo admin operativo",
                metrics.nextCommerceExpiryLabel,
            ),
        ),
        InfoPanel(
            title = "Ofertas",
            lines = listOf(
                "${metrics.activeOffers} activas",
                "${metrics.scheduledOffers} programadas",
                "Estados draft/active/scheduled",
                "Control por fechas",
            ),
        ),
        InfoPanel(
            title = "Rafagas",
            lines = listOf(
                "${metrics.activeBurstCampaigns} activas",
                "Multiplicadores y tickets",
                "Vinculo con examen o tienda",
                "Programacion temporal",
            ),
        ),
    )
}

private fun compactInstant(value: Instant): String {
    return value.toString()
        .substringBefore(".")
        .removeSuffix("Z")
        .replace("T", " ")
}

private fun shortDateLabel(date: String): String {
    return if (date.length >= 10) {
        "${date.substring(8, 10)}/${date.substring(5, 7)}"
    } else {
        date.takeLast(4)
    }
}

/** Texto único para una métrica sin fuente conectada. Ver DashboardMetrics.unavailableMetrics. */
internal const val NO_DATA_LABEL = "NO_DATA"
internal const val NO_DATA_SUPPORTING = "Sin fuente conectada"

private data class KpiItem(
    val title: String,
    val value: String,
    val supporting: String,
)

private data class QuickActionItem(
    val group: String,
    val title: String,
    val action: DashboardQuickAction,
)

private data class InfoPanel(
    val title: String,
    val lines: List<String>,
)

@Preview
@Composable
private fun DashboardPreview() {
    AdminTheme {
        DashboardContent(
            state = DashboardState(
                metrics = UiAsync.Success(
                    DashboardMetrics(
                        totalSpecialties = 4,
                        totalExams = 12,
                        publishedExams = 8,
                        totalQuestions = 320,
                        lastUpdatedAt = Instant.parse("2026-04-14T09:00:00Z"),
                        draftExams = 4,
                        totalAttempts = 186,
                        activeUsers30d = 42,
                        avgScore = 74.6,
                        globalErrorRate = 25.4,
                        attemptsPerDay = listOf(
                            DayCount("2026-04-08", 4),
                            DayCount("2026-04-09", 7),
                            DayCount("2026-04-10", 3),
                            DayCount("2026-04-11", 10),
                            DayCount("2026-04-12", 8),
                            DayCount("2026-04-13", 14),
                            DayCount("2026-04-14", 11),
                        ),
                        questionsWithoutResources = 18,
                        linkedResources = 106,
                        pendingResources = 3,
                        unlinkedResources = 2,
                        publishedNews = 6,
                        draftNews = 2,
                        activeStoreItems = 5,
                        activeOffers = 1,
                        scheduledOffers = 2,
                        activeBurstCampaigns = 1,
                        activeLimitedResources = 2,
                        nextCommerceExpiryLabel = "Proximo vencimiento: 2026-04-18 10:00",
                        latestImportLabel = "Última revisión: 2026-04-14 09:00",
                        alerts = listOf(
                            DashboardAlert(
                                id = "questions_without_resources",
                                title = "18 preguntas sin recurso",
                                detail = "Completa recursos antes de publicar.",
                                actionLabel = "Auditar",
                                severity = DashboardAlertSeverity.WARNING,
                            ),
                            DashboardAlert(
                                id = "unlinked_resources",
                                title = "2 recursos sin enlazar",
                                detail = "Hay archivos pendientes de asociar.",
                                actionLabel = "Enlazar",
                                severity = DashboardAlertSeverity.CRITICAL,
                            ),
                        ),
                        recentEvents = listOf(
                            DashboardActivityEvent(
                                id = "preview_exam",
                                title = "Examen actualizado",
                                detail = "Constitucion bloque 1",
                                occurredAt = Instant.parse("2026-04-14T08:40:00Z"),
                            ),
                        ),
                    ),
                ),
            ),
            environmentLabel = "Entorno dev | vista previa",
            onRefresh = {},
            onQuickAction = {},
        )
    }
}
