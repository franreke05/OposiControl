package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassChipGroup
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Editor del usuario seleccionado.
 *
 * Sólo contiene lo que el guardado escribe de verdad —nombre y roles— y lo dice
 * antes de guardar, no después. El editor anterior tenía además un campo de email
 * editable y obligatorio, y un interruptor de «Usuario activo»: el backend
 * descartaba los dos y devolvía éxito igualmente.
 */
@Composable
internal fun UserEditorPane(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val user = state.selectedUser

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FormSection(
            title = "Identidad",
            description = USER_SAVE_SCOPE_NOTE,
            icon = AppIcon.USERS,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                EditorLabel("Nombre")
                GlassTextField(
                    value = state.editorName,
                    onValueChange = { onIntent(UsersIntent.UpdateName(it)) },
                    placeholder = "Nombre que se muestra en la app",
                    modifier = Modifier.fillMaxWidth(),
                )
                state.saveBlockers.forEach { blocker ->
                    Text(
                        text = blocker,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.danger,
                    )
                }

                // De sólo lectura y con su nombre real. Este campo es
                // `perfiles_usuario.username`, no un correo, y el guardado lo
                // descarta: dejarlo editable hacía creer que se estaba corrigiendo
                // el email de alguien.
                EditorLabel(USER_ACCOUNT_FIELD_LABEL)
                Text(
                    text = user?.let { userAccountName(it) } ?: USER_NO_DATA,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = USER_EMAIL_UNAVAILABLE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )

                EditorLabel("Tipo de cuenta")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val kind = user?.let { userAccountKind(it) }
                    StatusChip(
                        status = kind?.let { userAccountKindLabel(it) } ?: USER_NO_DATA,
                        tone = when (kind) {
                            UserAccountKind.REGISTRADO -> StatusTone.POSITIVE
                            UserAccountKind.INVITADO -> StatusTone.NEUTRAL
                            null -> StatusTone.UNKNOWN
                        },
                    )
                }
                Text(
                    text = USER_NO_BLOCK_STATE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }

        FormSection(
            title = "Roles y acceso",
            description = USER_POPULATION_NOTE,
            icon = AppIcon.ROLES,
            iconRole = SemanticRole.DANGER,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                if (state.availableRoles.isEmpty()) {
                    Text(
                        text = "No se ha podido cargar el catálogo de roles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                } else {
                    GlassChipGroup(
                        options = state.availableRoles.map { it.id to it.name },
                        selected = state.editorRoleIds,
                        onToggle = { onIntent(UsersIntent.ToggleRole(it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // El aviso de acceso administrativo NO se repite aquí: vive en la
                // barra de guardado, que es donde se confirma el cambio. Decirlo
                // en los dos sitios con el mismo texto es la duplicación que ya
                // hubo que corregir en la pestaña Publicación de Exámenes — y un
                // aviso que se repite se aprende a ignorar.
            }
        }

        UserSaveBar(state = state, onIntent = onIntent)

        FormSection(
            title = "Acciones",
            description = "Operaciones sobre la cuenta seleccionada.",
            icon = AppIcon.TICKETS,
            iconRole = SemanticRole.COMMERCE,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SecondaryButton(
                        text = "Conceder OposiTickets",
                        onClick = { onIntent(UsersIntent.OpenTicketsDialog) },
                        size = ButtonSize.COMPACT,
                        enabled = user != null,
                    )
                    SecondaryButton(
                        text = "Registrar advertencia",
                        onClick = { onIntent(UsersIntent.OpenWarningDialog) },
                        size = ButtonSize.COMPACT,
                        enabled = user != null,
                    )
                }
                // La advertencia no llega al usuario: sólo deja rastro en
                // auditoría. Se dice junto al botón, no en una nota al pie.
                userCapability("Advertir")?.let { capability ->
                    Text(
                        text = capability.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.warningInk,
                    )
                }
            }
        }
        // `UserCapabilitiesNotice` NO va aquí: es material de referencia y, puesto
        // delante de la ficha, empujaba los datos reales del usuario setecientos
        // píxeles hacia abajo. Lo compone `UsersContent` al final de la columna.
    }
}

/**
 * Barra de guardado.
 *
 * Dice tres cosas distintas: si hay cambios, qué se va a escribir y cómo fue el
 * último intento. Un guardado rechazado nunca se pinta como completado.
 */
@Composable
private fun UserSaveBar(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = if (state.isDirty) "Cambios sin guardar" else "Sin cambios pendientes",
        description = USER_SAVE_SCOPE_NOTE,
        icon = if (state.isDirty) AppIcon.EDIT else AppIcon.CHECK,
        iconRole = if (state.isDirty) SemanticRole.WARNING else SemanticRole.SUCCESS,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = "Guardar",
                    onClick = { onIntent(UsersIntent.Save) },
                    enabled = state.canSave,
                    size = ButtonSize.COMPACT,
                )
                SecondaryButton(
                    text = "Descartar",
                    onClick = { onIntent(UsersIntent.DiscardChanges) },
                    enabled = state.isDirty,
                    size = ButtonSize.COMPACT,
                )
            }
            FormFeedback(
                state = state.actionState,
                successText = "Cambios guardados.",
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.touchesAdminAccess && state.isDirty) {
                Text(
                    text = USER_ADMIN_ROLE_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.dangerInk,
                )
            }
        }
    }
}

/**
 * Lo que esta pantalla NO puede hacer.
 *
 * Va a la vista y no a un documento porque la pregunta se hace delante de la
 * pantalla: quien busca «bloquear» o «eliminar» necesita saber ahí mismo que no
 * existe, en vez de deducirlo de que no encuentra el botón.
 */
@Composable
internal fun UserCapabilitiesNotice(modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Qué no se puede hacer desde aquí",
        description = "Operaciones que este panel no realiza, y por qué.",
        modifier = modifier,
        icon = AppIcon.INFO,
        iconRole = SemanticRole.NEUTRAL,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            userUnavailableCapabilities().forEach { capability ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = capability.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(190.dp),
                    )
                    Text(
                        text = capability.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
    )
}
