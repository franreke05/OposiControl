package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SummaryCard

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
) {
    val state by viewModel.state.collectAsState()

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("Entorno", state.environmentLabel.ifBlank { "Sin definir" }, "Configuracion efectiva del panel")
                SummaryCard("Datos", "Reales", "Sin fallback de produccion")
                SummaryCard("Logs verbosos", state.enableVerboseLogs.toString(), "Observabilidad del cliente")
            }
        },
        detailPane = {
            FormSection(
                title = "Preferencias del administrador",
                description = "Ajustes personales y flags operativos del panel.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = { viewModel.dispatch(SettingsIntent.UpdateDisplayName(it)) },
                        label = { Text("Nombre visible") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = { viewModel.dispatch(SettingsIntent.UpdateEmail(it)) },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Recibir release notes", state.receiveReleaseNotes) {
                        viewModel.dispatch(SettingsIntent.ToggleReleaseNotes(it))
                    }
                    SwitchRow("Logs verbosos", state.enableVerboseLogs) {
                        viewModel.dispatch(SettingsIntent.ToggleVerboseLogs(it))
                    }
                    Button(onClick = { viewModel.dispatch(SettingsIntent.Save) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Guardar ajustes")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando configuracion...")
                        is UiAsync.Success -> Text("Configuracion sincronizada.")
                        else -> Unit
                    }
                }
            }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label)
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}
