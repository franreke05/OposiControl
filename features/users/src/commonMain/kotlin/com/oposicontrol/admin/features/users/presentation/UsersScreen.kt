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
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Pantalla de Usuarios.
 *
 * La firma se mantiene —`UsersScreen(viewModel)`— porque la componen dos sitios:
 * el hub de Usuarios y el adaptador de sección legacy de `AppRoot`.
 */
@Composable
fun UsersScreen(viewModel: UsersViewModel) {
    val state by viewModel.state.collectAsState()
    UsersContent(
        state = state,
        onIntent = viewModel::dispatch,
        modifier = Modifier.fillMaxSize(),
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
) {
    CrudSplitLayout(
        modifier = modifier,
        listPane = {
            UsersListPane(
                state = state,
                onSearch = { onIntent(UsersIntent.UpdateSearch(it)) },
                onSelect = { onIntent(UsersIntent.SelectUser(it)) },
            )
        },
        detailPane = {
            if (state.selectedUser == null) {
                EmptyStateCard(
                    title = "Sin usuario seleccionado",
                    message = "Elige a alguien del catálogo para ver su ficha y editar su nombre y sus roles.",
                    icon = AppIcon.USERS,
                )
            } else {
                // La columna de detalle desplaza. `CrudSplitLayout` no lo hace por
                // sí mismo, y aquí hay editor + ficha por pestañas + el bloque de
                // capacidades: en una pantalla de 1080 de alto, todo lo que queda
                // por debajo del pliegue era sencillamente inalcanzable.
                //
                // A diferencia de Exámenes, aquí el scroll de página sí vale: no
                // hay barra de guardar anclada al fondo que se perdería al bajar.
                val detailScroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(detailScroll),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        UserEditorPane(state = state, onIntent = onIntent)
                        // Ficha de solo lectura: rendimiento, liga, economía y
                        // comunidad. Estado propio, así que su fallo no impide editar.
                        UserDetailPanel(state)
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
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
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

@Composable
private fun WarningDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
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
