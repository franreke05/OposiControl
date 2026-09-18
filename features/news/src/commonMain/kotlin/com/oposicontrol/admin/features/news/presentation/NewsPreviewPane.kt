package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.contentOn
import androidx.compose.ui.graphics.Brush

@Composable
internal fun NewsPreviewPane(state: NewsState, onIntent: (NewsIntent) -> Unit, width: Int) {
    NewsPanel("Vista previa") {
        Text("Composición del lienzo", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        val canvasWidth = (width - 36).coerceAtLeast(200).coerceAtMost(640)
        NewsCanvas(state, onIntent, canvasWidth)
        Text("${state.editorCanvasWidth} × ${state.editorCanvasHeight} · ${state.editorLayers.count { it.visible }} capas visibles", style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.textSecondary)
        GhostButton("Ampliar composición", { onIntent(NewsIntent.ToggleCanvasFullscreen) })
        Text("Referencia de composición; no reproduce la pantalla final de OposiBot.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
    }
    if (state.canvasFullscreen) Dialog(onDismissRequest = { onIntent(NewsIntent.ToggleCanvasFullscreen) }) {
        var modalWidth by remember { mutableStateOf(600) }
        val density = LocalDensity.current
        NewsPanel("Composición ampliada", Modifier.width(860.dp).onSizeChanged { modalWidth = with(density) { it.width.toDp().value.toInt() } }) {
            NewsCanvas(state, onIntent, (modalWidth - 36).coerceAtLeast(100))
            SecondaryButton("Cerrar vista previa", { onIntent(NewsIntent.ToggleCanvasFullscreen) })
        }
    }
}

@Composable
private fun NewsCanvas(state: NewsState, onIntent: (NewsIntent) -> Unit, canvasWidth: Int) {
        val scale = canvasWidth.toFloat() / state.editorCanvasWidth.coerceAtLeast(1)
        val accent = when (state.editorBackgroundType.uppercase()) { "B", "MINT" -> GlassTheme.tokens.success; "C", "GOLD" -> GlassTheme.tokens.warning; else -> GlassTheme.tokens.info }
        val canvasBase = MaterialTheme.colorScheme.scrim
        Box(Modifier.width(canvasWidth.dp).height((state.editorCanvasHeight * scale).dp).clipToBounds().background(canvasBase).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), canvasBase)))) {
            if (state.editorBackgroundPath.isNotBlank()) NewsAssetImage(state.editorBackgroundPath, Modifier.matchParentSize())
            state.editorLayers.sortedBy { it.sortOrder }.filter { it.visible }.forEach { layer ->
                val selected = layer.id == state.selectedLayerId
                Box(Modifier.offset((layer.x * scale).dp, (layer.y * scale).dp).size((layer.width * scale).dp, (layer.height * scale).dp)
                    .graphicsLayer { alpha = layer.opacity.coerceIn(0, 100) / 100f; rotationZ = layer.rotationDegrees.toFloat() }
                    .then(if (selected) Modifier.border(1.dp, GlassTheme.tokens.accentInk) else Modifier)
                    .clickable { onIntent(NewsIntent.SelectLayer(layer.id)) }) {
                    if (layer.kind.lowercase() in listOf("imagen", "image")) NewsAssetImage(layer.content, Modifier.matchParentSize())
                    else Text(layer.content, color = newsLayerColor(layer.textColor, GlassTheme.tokens.contentOn(canvasBase)),
                        fontFamily = when (layer.fontFamily) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> FontFamily.SansSerif },
                        fontSize = ((if (layer.kind in listOf("titulo", "title")) 25 else 16) * scale).sp,
                        fontWeight = if (layer.kind in listOf("titulo", "title")) FontWeight.Bold else FontWeight.Normal,
                        overflow = TextOverflow.Clip)
                }
            }
            if (state.editorLayers.none { it.visible }) Text("Añade una capa para empezar", Modifier.padding(20.dp), color = GlassTheme.tokens.textSecondary)
        }
}

// El color almacenado pertenece al contenido editorial; el chrome usa exclusivamente tokens.
private fun newsLayerColor(value: String, fallback: Color): Color = runCatching {
    val hex = value.removePrefix("#")
    if (hex.length != 6) fallback else Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
}.getOrDefault(fallback)

@Composable
internal expect fun NewsAssetImage(path: String, modifier: Modifier)

@Composable
internal expect fun rememberNewsImagePicker(onPicked: (String) -> Unit): () -> Unit
