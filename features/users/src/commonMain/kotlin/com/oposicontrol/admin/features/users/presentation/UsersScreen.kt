package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.components.motionOverlayEntrance
import com.oposicontrol.admin.core.ui.components.overlayDialogProperties
import com.oposicontrol.admin.core.ui.components.DangerButton

/**
 * Pantalla de Usuarios.
 *
 * La firma se mantiene —`UsersScreen(viewModel)`— porque la componen dos sitios:
 * el hub de Usuarios y el adaptador de sección legacy de `AppRoot`.
 */
@Composable
fun UsersScreen(
    viewModel: UsersViewModel,
    /** Abre la cartera del usuario en OposiTickets. `null` = no se ofrece el enlace. */
    onOpenTickets: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    UsersContent(
        state = state,
        onIntent = viewModel::dispatch,
        modifier = Modifier.fillMaxSize(),
        onOpenTickets = onOpenTickets,
    )
}

/**
 * El cuerpo de la pantalla, sin ViewModel.
 *
 * Separado para que el banco de pruebas visual pueda renderizar la pantalla real
 * contra estados fabricados sin levantar Koin ni Ktor.
 */
@Composable
internal fun UsersContent(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTickets: ((String) -> Unit)? = null,
) {
    UsersSplit(
        modifier = modifier,
        listPane = {
            UsersListPane(
                state = state,
                onSearch = { onIntent(UsersIntent.UpdateSearch(it)) },
                onSelect = { onIntent(UsersIntent.SelectUser(it)) },
                onRefresh = { onIntent(UsersIntent.Refresh) },
            )
        },
        detailPane = { embedded ->
            if (state.pendingOpenUserId != null) {
                // Se llegó aquí desde otra pantalla pidiendo una ficha y la lista todavía está en camino.
                EmptyStateCard(
                    title = "Abriendo la ficha…",
                    message = "Se está cargando la lista de usuarios; la ficha se abre en cuanto llegue.",
                    icon = AppIcon.USERS,
                )
            } else if (state.openUserMissing) {
                EmptyStateCard(
                    title = "Ese usuario no está en la lista",
                    message = "No se encontró entre los usuarios cargados. Puede que la cuenta ya no exista o que la lista no esté al día.",
                    icon = AppIcon.USERS,
                )
            } else if (state.selectedUser == null) {
                EmptyStateCard(
                    title = "Sin usuario seleccionado",
                    message = "Elige a alguien del catálogo para ver su ficha y editar su nombre y sus roles.",
                    icon = AppIcon.USERS,
                )
            } else if (embedded) {
                // Apilado: la pantalla entera desplaza (ver `UsersSplit`); la ficha sólo
                // se compone a su altura natural.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    UserDetailWorkspace(state = state, onIntent = onIntent, onOpenTickets = onOpenTickets)
                    UserCapabilitiesNotice()
                }
            } else {
                // La columna de detalle desplaza: hay cabecera, métricas, pestañas, editor y
                // el bloque de capacidades, y en una pantalla de 1080 de alto todo lo que
                // queda por debajo del pliegue sería inalcanzable. A diferencia de Exámenes,
                // aquí el scroll de página sí vale: no hay barra de guardar anclada al fondo
                // que se perdería al bajar.
                val detailScroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(detailScroll).padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        // Cabecera de la persona, métricas, pestañas y el contenido de la
                        // pestaña abierta. La ficha tiene estado propio: que falle una
                        // sección no impide editar el nombre ni los roles.
                        UserDetailWorkspace(state = state, onIntent = onIntent, onOpenTickets = onOpenTickets)
                        // Al final: lo que la pantalla no puede hacer es referencia,
                        // no debe mandar sobre los datos de la persona.
                        UserCapabilitiesNotice()
                    }
                    DesktopVerticalScrollbar(
                        detailScroll,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        },
    )

    // El alta no se hace desde el panel. Antes este botón abría un editor vacío
    // que al guardar moría con un error del cliente.
    if (state.createNoticeVisible) {
        ConfirmDialog(
            title = "El alta no se hace desde el panel",
            message = userCapability("Crear usuario")?.note
                ?: "Las altas se gestionan desde el sistema de autenticación.",
            confirmLabel = "Entendido",
            dismissLabel = "Cerrar",
            destructive = false,
            onConfirm = { onIntent(UsersIntent.DismissCreateNotice) },
            onDismiss = { onIntent(UsersIntent.DismissCreateNotice) },
        )
    }

    // §32: saltar de usuario con cambios encima pregunta antes de perderlos.
    if (state.pendingSelectionUserId != null) {
        ConfirmDialog(
            title = "Hay cambios sin guardar",
            message = "Si cambias de usuario se perderán los cambios que no has guardado. ¿Continuar?",
            confirmLabel = "Descartar y cambiar",
            dismissLabel = "Seguir editando",
            onConfirm = { onIntent(UsersIntent.ConfirmDiscardChanges) },
            onDismiss = { onIntent(UsersIntent.CancelPendingSelection) },
        )
    }

    if (state.ticketsDialogOpen) {
        TicketsDialog(
            input = state.ticketsInput,
            onInputChange = { onIntent(UsersIntent.UpdateTicketsInput(it)) },
            onConfirm = { onIntent(UsersIntent.ConfirmTickets) },
            onDismiss = { onIntent(UsersIntent.DismissTicketsDialog) },
        )
    }

    if (state.warningDialogOpen) {
        WarningDialog(
            input = state.warningInput,
            onInputChange = { onIntent(UsersIntent.UpdateWarningInput(it)) },
            onConfirm = { onIntent(UsersIntent.ConfirmWarning) },
            onDismiss = { onIntent(UsersIntent.DismissWarningDialog) },
        )
    }

    if (state.accountStatusDialogOpen) {
        AccountStatusDialog(
            userName = state.selectedUser?.let(::userAccountName).orEmpty(),
            blocks = state.accountStatusBlocks,
            reason = state.accountStatusReason,
            onReasonChange = { onIntent(UsersIntent.UpdateAccountStatusReason(it)) },
            onConfirm = { onIntent(UsersIntent.ConfirmAccountStatus) },
            onDismiss = { onIntent(UsersIntent.DismissAccountStatusDialog) },
        )
    }
}

