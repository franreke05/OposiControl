package com.oposicontrol.admin.features.support.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.AdminTheme

@Composable
fun SupportScreen(
    viewModel: SupportViewModel,
) {
    val state by viewModel.state.collectAsState()
    SupportContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
fun SupportContent(
    state: SupportState,
    onIntent: (SupportIntent) -> Unit,
) {
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { onIntent(SupportIntent.UpdateSearch(it)) },
                    placeholder = "Buscar conversaciones",
                    filters = {
                        OutlinedTextField(
                            value = state.statusFilter,
                            onValueChange = { onIntent(SupportIntent.UpdateStatusFilter(it)) },
                            label = { Text("Estado") },
                            singleLine = true,
                        )
                    },
                )
                AsyncStatePane(state.conversations, emptyMessage = "No hay conversaciones abiertas.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Usuario", 1f),
                            AdminTableColumn("Asunto", 1.4f),
                            AdminTableColumn("Estado", 0.8f),
                            AdminTableColumn("Asignado", 1f),
                        ),
                        rows = items.map { conversation ->
                            AdminTableRow(
                                key = conversation.id,
                                cells = listOf(conversation.userName, conversation.subject, conversation.status, conversation.assignedTo),
                                selected = conversation.id == state.selectedConversationId,
                                supporting = conversation.lastMessage,
                            )
                        },
                        onRowClick = { onIntent(SupportIntent.SelectConversation(it)) },
                    )
                }
            }
        },
        detailPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormSection(
                    title = "Detalle del hilo",
                    description = "Misma pantalla para lectura del chat, asignacion y respuesta.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(
                            (state.conversations as? UiAsync.Success)
                                ?.value
                                ?.firstOrNull { it.id == state.selectedConversationId }
                                ?.status ?: "open",
                        )
                        OutlinedTextField(
                            value = state.assignee,
                            onValueChange = { onIntent(SupportIntent.UpdateAssignee(it)) },
                            label = { Text("Asignado a") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AsyncStatePane(state.messages, emptyMessage = "No hay mensajes en este hilo.") { messages ->
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(messages, key = { it.id }) { message ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = if (message.internal) {
                                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                shape = MaterialTheme.shapes.medium,
                                            )
                                            .padding(12.dp),
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            StatusChip(if (message.internal) "internal_note" else "message")
                                            Text(message.authorName, style = MaterialTheme.typography.titleSmall)
                                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                            if (message.attachmentLabel != null) {
                                                Text(message.attachmentLabel, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(message.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                FormSection(
                    title = "Responder",
                    description = "Base para respuesta admin/moderador y futuras notas internas privadas.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.replyText,
                            onValueChange = { onIntent(SupportIntent.UpdateReplyText(it)) },
                            label = { Text("Mensaje") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Enviar como nota interna")
                            Switch(
                                checked = state.sendAsInternalNote,
                                onCheckedChange = { onIntent(SupportIntent.ToggleInternalNote(it)) },
                            )
                        }
                        Button(
                            onClick = { onIntent(SupportIntent.SendReply) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enviar respuesta")
                        }
                        when (val actionState = state.actionState) {
                            is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                            UiAsync.Loading -> Text("Enviando...")
                            is UiAsync.Success -> Text("Mensaje enviado.")
                            else -> Unit
                        }
                    }
                }
            }
        },
    )
}

@Preview
@Composable
private fun SupportScreenPreview() {
    AdminTheme {
        SupportContent(
            state = SupportState(
                conversations = UiAsync.Success(
                    listOf(
                        SupportConversation("conv-1", "Alicia Torres", "No veo un examen comprado", "open", "Sonia Admin", "Necesito acceso hoy"),
                    ),
                ),
                messages = UiAsync.Success(
                    listOf(
                        SupportMessageItem("msg-1", "Alicia Torres", "He comprado el examen y no aparece.", "10:02", false),
                        SupportMessageItem("msg-2", "Sonia Admin", "Lo reviso ahora mismo.", "10:08", false),
                    ),
                ),
                selectedConversationId = "conv-1",
                assignee = "Sonia Admin",
            ),
            onIntent = {},
        )
    }
}
