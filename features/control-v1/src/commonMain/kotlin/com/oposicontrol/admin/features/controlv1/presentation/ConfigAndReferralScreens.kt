package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ReferralItem
import com.oposicontrol.admin.domain.model.ReferralReason
import com.oposicontrol.admin.domain.model.ReferralStates

internal const val CONFIG_EDITOR_TAG = "control-config-editor"
internal const val REFERRAL_TABLE_TAG = "control-referral-table"

/**
 * Los números de un grupo de configuración, editables uno a uno. Un interruptor (rango 0..1) pide confirmación antes de
 * escribir; un número muestra su rango y el error del servidor o del propio formulario junto al campo.
 */
@Composable
fun ConfigEditorPanel(
    viewModel: ConfigEditorViewModel,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ConfigEditorContent(state, viewModel::dispatch, title, description, modifier)
}

@Composable
internal fun ConfigEditorContent(
    state: ConfigEditorState,
    onIntent: (ConfigEditorIntent) -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    FormSection(title = title, description = description, modifier = modifier.testTag(CONFIG_EDITOR_TAG)) {
        AsyncStatePane(
            state = state.entries,
            emptyTitle = "Sin configuración",
            emptyMessage = "El servidor no devuelve ninguna clave de este grupo.",
            onRetry = { onIntent(ConfigEditorIntent.Refresh) },
        ) { entries ->
            ConfigRows(entries, state, onIntent)
        }
        ActionFeedback(state.feedback, success = "Valor guardado.")
    }
    val pending = state.pendingSwitch
    if (pending != null) {
        ConfirmDialog(
            title = if (pending.on) "Activar «${pending.label}»" else "Desactivar «${pending.label}»",
            message = if (pending.on) {
                "Pasará a estar activo para todos los usuarios en cuanto se guarde. Queda registrado en auditoría con tu cuenta."
            } else {
                "Dejará de estar activo para todos los usuarios en cuanto se guarde. Queda registrado en auditoría con tu cuenta."
            },
            confirmLabel = if (pending.on) "Activar" else "Desactivar",
            destructive = !pending.on,
            onConfirm = { onIntent(ConfigEditorIntent.ConfirmSwitch) },
            onDismiss = { onIntent(ConfigEditorIntent.DismissSwitch) },
        )
    }
}

@Composable
private fun ConfigRows(entries: List<ConfigEntry>, state: ConfigEditorState, onIntent: (ConfigEditorIntent) -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        entries.forEach { entry ->
            if (entry.value == null) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = entry.label,
                    detail = "Esta clave no existe todavía en el servidor conectado: no se puede editar desde aquí.",
                )
            } else if (entry.isSwitch) {
                GlassToggleRow(
                    title = entry.label,
                    description = entry.help,
                    checked = entry.value == 1,
                    enabled = state.savingKey == null,
                    onCheckedChange = { on -> onIntent(ConfigEditorIntent.RequestSwitch(entry.key, on)) },
                )
            } else {
                val text = state.textFor(entry)
                val dirty = text.trim() != entry.value.toString()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Bottom) {
                    LabeledField(
                        label = entry.label,
                        value = text,
                        onValueChange = { onIntent(ConfigEditorIntent.Edit(entry.key, it)) },
                        supportingText = state.errors[entry.key] ?: "De ${entry.min} a ${entry.max}." + (entry.help?.let { " $it" } ?: ""),
                        isError = state.errors.containsKey(entry.key),
                    )
                    PrimaryButton(
                        text = "Guardar",
                        size = ButtonSize.COMPACT,
                        enabled = dirty && state.savingKey == null,
                        loading = state.savingKey == entry.key,
                        onClick = { onIntent(ConfigEditorIntent.Save(entry.key)) },
                    )
                }
            }
        }
    }
}

// ====================================================================================================================
// Invitaciones
// ====================================================================================================================

/** Página «Invitaciones»: configuración del programa y lista con revocación. */
@Composable
fun ReferralScreen(configViewModel: ConfigEditorViewModel, viewModel: ReferralViewModel) {
    val state by viewModel.state.collectAsState()
    ControlColumn {
        ConfigEditorPanel(
            viewModel = configViewModel,
            title = "Programa Invita y gana",
            description = "Lo que se paga y cuándo. Empieza APAGADO: nadie recibe recompensa hasta que lo actives. " +
                "Los importes son tuyos; el servidor solo comprueba el rango.",
        )
        ReferralListContent(state, viewModel::dispatch)
    }
}

