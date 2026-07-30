package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.AdminTheme

@Preview
@Composable
private fun CoreUiComponentsPreview() {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("inmersivo") }
    AdminTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UiSpacing.blockLarge),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.block),
        ) {
            GlassField(
                label = "Nombre del examen",
                value = name,
                onValueChange = { name = it },
                placeholder = "Introduce un nombre",
                supportingText = "Visible para el equipo administrador.",
                required = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact),
            ) {
                GlassChoiceChip(
                    label = "Inmersivo",
                    selected = selected == "inmersivo",
                    onClick = { selected = "inmersivo" },
                )
                GlassChoiceChip(
                    label = "Ráfaga",
                    selected = selected == "rafaga",
                    onClick = { selected = "rafaga" },
                )
            }
            GlassButton(
                text = "Guardar examen",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
            GlassButton(
                text = "Eliminar borrador",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                variant = GlassButtonVariant.Destructive,
            )
            GlassFeedbackPane(
                kind = GlassFeedbackKind.Success,
                title = "Cambios guardados",
                message = "El examen está listo para continuar.",
            )
        }
    }
}
