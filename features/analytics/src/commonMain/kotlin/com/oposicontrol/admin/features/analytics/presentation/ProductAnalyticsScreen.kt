package com.oposicontrol.admin.features.analytics.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
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
            emptyMessage = "Esta sección no tiene todavía ninguna métrica.",
        ) { report ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MetricsSection(report)
                report.tables.forEach { table -> AnalyticsTableSection(table) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionSelector(
    selected: AnalyticsSection,
    onSelect: (AnalyticsSection) -> Unit,
    onRefresh: () -> Unit,
) {
    // FlowRow y no Row: son siete botones y en una ventana estrecha el último se
    // saldría de pantalla sin posibilidad de pulsarlo.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnalyticsSection.entries.forEach { section ->
            GlassActionButton(
                text = section.label(),
                onClick = { onSelect(section) },
                tint = if (section == selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        GlassActionButton(text = "Actualizar", onClick = onRefresh)
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
