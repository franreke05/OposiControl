package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.SummaryCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_filter
import oposicontrol.core.ui.generated.resources.ic_settings
import oposicontrol.core.ui.generated.resources.ic_support
import oposicontrol.core.ui.generated.resources.ic_users
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSystemStatusClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < 720.dp) {
            SettingsMobileContent(
                state = state,
                onIntent = viewModel::dispatch,
                onSystemStatusClick = onSystemStatusClick,
            )
        } else {
            SettingsDesktopContent(state = state, onIntent = viewModel::dispatch)
        }
    }
}

@Composable
private fun SettingsMobileContent(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onSystemStatusClick: () -> Unit,
) {
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (val profile = state.profile) {
            UiAsync.Loading -> LoadingStateCard(rows = 2)
            is UiAsync.Success -> SettingsSectionCard(title = "CUENTA") {
                SettingsInfoRow(
                    icon = Res.drawable.ic_users,
                    title = "Perfil administrador",
                    subtitle = profile.value.displayName,
                    detail = profile.value.roleLabel,
                )
            }
            UiAsync.Empty, UiAsync.Idle, is UiAsync.Error -> Unit
        }

        SettingsSectionCard(title = "APLICACIÓN") {
            SettingsPreferenceRow(
                title = "Avisos de versiones",
                description = "Recibir novedades del panel cuando estén disponibles.",
                checked = state.receiveReleaseNotes,
                onCheckedChange = { onIntent(SettingsIntent.ToggleReleaseNotes(it)) },
            )
            Spacer(modifier = Modifier.size(12.dp))
            GlassActionButton(
                text = "Guardar cambios",
                modifier = Modifier.fillMaxWidth(),
                enabled = state.actionState !is UiAsync.Loading,
                onClick = { onIntent(SettingsIntent.Save) },
            )
            SettingsFeedback(state.actionState)
        }

        SettingsSectionCard(title = "SISTEMA") {
            SettingsInfoRow(
                icon = Res.drawable.ic_filter,
                title = "Estado del sistema",
                subtitle = "Réplicas, alertas y consumo de Supabase",
                onClick = onSystemStatusClick,
                contentDescription = "Estado del sistema. Abrir estado del sistema.",
            )
        }

        SettingsSectionCard(title = "SESIÓN") {
            SettingsInfoRow(
                icon = Res.drawable.ic_support,
                title = "Cerrar sesión",
                subtitle = "Salir de este dispositivo",
                onClick = { showLogoutConfirmation = true },
                contentDescription = "Cerrar sesión.",
                destructive = true,
            )
            if (state.logoutState is UiAsync.Error) {
                SettingsFeedback(state.logoutState)
            }
        }
    }

    if (showLogoutConfirmation) {
        ConfirmDialog(
            title = "Cerrar sesión",
            message = "Se cerrará la sesión en este dispositivo.",
            confirmLabel = "Cerrar sesión",
            onConfirm = {
                showLogoutConfirmation = false
                onIntent(SettingsIntent.Logout)
            },
            onDismiss = { showLogoutConfirmation = false },
            destructive = false,
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 7.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: DrawableResource,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String = title,
    destructive: Boolean = false,
) {
    val textColor = if (destructive) MaterialTheme.colorScheme.error else GlassTheme.tokens.textPrimary
    val rowModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 64.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
        .padding(horizontal = 10.dp, vertical = 8.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else GlassTheme.tokens.accent,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.82f) else GlassTheme.tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = GlassTheme.tokens.textSecondary,
            )
        }
        if (onClick != null) {
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = if (destructive) MaterialTheme.colorScheme.error else GlassTheme.tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun SettingsPreferenceRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clickable { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. ${if (checked) "Activado" else "Desactivado"}."
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_settings),
            contentDescription = null,
            tint = GlassTheme.tokens.accent,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = GlassTheme.tokens.textPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GlassTheme.tokens.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = GlassTheme.tokens.textSecondary.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun SettingsFeedback(state: UiAsync<Unit>) {
    when (state) {
        UiAsync.Loading -> Text("Guardando…", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        is UiAsync.Error -> Text(state.error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is UiAsync.Success -> Text("Cambios guardados en este dispositivo.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.success)
        UiAsync.Empty, UiAsync.Idle -> Unit
    }
}

@Composable
private fun SettingsDesktopContent(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("Entorno", state.environmentLabel.ifBlank { "Sin definir" }, "Configuración efectiva del panel")
                SummaryCard("Datos", "Reales", "Sin fallback de producción")
                SummaryCard("Logs verbosos", if (state.enableVerboseLogs) "Activados" else "Desactivados", "Observabilidad del cliente")
            }
        },
        detailPane = {
            FormSection(
                title = "Preferencias del administrador",
                description = "Ajustes personales y flags operativos del panel.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldLabel("Nombre visible")
                    GlassTextField(
                        value = state.displayName,
                        onValueChange = { onIntent(SettingsIntent.UpdateDisplayName(it)) },
                        placeholder = "Tu nombre en el panel",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Email")
                    GlassTextField(
                        value = state.email,
                        onValueChange = { onIntent(SettingsIntent.UpdateEmail(it)) },
                        placeholder = "persona@dominio.com",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    com.oposicontrol.admin.core.ui.components.GlassToggleRow(
                        title = "Recibir release notes",
                        description = "Avisos de nuevas versiones y cambios del panel.",
                        checked = state.receiveReleaseNotes,
                        onCheckedChange = { onIntent(SettingsIntent.ToggleReleaseNotes(it)) },
                    )
                    com.oposicontrol.admin.core.ui.components.GlassToggleRow(
                        title = "Logs verbosos",
                        description = "Más detalle de red y diagnóstico en consola.",
                        checked = state.enableVerboseLogs,
                        onCheckedChange = { onIntent(SettingsIntent.ToggleVerboseLogs(it)) },
                    )
                    GlassActionButton(
                        text = "Guardar ajustes",
                        onClick = { onIntent(SettingsIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormFeedback(state.actionState, successText = "Configuración guardada en este dispositivo.")
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
