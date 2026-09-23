package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsLayersEditor(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    val imagePicker = rememberNewsImagePicker { onIntent(NewsIntent.AddLayerWithImage(it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryButton("+ Título", { onIntent(NewsIntent.AddTitleLayer) }, size = ButtonSize.COMPACT)
        SecondaryButton("+ Texto", { onIntent(NewsIntent.AddLayer) }, size = ButtonSize.COMPACT)
        SecondaryButton("+ Imagen", imagePicker, size = ButtonSize.COMPACT)
    }
    Text("De delante hacia atrás. Selecciona una capa para editarla.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
    state.editorLayers.sortedByDescending { it.sortOrder }.forEach { layer ->
        SecondaryButton("${layer.sortOrder + 1}. ${layer.name.ifBlank { "Capa" }} · ${if (layer.visible) "Visible" else "Oculta"}${if (layer.locked) " · Bloqueada" else ""}", { onIntent(NewsIntent.SelectLayer(layer.id)) }, Modifier.fillMaxWidth())
    }
    val layer = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }
    if (layer == null) {
        Text("Selecciona una capa o añade contenido al lienzo.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    HorizontalDivider(color = GlassTheme.tokens.border)
    Text(layer.name, style = MaterialTheme.typography.titleMedium)
    if (!layer.locked) {
        NewsField("Nombre de capa", layer.name, { onIntent(NewsIntent.UpdateLayerName(layer.id, it)) })
        if (layer.kind.lowercase() in listOf("imagen", "image")) {
            Text(newsAssetName(layer.content), style = MaterialTheme.typography.bodySmall)
            val replace = rememberNewsImagePicker { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) }
            SecondaryButton("Cambiar imagen de capa", replace)
        } else NewsField("Texto de capa", layer.content, { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) }, lines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Posición X" to layer.x, "Posición Y" to layer.y, "Ancho" to layer.width, "Alto" to layer.height, "Opacidad %" to layer.opacity, "Rotación °" to layer.rotationDegrees).forEachIndexed { index, (label, value) ->
                Column(Modifier.width(112.dp)) {
                    NewsField(label, value.toString(), { text -> onIntent(when (index) {
                        0 -> NewsIntent.UpdateLayerX(layer.id, text)
                        1 -> NewsIntent.UpdateLayerY(layer.id, text)
                        2 -> NewsIntent.UpdateLayerWidth(layer.id, text)
                        3 -> NewsIntent.UpdateLayerHeight(layer.id, text)
                        4 -> NewsIntent.UpdateLayerOpacity(layer.id, text)
                        else -> NewsIntent.UpdateLayerRotation(layer.id, text.toIntOrNull() ?: layer.rotationDegrees)
                    }) })
                }
            }
        }
        if (layer.kind.lowercase() !in listOf("imagen", "image")) {
            NewsChoice("Tipografía", when (layer.fontFamily) { "serif" -> "Serif"; "mono" -> "Monoespaciada"; "cursive" -> "Cursiva"; else -> "Sans serif" }, listOf("sans" to "Sans serif", "serif" to "Serif", "mono" to "Monoespaciada", "cursive" to "Cursiva")) { onIntent(NewsIntent.UpdateLayerFontFamily(layer.id, it)) }
            NewsField("Color del texto (hexadecimal)", layer.textColor, { onIntent(NewsIntent.UpdateLayerTextColor(layer.id, it)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryButton(if (layer.locked) "Desbloquear" else "Bloquear", { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) }, size = ButtonSize.COMPACT)
        SecondaryButton(if (layer.visible) "Ocultar" else "Mostrar", { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) }, size = ButtonSize.COMPACT, enabled = !layer.locked)
        SecondaryButton("Adelante", { onIntent(NewsIntent.MoveLayerUp(layer.id)) }, size = ButtonSize.COMPACT, enabled = !layer.locked && layer.sortOrder < state.editorLayers.lastIndex)
        SecondaryButton("Atrás", { onIntent(NewsIntent.MoveLayerDown(layer.id)) }, size = ButtonSize.COMPACT, enabled = !layer.locked && layer.sortOrder > 0)
        GhostButton("Centrar", { onIntent(NewsIntent.CenterLayer(layer.id)) }, enabled = !layer.locked)
        GhostButton("Duplicar", { onIntent(NewsIntent.DuplicateLayer(layer.id)) })
        GhostButton("Quitar capa", { onIntent(NewsIntent.RemoveLayer(layer.id)) }, enabled = !layer.locked)
    }
}
