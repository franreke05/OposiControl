package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassChipGroup
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.UserDetail

/**
 * Las tarjetas EDITABLES de la ficha: identidad, roles, guardado y acciones.
 *
 * Sólo contienen lo que el guardado escribe de verdad —nombre y roles— y lo dicen
 * antes de guardar, no después. El editor anterior tenía además un campo de email
 * editable y obligatorio, y un interruptor de «Usuario activo»: el backend
 * descartaba los dos y devolvía éxito igualmente.
 *
 * Esta vez cambia la presentación, no el contrato: mismas intenciones, mismos
 * textos de alcance y los mismos avisos.
 */

/**
 * «Información básica»: el nombre (editable) y los datos de la cuenta (de lectura).
 *
 * [detail] sólo aporta el onboarding; si la ficha no ha cargado o falló, la tarjeta
 * sigue siendo usable —el nombre se puede editar— y esa fila lo dice.
 */
@Composable
internal fun UserBasicInfoCard(
    state: UsersState,
    detail: UiAsyncDetail,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val user = state.selectedUser
    val kind = user?.let { userAccountKind(it) }
    val population = user?.let { userPopulation(it, state.adminRoleIds) }
    FormSection(
        title = "Información básica",
        description = USER_SAVE_SCOPE_NOTE,
        modifier = modifier,
        icon = AppIcon.USERS,
        iconRole = SemanticRole.INFO,
    ) {
        Column {
            UserInfoRowSlot(label = "Nombre") {
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            UserRowDivider()
            // De sólo lectura y con su nombre real. Este campo es
            // `perfiles_usuario.username`, no un correo, y el guardado lo descarta:
            // dejarlo editable hacía creer que se estaba corrigiendo el email de alguien.
            UserInfoRow(USER_ACCOUNT_FIELD_LABEL, user?.let { userAccountName(it) })
            UserRowDivider()
            UserInfoRowSlot(label = "Tipo de cuenta") {
                if (kind == null) {
                    Text(text = USER_NO_DATA, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                } else {
                    UserPill(
                        text = userAccountKindLabel(kind),
                        tone = if (kind == UserAccountKind.REGISTRADO) UserPillTone.POSITIVE else UserPillTone.NEUTRAL,
                    )
                }
            }
            UserRowDivider()
            UserInfoRowSlot(label = "Estado de cuenta") {
                if (user == null) {
                    Text(text = USER_NO_DATA, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                } else {
                    UserPill(
                        text = userAccountStatusLabel(user.accountStatus),
                        tone = userAccountStatusTone(user.accountStatus),
                    )
                }
            }
            UserRowDivider()
            UserInfoRowSlot(label = "Población") {
                if (population == null) {
                    Text(text = USER_NO_DATA, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                } else {
                    UserPill(
                        text = userPopulationLabel(population),
                        tone = if (population == UserPopulation.ADMINISTRACION) UserPillTone.ADMIN else UserPillTone.INFO,
                    )
                }
            }
            UserRowDivider()
            UserInfoRow("Alta", user?.let { userSignupDate(it) })
            UserRowDivider()
            UserInfoRowSlot(label = "Onboarding") {
                when (detail) {
                    is UiAsyncDetail.Loaded -> UserPill(
                        text = if (detail.detail.profile.onboardingCompleted) "Completo" else "Pendiente",
                        tone = if (detail.detail.profile.onboardingCompleted) UserPillTone.POSITIVE else UserPillTone.ATTENTION,
                    )
                    UiAsyncDetail.Loading -> UserLoadingNote()
                    UiAsyncDetail.Failed -> UserUnavailable()
                    UiAsyncDetail.Missing -> Text(text = NO_DATA_TEXT, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                }
            }
            // Las dos notas que hacen honesto el bloque: no hay correo que enseñar y el
            // estado de cuenta es de sólo lectura. Van debajo, en pequeño, no como filas.
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = USER_EMAIL_UNAVAILABLE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
                Text(
                    text = USER_ACCOUNT_STATUS_READ_ONLY_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/** «Roles del usuario»: el catálogo de roles como interruptores, con el mismo alcance que antes. */
@Composable
internal fun UserRolesCard(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Roles del usuario",
        description = USER_POPULATION_NOTE,
        modifier = modifier,
        icon = AppIcon.ROLES,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
            // El aviso de acceso administrativo NO se repite aquí: vive en la barra de
            // guardado, que es donde se confirma el cambio. Un aviso que se repite se
            // aprende a ignorar.
        }
    }
}

/**
 * La barra de guardado, en una línea.
 *
 * Dice tres cosas distintas: si hay cambios, qué se va a escribir y cómo fue el
 * último intento. Un guardado rechazado nunca se pinta como completado.
 */
@Composable
internal fun UserSaveCard(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SemanticIconContainer(
                    icon = if (state.isDirty) AppIcon.EDIT else AppIcon.CHECK,
                    role = if (state.isDirty) SemanticRole.WARNING else SemanticRole.SUCCESS,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (state.isDirty) "Cambios sin guardar" else "Sin cambios pendientes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                    )
                    Text(
                        text = USER_SAVE_SCOPE_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        text = "Descartar",
                        onClick = { onIntent(UsersIntent.DiscardChanges) },
                        enabled = state.isDirty,
                        size = ButtonSize.COMPACT,
                    )
                    PrimaryButton(
                        text = "Guardar",
                        onClick = { onIntent(UsersIntent.Save) },
                        enabled = state.canSave,
                        size = ButtonSize.COMPACT,
                    )
                }
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

/** «Acciones»: las dos operaciones que existen sobre la cuenta, con su aviso de alcance. */
@Composable
internal fun UserActionsCard(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val user = state.selectedUser
    FormSection(
        title = "Acciones",
        description = "Operaciones habituales sobre la cuenta seleccionada.",
        modifier = modifier,
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
                    icon = { AppIconImage(AppIcon.TICKETS, size = 14.dp) },
                )
                SecondaryButton(
                    text = "Registrar advertencia",
                    onClick = { onIntent(UsersIntent.OpenWarningDialog) },
                    size = ButtonSize.COMPACT,
                    enabled = user != null,
                    icon = { AppIconImage(AppIcon.WARNING, size = 14.dp) },
                )
            }
            // La advertencia no llega al usuario: sólo deja rastro en auditoría. Se dice
            // junto al botón, no en una nota al pie.
            userCapability("Advertir")?.let { capability ->
                Text(
                    text = capability.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.warningInk,
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

/** Cómo llega la ficha a una tarjeta que sólo necesita saber si hay, si carga o si falló. */
internal sealed interface UiAsyncDetail {
    data class Loaded(val detail: UserDetail) : UiAsyncDetail
    data object Loading : UiAsyncDetail
    data object Failed : UiAsyncDetail
    data object Missing : UiAsyncDetail
}

/** La ficha de [UsersState] vista como las cuatro situaciones que le importan a una tarjeta. */
internal fun UiAsync<UserDetail>.asDetail(): UiAsyncDetail = when (this) {
    is UiAsync.Success -> UiAsyncDetail.Loaded(value)
    is UiAsync.Error -> UiAsyncDetail.Failed
    UiAsync.Empty -> UiAsyncDetail.Missing
    else -> UiAsyncDetail.Loading
}
