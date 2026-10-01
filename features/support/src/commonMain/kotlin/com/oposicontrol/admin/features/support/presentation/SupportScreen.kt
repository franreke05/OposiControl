package com.oposicontrol.admin.features.support.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme

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
                        GlassTextField(
                            value = state.statusFilter,
                            onValueChange = { onIntent(SupportIntent.UpdateStatusFilter(it)) },
                            placeholder = "Estado",
                            modifier = Modifier.width(160.dp),
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
                    description = "Lectura del chat, asignación y respuesta.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(
                            (state.conversations as? UiAsync.Success)
                                ?.value
                                ?.firstOrNull { it.id == state.selectedConversationId }
                                ?.status ?: "open",
                        )
                        FieldLabel("Asignado a")
                        GlassTextField(
                            value = state.assignee,
                            onValueChange = { onIntent(SupportIntent.UpdateAssignee(it)) },
                            placeholder = "Responsable del hilo",
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
                                                    GlassTheme.tokens.warning.copy(alpha = 0.12f)
                                                } else {
                                                    GlassTheme.tokens.glassFill
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
                                            Text(message.timestamp, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                FormSection(
                    title = "Responder",
                    description = "Respuesta visible para el usuario.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FieldLabel("Mensaje")
                        GlassTextField(
                            value = state.replyText,
                            onValueChange = { onIntent(SupportIntent.UpdateReplyText(it)) },
                            placeholder = "Escribe tu respuesta…",
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GlassActionButton(
                            text = "Enviar respuesta",
                            enabled = state.replyText.isNotBlank(),
                            onClick = { onIntent(SupportIntent.SendReply) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FormFeedback(state.actionState, loadingText = "Enviando...", successText = "Mensaje enviado.")
                    }
                }
            }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
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
