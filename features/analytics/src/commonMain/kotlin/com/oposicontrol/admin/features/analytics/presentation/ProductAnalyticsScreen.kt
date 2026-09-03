package com.oposicontrol.admin.features.analytics.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SummaryCard
import com.oposicontrol.admin.core.ui.layout.DesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport
import com.oposicontrol.admin.domain.model.AnalyticsTable

/**
 * Analítica de producto.
 *
 * Seis secciones en una sola pantalla con selector, no seis entradas de menú:
 * quien mira "cuántos usuarios activos hay" mira a continuación "cuántos
 * intentos" y "cuánto se ha ingresado", y repartirlo por el árbol de navegación
 * obligaría a ir y volver para leer una sola historia.
 *
 * Tampoco son cincuenta tarjetas sueltas: cada sección es una tabla densa de
 * escritorio, que es lo que permite comparar de un vistazo qué hay medido y qué
 * no. La columna "Estado" es la que da valor real al panel — dice si el número
 * es un dato, si no se pudo leer, o si nadie lo ha instrumentado todavía.
 */
@Composable
fun ProductAnalyticsScreen(viewModel: ProductAnalyticsViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionSelector(
            selected = state.section,
            onSelect = { viewModel.dispatch(ProductAnalyticsIntent.SelectSection(it)) },
            onRefresh = { viewModel.dispatch(ProductAnalyticsIntent.Refresh) },
        )

        AsyncStatePane(
            state = state.current,
            emptyTitle = "Sección sin métricas",
            emptyMessage = "Esta sección no tiene todavía ninguna métrica definida.",
            emptyIcon = AppIcon.ANALYTICS,
            onRetry = { viewModel.dispatch(ProductAnalyticsIntent.Refresh) },
        ) { report ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverageHeader(report, DesktopWindow.breakpoint)
                MetricsSection(report)
                report.tables.forEach { table -> AnalyticsTableSection(table) }
            }
        }
    }
}

@Composable
private fun SectionSelector(
    selected: AnalyticsSection,
    onSelect: (AnalyticsSection) -> Unit,
    onRefresh: () -> Unit,
) {
    // Pestañas y no siete botones rellenos: elegir sección no son siete acciones
    // distintas, es una sola vista con siete caras, y siete rectángulos de color
    // seguidos tapaban visualmente la tabla que viene justo debajo. Las pestañas
    // se desplazan solas en horizontal si la ventana es estrecha, así que sigue
    // sin perderse la última.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DesktopTabs(
            modifier = Modifier.weight(1f),
            tabs = AnalyticsSection.entries.map { DesktopTab(it.name, it.label()) },
            selectedId = selected.name,
            onSelect = { id ->
                AnalyticsSection.entries.firstOrNull { it.name == id }?.let(onSelect)
            },
        )
        SecondaryButton(
            text = "Actualizar",
            onClick = onRefresh,
            size = ButtonSize.COMPACT,
            icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
        )
    }
}

/**
 * Cabecera de cobertura (§36: primero los KPI, luego el detalle).
 *
 * Los tres números no son métricas de producto: dicen cuánto de esta pantalla se
 * puede creer. Es lo primero que hay que saber antes de leer cualquier cifra de
 * abajo, y evita la trampa de §62 — un cero medido y un cero por falta de
 * instrumentación se leen igual en una tabla, pero aquí quedan separados.
 */
@Composable
private fun CoverageHeader(report: AnalyticsSectionReport, breakpoint: DesktopBreakpoint) {
    val unavailable = report.metrics.size - report.availableCount - report.notInstrumentedCount
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Con dato real",
            value = report.availableCount.toString(),
            supporting = "de ${report.metrics.size} métricas",
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Sin instrumentar",
            value = report.notInstrumentedCount.toString(),
            supporting = "nadie las mide todavía",
        )
        if (breakpoint.isAtLeastStandard) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                title = "No disponibles",
                value = unavailable.coerceAtLeast(0).toString(),
                supporting = "no se pudieron leer",
            )
        }
    }
}

@Composable
private fun MetricsSection(report: AnalyticsSectionReport) {
    FormSection(
        title = report.section.label(),
        description = buildString {
            append(report.section.description())
            append("  ·  ")
            append("${report.availableCount} de ${report.metrics.size} con dato real")
            if (report.notInstrumentedCount > 0) {
                append(", ${report.notInstrumentedCount} sin instrumentar")
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AdminTable(
                columns = listOf(
                    AdminTableColumn("Métrica", 1.4f),
                    AdminTableColumn("Valor", 0.8f),
                    AdminTableColumn("Estado", 0.8f),
                ),
                rows = report.metrics.map { metric ->
                    AdminTableRow(
                        key = metric.key,
                        cells = listOf(
                            analyticsMetricLabel(metric.key),
                            metric.displayValue(),
                            metric.statusLabel(),
                        ),
                        // La razón sólo existe para los huecos estructurales, y es
                        // justo lo accionable: qué vista o RPC hay que escribir.
                        supporting = metric.reason,
                    )
                },
            )
            if (report.generatedAt.isNotBlank()) {
                Text(
                    text = "Calculado en el backend a las ${report.generatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnalyticsTableSection(table: AnalyticsTable) {
    FormSection(title = analyticsMetricLabel(table.key)) {
        when (table.status) {
            AnalyticsMetricStatus.AVAILABLE -> if (table.rows.isEmpty()) {
                Text(
                    text = "Todavía no hay filas que mostrar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AdminTable(
                    columns = table.columns.map { AdminTableColumn(it, 1f) },
                    rows = table.rows.map { row -> AdminTableRow(key = row.key, cells = row.cells) },
                )
            }
            // Una tabla que no se pudo leer no se pinta vacía: una tabla vacía se
            // lee como "no hay nada", que es una afirmación que aquí nadie puede hacer.
            AnalyticsMetricStatus.NO_DATA -> Text(
                text = "$NO_DATA_LABEL — el backend no pudo leer estos datos. Prueba a actualizar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            AnalyticsMetricStatus.NOT_INSTRUMENTED -> Text(
                text = "$NOT_INSTRUMENTED_LABEL — ${table.reason ?: "falta el agregado que lo calcula."}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
