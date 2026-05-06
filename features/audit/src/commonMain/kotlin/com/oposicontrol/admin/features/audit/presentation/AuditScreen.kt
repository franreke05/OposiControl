package com.oposicontrol.admin.features.audit.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection


@Composable
fun AuditScreen(
    viewModel: AuditViewModel,
) {
    val state by viewModel.state.collectAsState()
    AuditContent(state = state)
}

@Composable
fun AuditContent(
    state: AuditState,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilterBar(
            searchValue = searchQuery,
            onSearchChange = { searchQuery = it },
            placeholder = "Filtrar por actor, accion o recurso",
        )
        FormSection(
            title = "Auditoria",
            description = "Linea temporal administrativa para operaciones criticas.",
        ) {
            AsyncStatePane(state.events, emptyMessage = "No hay eventos de auditoria.") { events ->
                val filtered = events.filter {
                    searchQuery.isBlank() ||
                        it.actorName.contains(searchQuery, ignoreCase = true) ||
                        it.action.contains(searchQuery, ignoreCase = true) ||
                        it.target.contains(searchQuery, ignoreCase = true)
                }
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Actor", 1f),
                        AdminTableColumn("Accion", 1f),
                        AdminTableColumn("Recurso", 1.2f),
                        AdminTableColumn("Fecha", 1f),
                    ),
                    rows = filtered.map { event ->
                        AdminTableRow(
                            key = event.id,
                            cells = listOf(event.actorName, event.action, event.target, event.occurredAt.toString()),
                            supporting = event.metadata.entries.joinToString { "${it.key}=${it.value}" },
                        )
                    },
                )
            }
        }
    }
}


