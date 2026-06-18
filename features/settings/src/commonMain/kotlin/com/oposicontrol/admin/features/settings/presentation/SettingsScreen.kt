package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.SummaryCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
) {
    val state by viewModel.state.collectAsState()

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
                        onValueChange = { viewModel.dispatch(SettingsIntent.UpdateDisplayName(it)) },
                        placeholder = "Tu nombre en el panel",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Email")
                    GlassTextField(
                        value = state.email,
                        onValueChange = { viewModel.dispatch(SettingsIntent.UpdateEmail(it)) },
                        placeholder = "persona@dominio.com",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    GlassToggleRow(
                        title = "Recibir release notes",
                        description = "Avisos de nuevas versiones y cambios del panel.",
                        checked = state.receiveReleaseNotes,
                        onCheckedChange = { viewModel.dispatch(SettingsIntent.ToggleReleaseNotes(it)) },
                    )
                    GlassToggleRow(
                        title = "Logs verbosos",
                        description = "Más detalle de red y diagnóstico en consola.",
                        checked = state.enableVerboseLogs,
                        onCheckedChange = { viewModel.dispatch(SettingsIntent.ToggleVerboseLogs(it)) },
                    )

                    GlassActionButton(
                        text = "Guardar ajustes",
                        onClick = { viewModel.dispatch(SettingsIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormFeedback(state.actionState, successText = "Configuración sincronizada.")
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
