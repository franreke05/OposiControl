package com.oposicontrol.admin.features.audit.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton

@Composable
fun AuditScreen(
    viewModel: AuditViewModel,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilterBar(
            searchValue = state.searchQuery,
            onSearchChange = { viewModel.dispatch(AuditIntent.UpdateSearch(it)) },
            placeholder = "Filtrar por actor, acción o recurso",
            filters = {
                GlassActionButton(
                    text = "Actualizar",
                    modifier = Modifier.width(120.dp),
                    onClick = { viewModel.dispatch(AuditIntent.Refresh) },
                )
            },
        )
        if (state.lastRefreshedAt.isNotBlank()) {
            Text(
                text = "Última carga: ${state.lastRefreshedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FormSection(
            title = "Auditoría",
            description = "Linea temporal administrativa para operaciones criticas.",
        ) {
            AsyncStatePane(state.events, emptyMessage = "No hay eventos de auditoría.") { events ->
                val filtered = events.filter {
                    state.searchQuery.isBlank() ||
                        it.actorName.contains(state.searchQuery, ignoreCase = true) ||
                        it.action.contains(state.searchQuery, ignoreCase = true) ||
                        it.target.contains(state.searchQuery, ignoreCase = true)
                }
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Actor", 1f),
                        AdminTableColumn("Acción", 1f),
                        AdminTableColumn("Recurso", 1.2f),
                        AdminTableColumn("Fecha", 1f),
                    ),
                    rows = filtered.map { event ->
                        AdminTableRow(
                            key = event.id,
                            cells = listOf(
                                event.actorName,
                                event.action,
                                event.target,
                                event.occurredAt.toString().substringBefore(".").removeSuffix("Z").replace("T", " ").takeLast(16),
                            ),
                            supporting = event.metadata.entries.joinToString { "${it.key}=${it.value}" },
                        )
                    },
                )
            }
        }
    }
}