@Composable
internal fun ReferralListContent(state: ReferralState, onIntent: (ReferralIntent) -> Unit) {
    FormSection(
        title = "Invitaciones",
        description = "Solo se muestran los nombres de usuario públicos. Una invitación premiada se puede revocar: se revierte lo que aún no se ha gastado.",
        icon = AppIcon.USERS,
        action = {
            SecondaryButton(text = "Actualizar", size = ButtonSize.COMPACT, onClick = { onIntent(ReferralIntent.Refresh) })
        },
    ) {
        FilterPills(
            options = listOf<Pair<String?, String>>(null to "Todas") + ReferralStates.ALL.map { it to ReferralStates.label(it) },
            selected = state.filter,
            onSelect = { onIntent(ReferralIntent.SetFilter(it)) },
        )
        if (state.summary.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                ReferralStates.ALL.filter { state.summary.containsKey(it) }.forEach {
                    WorkspacePill(text = "${ReferralStates.label(it)}: ${state.summary.getValue(it)}")
                }
            }
        }
        AsyncStatePane(
            state = state.items,
            emptyTitle = "Sin invitaciones",
            emptyMessage = if (state.filter == null) "Todavía nadie se ha registrado con un código de invitación." else "Ninguna invitación en este estado.",
            onRetry = { onIntent(ReferralIntent.Refresh) },
        ) { items ->
            ReferralTable(items, state, onIntent)
        }
        ActionFeedback(state.action, success = (state.action as? UiAsync.Success<String>)?.value ?: "Hecho.")
    }
    val target = state.revokeTarget
    if (target != null) RevokeDialog(target, state, onIntent)
}

@Composable
private fun ReferralTable(items: List<ReferralItem>, state: ReferralState, onIntent: (ReferralIntent) -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AdminTable(
            modifier = Modifier.testTag(REFERRAL_TABLE_TAG),
            columns = listOf(
                AdminTableColumn("Invita"),
                AdminTableColumn("Invitado"),
                AdminTableColumn("Estado"),
                AdminTableColumn("Pago"),
                AdminTableColumn("Marcas"),
                AdminTableColumn("Fecha"),
            ),
            rows = items.map { item ->
                AdminTableRow(
                    key = item.id,
                    cells = listOf(
                        handleOrDash(item.inviterHandle),
                        handleOrDash(item.inviteeHandle),
                        ReferralStates.label(item.state),
                        listOfNotNull(item.inviterPayment, item.inviteePayment).joinToString(" / ").ifEmpty { "—" },
                        item.flags.joinToString(", ") { ReferralStates.flagLabel(it) }.ifEmpty { "—" },
                        formatWhen(item.createdAt),
                    ),
                    supporting = listOfNotNull(
                        item.lastError?.let { "Error: $it" },
                        item.reason?.let { "Motivo: $it" },
                        item.heldUntil?.takeIf { item.state == "diferida" }?.let { "Retenida hasta ${formatWhen(it)}" },
                    ).joinToString(" · ").ifEmpty { null },
                )
            },
            rowActions = { row ->
                val item = items.firstOrNull { it.id == row.key }
                if (item != null && ReferralStates.canRevoke(item.state)) {
                    DangerOutlineButton(text = "Revocar", size = ButtonSize.COMPACT, onClick = { onIntent(ReferralIntent.RequestRevoke(item.id)) })
                }
            },
        )
        if (state.nextCursor != null) {
            SecondaryButton(
                text = "Cargar más",
                size = ButtonSize.COMPACT,
                loading = state.loadingMore,
                onClick = { onIntent(ReferralIntent.LoadMore) },
            )
        }
    }
}

@Composable
private fun RevokeDialog(target: ReferralItem, state: ReferralState, onIntent: (ReferralIntent) -> Unit) {
    FormDialog(onDismiss = { onIntent(ReferralIntent.DismissRevoke) }) {
        FormSection(
            title = "Revocar invitación",
            description = "${handleOrDash(target.inviterHandle)} invitó a ${handleOrDash(target.inviteeHandle)}. " +
                "Se revierte solo el saldo que aún no se ha gastado; el resto queda anotado, no se oculta.",
        ) {
            LabeledField(
                label = "Motivo",
                value = state.revokeReason,
                onValueChange = { onIntent(ReferralIntent.UpdateReason(it)) },
                supportingText = state.revokeError ?: "De ${ReferralReason.MIN} a ${ReferralReason.MAX} caracteres. Queda en auditoría.",
                isError = state.revokeError != null,
                singleLine = false,
                minLines = 2,
            )
            ActionFeedback(state.action)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(text = "Cancelar", onClick = { onIntent(ReferralIntent.DismissRevoke) })
                DangerOutlineButton(
                    text = "Revocar",
                    loading = state.action is UiAsync.Loading,
                    onClick = { onIntent(ReferralIntent.ConfirmRevoke) },
                )
            }
        }
    }
}
