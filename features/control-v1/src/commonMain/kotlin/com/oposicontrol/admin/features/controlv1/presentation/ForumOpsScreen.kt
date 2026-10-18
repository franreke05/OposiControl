package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumModeration
import com.oposicontrol.admin.domain.model.ForumQueueItem
import com.oposicontrol.admin.domain.model.ForumQueueState

internal const val FORUM_QUEUE_TAG = "control-forum-queue"
internal const val FORUM_CHANNELS_TAG = "control-forum-channels"
internal const val FORUM_BLOCKS_TAG = "control-forum-blocks"

private enum class ForumOpsTab(val id: String, val label: String) {
    QUEUE("queue", "Cola"),
    CHANNELS("channels", "Canales"),
    BLOCKS("blocks", "Bloqueos y silencios"),
    LIMITS("limits", "Límites"),
}

/**
 * OposiRed: moderación, canales y límites. La cola es lo que hace falta mirar cada día; lo demás se toca de vez en cuando.
 */
@Composable
fun ForumOpsScreen(
    queueViewModel: ForumQueueViewModel,
    channelsViewModel: ForumChannelsViewModel,
    blocksViewModel: ForumBlocksViewModel,
    configViewModel: ConfigEditorViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(ForumOpsTab.QUEUE.id) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = ForumOpsTab.entries.map { DesktopTab(it.id, it.label) },
            selectedId = tab,
            onSelect = { tab = it },
        )
        ControlColumn {
            when (tab) {
                ForumOpsTab.CHANNELS.id -> {
                    val state by channelsViewModel.state.collectAsState()
                    ForumChannelsContent(state, channelsViewModel::dispatch)
                }
                ForumOpsTab.BLOCKS.id -> {
                    val state by blocksViewModel.state.collectAsState()
                    ForumBlocksContent(state, blocksViewModel::dispatch)
                }
                ForumOpsTab.LIMITS.id -> ConfigEditorPanel(
                    viewModel = configViewModel,
                    title = "Límites y tamaños del foro",
                    description = "Frecuencias, longitudes y la pre-moderación. Los topes los aplica el servidor al escribir; aquí solo se ajustan.",
                )
                else -> {
                    val state by queueViewModel.state.collectAsState()
                    ForumQueueContent(state, queueViewModel::dispatch)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- cola

@Composable
internal fun ForumQueueContent(state: ForumQueueUiState, onIntent: (ForumQueueIntent) -> Unit) {
    FormSection(
        title = "Cola de moderación",
        description = "Lo que escriben los usuarios se muestra como TEXTO plano. Rechazar, ocultar y silenciar piden motivo y quedan auditados con tu cuenta.",
        icon = AppIcon.MODERATION,
        action = { SecondaryButton(text = "Actualizar", size = ButtonSize.COMPACT, onClick = { onIntent(ForumQueueIntent.Refresh) }) },
    ) {
        FilterPills(
            options = ForumContentType.entries.map { it to it.label },
            selected = state.type,
            onSelect = { onIntent(ForumQueueIntent.SetType(it)) },
        )
        FilterPills(
            options = ForumQueueState.entries.map { it to it.label },
            selected = state.queue,
            onSelect = { onIntent(ForumQueueIntent.SetQueue(it)) },
        )
        AsyncStatePane(
            state = state.items,
            emptyTitle = "Cola vacía",
            emptyMessage = "No hay ${state.type.label.lowercase()} en «${state.queue.label.lowercase()}».",
            onRetry = { onIntent(ForumQueueIntent.Refresh) },
        ) { items ->
            Column(modifier = Modifier.testTag(FORUM_QUEUE_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items.forEach { item -> QueueCard(item, onIntent) }
                if (state.nextCursor != null) {
                    SecondaryButton(text = "Cargar más", size = ButtonSize.COMPACT, loading = state.loadingMore, onClick = { onIntent(ForumQueueIntent.LoadMore) })
                }
            }
        }
        ActionFeedback(state.feedback, success = (state.feedback as? UiAsync.Success<String>)?.value ?: "Hecho.")
    }
    val pending = state.pending
    if (pending != null) ForumActionDialog(pending, state.feedback, onIntent)
}

@Composable
private fun QueueCard(item: ForumQueueItem, onIntent: (ForumQueueIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            WorkspacePill(text = item.channel ?: "Sin canal")
            WorkspacePill(text = handleOrDash(item.authorHandle))
            Text(formatWhen(item.createdAt), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            if (item.hasImage) WorkspacePill(text = "Con imagen", icon = AppIcon.VIEW)
            if (item.pendingReports > 0) {
                WorkspacePill(text = "${item.pendingReports} reportes: " + item.reportReasons.joinToString(", ") { ForumModeration.reportReasonLabel(it) })
            }
        }
        item.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
        }
        Text(item.excerpt, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ForumModeration.actionsFor(item.state).forEach { action ->
                if (action.destructive) {
                    DangerOutlineButton(text = action.label, size = ButtonSize.COMPACT, onClick = { onIntent(ForumQueueIntent.Choose(item.id, action)) })
                } else {
                    PrimaryButton(text = action.label, size = ButtonSize.COMPACT, onClick = { onIntent(ForumQueueIntent.Choose(item.id, action)) })
                }
            }
        }
    }
}

@Composable
private fun ForumActionDialog(pending: ForumPendingAction, feedback: UiAsync<String>, onIntent: (ForumQueueIntent) -> Unit) {
    FormDialog(onDismiss = { onIntent(ForumQueueIntent.Dismiss) }) {
        FormSection(
            title = pending.action.label,
            description = "«${pending.item.excerpt.take(120)}»",
        ) {
            if (pending.action.needsReason) {
                LabeledField(
                    label = "Motivo",
                    value = pending.reason,
                    onValueChange = { onIntent(ForumQueueIntent.UpdateReason(it)) },
                    supportingText = pending.error ?: "De ${ForumModeration.REASON_MIN} a ${ForumModeration.REASON_MAX} caracteres. Queda en auditoría.",
                    isError = pending.error != null,
                    singleLine = false,
                    minLines = 2,
                )
            }
            if (pending.action == ForumAction.MUTE) {
                LabeledField(
                    label = "Días de silencio",
                    value = pending.days,
                    onValueChange = { onIntent(ForumQueueIntent.UpdateDays(it)) },
                    supportingText = "De ${ForumModeration.MUTE_DAYS_MIN} a ${ForumModeration.MUTE_DAYS_MAX}. No puede publicar, comentar ni votar durante ese tiempo.",
                )
            }
            ActionFeedback(feedback, loading = "Aplicando…")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostButton(text = "Cancelar", onClick = { onIntent(ForumQueueIntent.Dismiss) })
                if (pending.action.destructive) {
                    DangerOutlineButton(text = pending.action.label, loading = feedback is UiAsync.Loading, onClick = { onIntent(ForumQueueIntent.Confirm) })
                } else {
                    PrimaryButton(text = pending.action.label, loading = feedback is UiAsync.Loading, onClick = { onIntent(ForumQueueIntent.Confirm) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- canales

@Composable
internal fun ForumChannelsContent(state: ForumChannelsUiState, onIntent: (ForumChannelsIntent) -> Unit) {
    FormSection(
        title = "Canales",
        description = "Un canal puede limitarse a una especialidad (el resto de usuarios no lo ve) o ser de solo lectura. Para quitar un canal, desactívalo: nunca se borra.",
        icon = AppIcon.FORUM,
        action = { PrimaryButton(text = "Nuevo canal", size = ButtonSize.COMPACT, onClick = { onIntent(ForumChannelsIntent.New) }) },
    ) {
        AsyncStatePane(
            state = state.channels,
            emptyTitle = "Sin canales",
            emptyMessage = "Todavía no hay ningún canal. Crea el primero.",
            onRetry = { onIntent(ForumChannelsIntent.Refresh) },
        ) { channels ->
            AdminTable(
                modifier = Modifier.testTag(FORUM_CHANNELS_TAG),
                columns = listOf(AdminTableColumn("Canal"), AdminTableColumn("Identificador"), AdminTableColumn("Estado"), AdminTableColumn("Acceso")),
                rows = channels.map { c ->
                    AdminTableRow(
                        key = c.id,
                        cells = listOf(c.name, c.slug, if (c.active) "Activo" else "Inactivo", channelAccess(c)),
                        selected = c.id == state.selectedId,
                        supporting = c.description,
                    )
                },
                onRowClick = { onIntent(ForumChannelsIntent.Select(it)) },
            )
        }
    }
    if (state.editing) {
        FormSection(title = if (state.selectedId == null) "Nuevo canal" else "Editar canal") {
            val d = state.draft
            LabeledField(
                label = "Nombre",
                value = d.name,
                onValueChange = { onIntent(ForumChannelsIntent.Update(d.copy(name = it))) },
                supportingText = state.errors["name"],
                isError = state.errors.containsKey("name"),
            )
            LabeledField(
                label = "Identificador",
                value = d.slug,
                onValueChange = { onIntent(ForumChannelsIntent.Update(d.copy(slug = it), slugEdited = true)) },
                supportingText = state.errors["slug"] ?: "Minúsculas, números y guiones. Se sugiere desde el nombre.",
                isError = state.errors.containsKey("slug"),
            )
            LabeledField(
                label = "Descripción",
                value = d.description,
                onValueChange = { onIntent(ForumChannelsIntent.Update(d.copy(description = it))) },
                supportingText = state.errors["description"],
                isError = state.errors.containsKey("description"),
                singleLine = false,
                minLines = 2,
            )
            LabeledField(
                label = "Posición",
                value = d.position,
                onValueChange = { onIntent(ForumChannelsIntent.Update(d.copy(position = it))) },
                supportingText = state.errors["position"],
                isError = state.errors.containsKey("position"),
            )
            GlassToggleRow(title = "Activo", checked = d.active, onCheckedChange = { onIntent(ForumChannelsIntent.Update(d.copy(active = it))) })
            GlassToggleRow(
                title = "Solo lectura",
                description = "Los usuarios pueden leer pero no publicar ni comentar.",
                checked = d.readOnly,
                onCheckedChange = { onIntent(ForumChannelsIntent.Update(d.copy(readOnly = it))) },
            )
            ActionFeedback(state.save, success = "Canal guardado.")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostButton(text = "Cancelar", onClick = { onIntent(ForumChannelsIntent.Cancel) })
                PrimaryButton(text = "Guardar", loading = state.save is UiAsync.Loading, onClick = { onIntent(ForumChannelsIntent.Save) })
            }
        }
    }
}

private fun channelAccess(c: ForumChannel): String = buildList {
    add(if (c.specialtyId == null) "Todas las especialidades" else "Una especialidad")
    if (c.readOnly) add("solo lectura")
}.joinToString(" · ")

// ---------------------------------------------------------------- bloqueos y silencios

@Composable
internal fun ForumBlocksContent(state: ForumBlocksUiState, onIntent: (ForumBlocksIntent) -> Unit) {
    FormSection(
        title = "Lista de bloqueo",
        description = "Un texto que contenga uno de estos patrones no se publica. Se guardan en minúsculas.",
        icon = AppIcon.BLOCKED,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Bottom) {
            LabeledField(
                label = "Nuevo patrón",
                value = state.newPattern,
                onValueChange = { onIntent(ForumBlocksIntent.UpdatePattern(it)) },
                supportingText = state.patternError,
                isError = state.patternError != null,
            )
            PrimaryButton(text = "Añadir", size = ButtonSize.COMPACT, onClick = { onIntent(ForumBlocksIntent.Add) })
        }
        AsyncStatePane(
            state = state.patterns,
            emptyTitle = "Sin patrones",
            emptyMessage = "La lista de bloqueo está vacía.",
            onRetry = { onIntent(ForumBlocksIntent.Refresh) },
        ) { patterns ->
            AdminTable(
                modifier = Modifier.testTag(FORUM_BLOCKS_TAG),
                columns = listOf(AdminTableColumn("Patrón")),
                rows = patterns.map { AdminTableRow(key = it, cells = listOf(it)) },
                rowActions = { row ->
                    DangerOutlineButton(text = "Quitar", size = ButtonSize.COMPACT, onClick = { onIntent(ForumBlocksIntent.RequestRemove(row.key)) })
                },
            )
        }
    }
    FormSection(
        title = "Silencios activos",
        description = "Autores que no pueden escribir en el foro hasta la fecha indicada. Levantar el silencio es inmediato.",
        icon = AppIcon.CLOCK,
    ) {
        AsyncStatePane(
            state = state.restrictions,
            emptyTitle = "Sin silencios",
            emptyMessage = "Nadie está silenciado ahora mismo.",
            onRetry = { onIntent(ForumBlocksIntent.Refresh) },
        ) { list ->
            AdminTable(
                columns = listOf(AdminTableColumn("Autor"), AdminTableColumn("Hasta"), AdminTableColumn("Motivo")),
                rows = list.map { r -> AdminTableRow(key = r.userId, cells = listOf(handleOrDash(r.handle), formatWhen(r.until), r.reason ?: "—")) },
                rowActions = { row ->
                    SecondaryButton(text = "Levantar", size = ButtonSize.COMPACT, onClick = { onIntent(ForumBlocksIntent.RequestLift(row.key)) })
                },
            )
        }
        ActionFeedback(state.feedback, success = (state.feedback as? UiAsync.Success<String>)?.value ?: "Hecho.")
    }
    FormSection(
        title = "Imágenes retiradas",
        description = "Las imágenes de publicaciones rechazadas, ocultas o eliminadas dejan de servirse al instante; el barrido borra después el archivo. Solo lectura.",
        icon = AppIcon.BLOCKED,
    ) {
        AsyncStatePane(
            state = state.imagePurge,
            emptyTitle = "Sin datos",
            emptyMessage = "No hay datos de purga.",
            onRetry = { onIntent(ForumBlocksIntent.Refresh) },
        ) { purge ->
            WorkspaceNotice(
                tone = if (purge.stuck > 0) WorkspaceNoticeTone.WARNING else WorkspaceNoticeTone.INFO,
                title = purge.summary(),
                detail = purge.oldestAt?.let { "La más antigua lleva esperando desde ${formatWhen(it)}." },
            )
        }
    }
    state.confirmRemove?.let { pattern ->
        ConfirmDialog(
            title = "Quitar patrón",
            message = "«$pattern» dejará de bloquear textos. Queda registrado en auditoría.",
            confirmLabel = "Quitar",
            onConfirm = { onIntent(ForumBlocksIntent.ConfirmRemove) },
            onDismiss = { onIntent(ForumBlocksIntent.Dismiss) },
        )
    }
    state.confirmLift?.let { r ->
        ConfirmDialog(
            title = "Levantar silencio",
            message = "${handleOrDash(r.handle)} podrá escribir de nuevo en el foro. Queda registrado en auditoría.",
            confirmLabel = "Levantar",
            destructive = false,
            onConfirm = { onIntent(ForumBlocksIntent.ConfirmLift) },
            onDismiss = { onIntent(ForumBlocksIntent.Dismiss) },
        )
    }
}