@Composable
private fun TicketsDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val amount = input.toIntOrNull()
    Dialog(onDismissRequest = onDismiss, properties = overlayDialogProperties()) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 400.dp).motionOverlayEntrance(),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "Conceder OposiTickets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "Número positivo para dar tickets, negativo para quitar. " +
                        "El movimiento queda registrado en el histórico del usuario.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                GlassTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "Ej. 10 o -5",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (amount != null && amount < -100) {
                    Text(
                        text = "Vas a retirar más de 100 OposiTickets. Confirma que es correcto.",
                        color = tokens.dangerInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    SecondaryButton(text = "Cancelar", onClick = onDismiss, size = ButtonSize.COMPACT)
                    PrimaryButton(
                        text = "Conceder",
                        onClick = onConfirm,
                        enabled = amount != null && amount != 0,
                        size = ButtonSize.COMPACT,
                    )
                }
            }
        }
    }
}

/** Dar de baja o reactivar: dice a quién, qué va a pasar, y no deja confirmar sin motivo. */
@Composable
private fun AccountStatusDialog(
    userName: String,
    blocks: Boolean,
    reason: String,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss, properties = overlayDialogProperties()) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 460.dp).motionOverlayEntrance(),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = if (blocks) "Dar de baja la cuenta" else "Reactivar la cuenta",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = if (blocks) {
                        "$userName dejará de poder entrar en OposiBot y en OposiControl. No se borra nada: sus datos se " +
                            "conservan y la cuenta se puede reactivar. Queda registrado en auditoría con tu motivo."
                    } else {
                        "$userName podrá volver a entrar. Queda registrado en auditoría con tu motivo."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                GlassTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    placeholder = if (blocks) "Motivo de la baja" else "Motivo de la reactivación",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                )
                Text(
                    text = "${reason.length} / $ACCOUNT_STATUS_REASON_MAX",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    SecondaryButton(text = "Cancelar", onClick = onDismiss, size = ButtonSize.COMPACT)
                    if (blocks) {
                        DangerButton(text = "Dar de baja", onClick = onConfirm, enabled = reason.isNotBlank(), size = ButtonSize.COMPACT)
                    } else {
                        PrimaryButton(text = "Reactivar", onClick = onConfirm, enabled = reason.isNotBlank(), size = ButtonSize.COMPACT)
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss, properties = overlayDialogProperties()) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp).motionOverlayEntrance(),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "Registrar advertencia",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                // El texto anterior decía «Quedará registrado como acción de
                // moderación», que se leía como que al usuario le llegaba algo. No
                // le llega: el backend sólo escribe una fila de auditoría.
                Text(
                    text = userCapability("Advertir")?.note.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                GlassTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "Motivo de la advertencia",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    SecondaryButton(text = "Cancelar", onClick = onDismiss, size = ButtonSize.COMPACT)
                    PrimaryButton(
                        text = "Registrar",
                        onClick = onConfirm,
                        enabled = input.isNotBlank(),
                        size = ButtonSize.COMPACT,
                    )
                }
            }
        }
    }
}
