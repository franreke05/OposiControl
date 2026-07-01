package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.features.news.presentation.NewsIntent
import com.oposicontrol.admin.features.news.presentation.NewsLayerDraft
import com.oposicontrol.admin.features.news.presentation.NewsState
import kotlin.math.roundToInt

private const val LogicalCanvasWidth = 800
private const val LogicalCanvasHeight = 500
private const val PreviewCanvasWidth = 320
private const val PreviewCanvasHeight = 200
private const val FullscreenCanvasWidth = 480
private const val FullscreenCanvasHeight = 300
private const val FullscreenPanelWidth = 260
private const val MinLayerWidth = 24
private const val MinLayerHeight = 24

@Composable
internal fun MobileLayeredNewsEditor(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val imagePicker = rememberContentFilePickerLauncher(mimeType = "image/*") { picked ->
        onIntent(NewsIntent.AddLayerWithImage(picked.uri))
    }
    val backgroundPicker = rememberContentFilePickerLauncher(mimeType = "image/*") { picked ->
        onIntent(NewsIntent.SelectBackgroundFile(picked.uri, picked.name, picked.mimeType))
    }

    if (isFullscreen) {
        LockScreenOrientation(ScreenOrientation.LANDSCAPE)
        HideSystemBars(true)
        MobileLayeredNewsEditorFullscreen(
            state = state,
            onIntent = onIntent,
            onPickImage = { imagePicker.open() },
            onPickBackground = { backgroundPicker.open() },
            onClose = onToggleFullscreen,
            onRequestDelete = { confirmDelete = true },
        )
    } else {
        LockScreenOrientation(ScreenOrientation.UNSET)
        HideSystemBars(false)
        MobileLayeredNewsEditorPreview(
            state = state,
            onIntent = onIntent,
            onPickImage = { imagePicker.open() },
            onPickBackground = { backgroundPicker.open() },
            onOpenFullscreen = onToggleFullscreen,
            onRequestDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar noticia",
            message = "Se eliminara la noticia seleccionada. Esta accion no se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                onIntent(NewsIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun MobileLayeredNewsEditorPreview(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    onPickImage: () -> Unit,
    onPickBackground: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val selectedLayer = state.selectedLayer()
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NewsEditorHeader(
                title = state.editorTitle.ifBlank { "Noticia sin titulo" },
                selectedLabel = selectedLayer?.name ?: "Lienzo",
                actionLabel = "Pantalla",
                onAction = onOpenFullscreen,
            )
            NewsLayerCanvasViewport(
                state = state,
                viewportWidth = PreviewCanvasWidth,
                viewportHeight = PreviewCanvasHeight,
                onIntent = onIntent,
            )
            NewsEditorQuickActions(
                onAddTitle = { onIntent(NewsIntent.AddTitleLayer) },
                onAddText = { onIntent(NewsIntent.AddLayer) },
                onAddImage = onPickImage,
                onPickBackground = onPickBackground,
                onValidate = { onIntent(NewsIntent.ValidateDraft) },
                onSave = { onIntent(NewsIntent.SaveDraft) },
                onPublish = { onIntent(NewsIntent.Publish) },
                onSchedule = { onIntent(NewsIntent.Schedule) },
                onDelete = onRequestDelete,
                canDelete = state.selectedId != null,
                hasTitleLayer = state.editorLayers.any { it.kind.isTitleKind() },
            )
            if (state.editorBackgroundPath.isBlank()) NewsTemplateSelector(state = state, onIntent = onIntent)
            NewsLayerStrip(state = state, onIntent = onIntent)
            NewsEditorInspector(
                state = state,
                selectedLayer = selectedLayer,
                onIntent = onIntent,
                compact = true,
            )
            NewsActionState(state)
        }
    }
}

@Composable
private fun MobileLayeredNewsEditorFullscreen(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    onPickImage: () -> Unit,
    onPickBackground: () -> Unit,
    onClose: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1018)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(764.dp)
                .height(324.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NewsLayerCanvasViewport(
                state = state,
                viewportWidth = FullscreenCanvasWidth,
                viewportHeight = FullscreenCanvasHeight,
                onIntent = onIntent,
            )
            NewsFullscreenPanel(
                state = state,
                onIntent = onIntent,
                onPickImage = onPickImage,
                onPickBackground = onPickBackground,
                onClose = onClose,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun NewsFullscreenPanel(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    onPickImage: () -> Unit,
    onPickBackground: () -> Unit,
    onClose: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val selectedLayer = state.selectedLayer()
    GlassSurface(
        modifier = Modifier
            .width(FullscreenPanelWidth.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        tint = Color.White.copy(alpha = 0.08f),
        strokeColor = Color.White.copy(alpha = 0.14f),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(FullscreenPanelWidth.dp)
                .fillMaxHeight()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NewsEditorHeader(
                    title = state.editorTitle.ifBlank { "Noticia" },
                    selectedLabel = selectedLayer?.name ?: "Lienzo",
                    actionLabel = "Cerrar",
                    onAction = onClose,
                )
            }
            item {
                NewsEditorQuickActions(
                    onAddTitle = { onIntent(NewsIntent.AddTitleLayer) },
                    onAddText = { onIntent(NewsIntent.AddLayer) },
                    onAddImage = onPickImage,
                    onPickBackground = onPickBackground,
                    onValidate = { onIntent(NewsIntent.ValidateDraft) },
                    onSave = { onIntent(NewsIntent.SaveDraft) },
                    onPublish = { onIntent(NewsIntent.Publish) },
                    onSchedule = { onIntent(NewsIntent.Schedule) },
                    onDelete = onRequestDelete,
                    canDelete = state.selectedId != null,
                    hasTitleLayer = state.editorLayers.any { it.kind.isTitleKind() },
                )
            }
            if (state.editorBackgroundPath.isBlank()) item { NewsTemplateSelector(state = state, onIntent = onIntent) }
            item { NewsLayerStack(state = state, onIntent = onIntent) }
            item {
                NewsEditorInspector(
                    state = state,
                    selectedLayer = selectedLayer,
                    onIntent = onIntent,
                    compact = false,
                )
            }
            item { NewsActionState(state) }
        }
    }

}

@Composable
private fun NewsEditorHeader(
    title: String,
    selectedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(178.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NewsSmallButton(text = actionLabel, width = 78, onClick = onAction)
    }
}

@Composable
private fun NewsTemplateSelector(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("SKY", "MINT", "GOLD").forEach { style ->
            val selected = state.editorBackgroundType.toTemplateStyle() == style
            GlassSurface(
                modifier = Modifier
                    .width(74.dp)
                    .height(38.dp)
                    .clickable { onIntent(NewsIntent.UpdateBackgroundType(style)) },
                shape = RoundedCornerShape(13.dp),
                tint = if (selected) templatePalette(style).accent.copy(alpha = 0.28f) else GlassTheme.tokens.glassFill,
                strokeColor = if (selected) templatePalette(style).accent.copy(alpha = 0.78f) else GlassTheme.tokens.glassStroke,
            ) {
                Box(
                    modifier = Modifier
                        .width(74.dp)
                        .height(38.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = style,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsEditorQuickActions(
    onAddTitle: () -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onPickBackground: () -> Unit,
    onValidate: () -> Unit,
    onSave: () -> Unit,
    onPublish: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    hasTitleLayer: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileActionPill(
                icon = MobileIcon.Upload,
                label = "Publicar",
                modifier = Modifier.weight(1.4f),
                onClick = onPublish,
            )
            NewsActionButton(
                icon = MobileIcon.Clock,
                label = "Programar",
                modifier = Modifier.weight(1.2f),
                onClick = onSchedule,
            )
            NewsActionButton(
                icon = MobileIcon.Save,
                label = "Guardar",
                modifier = Modifier.weight(1f),
                onClick = onSave,
            )
        }
        NewsSectionLabel("Añadir capa")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NewsLayerChip("Título", MobileIcon.Plus, Modifier.weight(1f), enabled = !hasTitleLayer, onClick = onAddTitle)
            NewsLayerChip("Texto", MobileIcon.News, Modifier.weight(1f), onClick = onAddText)
            NewsLayerChip("Imagen", MobileIcon.Photo, Modifier.weight(1f), onClick = onAddImage)
            NewsLayerChip("Fondo", MobileIcon.Media, Modifier.weight(1f), onClick = onPickBackground)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NewsActionButton(
                icon = MobileIcon.Check,
                label = "Validar",
                modifier = if (canDelete) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                onClick = onValidate,
            )
            if (canDelete) {
                NewsActionButton(
                    icon = MobileIcon.Trash,
                    label = "Borrar",
                    modifier = Modifier.weight(1f),
                    danger = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun NewsLayerCanvasViewport(
    state: NewsState,
    viewportWidth: Int,
    viewportHeight: Int,
    onIntent: (NewsIntent) -> Unit,
) {
    val palette = templatePalette(state.editorBackgroundType)
    val canvasWidth = state.editorCanvasWidth.coerceAtLeast(LogicalCanvasWidth)
    val canvasHeight = state.editorCanvasHeight.coerceAtLeast(LogicalCanvasHeight)
    val scaleX = viewportWidth.toFloat() / canvasWidth
    val scaleY = viewportHeight.toFloat() / canvasHeight
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .width(viewportWidth.dp)
            .height(viewportHeight.dp)
            .clip(shape)
            .background(Brush.linearGradient(palette.background))
            .border(1.dp, palette.border, shape)
            .clickable { onIntent(NewsIntent.SelectBackground) },
    ) {
        if (state.editorBackgroundPath.isNotBlank()) {
            NewsBackgroundImage(
                backgroundPath = state.editorBackgroundPath,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
        }
        if (state.editorBackgroundPath.isBlank()) NewsTemplateArtwork(palette = palette)
        Box(modifier = Modifier.fillMaxSize()) {
            state.editorLayers
                .sortedBy { it.sortOrder }
                .filter { it.visible }
                .forEach { layer ->
                    NewsEditableLayer(
                        layer = layer,
                        selected = layer.id == state.selectedLayerId,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        onIntent = onIntent,
                    )
                }
        }
        if (state.editorLayers.none { it.visible }) {
            Text(
                text = "Sin capas visibles",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun BoxScope.NewsTemplateArtwork(palette: NewsTemplatePalette) {
    Canvas(modifier = Modifier.matchParentSize()) {
        val wave = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.78f)
            cubicTo(
                size.width * 0.34f,
                size.height * 0.18f,
                size.width * 0.68f,
                size.height * 0.86f,
                size.width * 1.02f,
                size.height * 0.22f,
            )
        }
        val secondWave = Path().apply {
            moveTo(size.width * 0.04f, size.height * 0.62f)
            cubicTo(
                size.width * 0.30f,
                size.height * 0.06f,
                size.width * 0.62f,
                size.height * 0.72f,
                size.width * 0.92f,
                size.height * 0.12f,
            )
        }
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0xE6101722),
                    Color(0x99121A26),
                    Color.Transparent,
                ),
            ),
        )
        drawPath(
            path = wave,
            brush = Brush.linearGradient(listOf(palette.accent.copy(alpha = 0.48f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.025f, cap = StrokeCap.Round),
        )
        drawPath(
            path = secondWave,
            brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.007f, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun NewsEditableLayer(
    layer: NewsLayerDraft,
    selected: Boolean,
    scaleX: Float,
    scaleY: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    onIntent: (NewsIntent) -> Unit,
) {
    val density = LocalDensity.current
    val currentLayer by rememberUpdatedState(layer)
    val layerShape = RoundedCornerShape(10.dp)
    val layerFill = when {
        selected -> Color.White.copy(alpha = 0.14f)
        layer.kind.isImageKind() -> Color.White.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .offset((layer.x * scaleX).roundToInt().dp, (layer.y * scaleY).roundToInt().dp)
            .width((layer.width * scaleX).roundToInt().coerceAtLeast(1).dp)
            .height((layer.height * scaleY).roundToInt().coerceAtLeast(1).dp)
            .graphicsLayer {
                rotationZ = layer.rotationDegrees.toFloat()
            }
            .background(layerFill, layerShape)
            .border(if (selected) 1.dp else 0.dp, if (selected) Color.White.copy(alpha = 0.72f) else Color.Transparent, layerShape)
            .pointerInput(layer.id, scaleX, scaleY) {
                var startFrame = currentLayer
                var totalDragX = 0f
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = {
                        startFrame = currentLayer
                        totalDragX = 0f
                        totalDragY = 0f
                        onIntent(NewsIntent.SelectLayer(startFrame.id))
                    },
                ) { change, dragAmount ->
                    change.consume()
                    if (startFrame.locked) return@detectDragGestures
                    totalDragX += with(density) { dragAmount.x.toDp().value }
                    totalDragY += with(density) { dragAmount.y.toDp().value }
                    val requestedX = startFrame.x + (totalDragX / scaleX).roundToInt()
                    val requestedY = startFrame.y + (totalDragY / scaleY).roundToInt()
                    onIntent(
                        NewsIntent.UpdateLayerFrame(
                            id = startFrame.id,
                            x = requestedX,
                            y = requestedY,
                            width = startFrame.width,
                            height = startFrame.height,
                        ),
                    )
                }
            }
            .clickable { onIntent(NewsIntent.SelectLayer(layer.id)) }
            .padding(horizontal = (10 * scaleX).dp, vertical = (7 * scaleY).dp),
    ) {
        if (layer.kind.isImageKind()) {
            NewsBackgroundImage(
                backgroundPath = layer.content,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = layer.content.ifBlank { layer.name.ifBlank { "Texto" } },
                modifier = Modifier.align(Alignment.CenterStart),
                color = layer.textColor.toNewsColor(Color.White).copy(alpha = layer.opacity.coerceIn(20, 100) / 100f),
                fontFamily = layer.fontFamily.toNewsFontFamily(),
                fontSize = if (layer.kind.isTitleKind()) 26.sp * scaleY else 16.sp * scaleY,
                lineHeight = if (layer.kind.isTitleKind()) 30.sp * scaleY else 20.sp * scaleY,
                fontWeight = if (layer.kind.isTitleKind()) FontWeight.Bold else FontWeight.Medium,
                maxLines = if (layer.kind.isTitleKind()) 3 else 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            NewsResizeHandle(
                layer = layer,
                scaleX = scaleX,
                scaleY = scaleY,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun BoxScope.NewsResizeHandle(
    layer: NewsLayerDraft,
    scaleX: Float,
    scaleY: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    onIntent: (NewsIntent) -> Unit,
) {
    val density = LocalDensity.current
    val currentLayer by rememberUpdatedState(layer)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(22.dp)
            .background(GlassTheme.tokens.accent, RoundedCornerShape(8.dp))
            .pointerInput(layer.id, scaleX, scaleY) {
                var startFrame = currentLayer
                var totalDragX = 0f
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = {
                        startFrame = currentLayer
                        totalDragX = 0f
                        totalDragY = 0f
                        onIntent(NewsIntent.SelectLayer(startFrame.id))
                    },
                ) { change, dragAmount ->
                    change.consume()
                    if (startFrame.locked) return@detectDragGestures
                    totalDragX += with(density) { dragAmount.x.toDp().value }
                    totalDragY += with(density) { dragAmount.y.toDp().value }
                    val requestedWidth = startFrame.width + (totalDragX / scaleX).roundToInt()
                    val requestedHeight = startFrame.height + (totalDragY / scaleY).roundToInt()
                    val maxWidth = (canvasWidth - startFrame.x).coerceAtLeast(MinLayerWidth)
                    val maxHeight = (canvasHeight - startFrame.y).coerceAtLeast(MinLayerHeight)
                    onIntent(
                        NewsIntent.UpdateLayerFrame(
                            id = startFrame.id,
                            x = startFrame.x,
                            y = startFrame.y,
                            width = requestedWidth.coerceIn(MinLayerWidth, maxWidth),
                            height = requestedHeight.coerceIn(MinLayerHeight, maxHeight),
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val strokeWidth = size.width * 0.13f
            drawLine(Color.White, Offset(size.width * 0.25f, size.height * 0.76f), Offset(size.width * 0.76f, size.height * 0.25f), strokeWidth, StrokeCap.Round)
            drawLine(Color.White, Offset(size.width * 0.48f, size.height * 0.82f), Offset(size.width * 0.82f, size.height * 0.48f), strokeWidth, StrokeCap.Round)
        }
    }
}

@Composable
private fun NewsLayerStrip(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(state.editorLayers.sortedByDescending { it.sortOrder }) { layer ->
            NewsLayerTile(layer = layer, selected = layer.id == state.selectedLayerId, onIntent = onIntent)
        }
    }
}

@Composable
private fun NewsLayerStack(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Capas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        state.editorLayers.sortedByDescending { it.sortOrder }.forEach { layer ->
            NewsLayerTile(layer = layer, selected = layer.id == state.selectedLayerId, onIntent = onIntent)
        }
    }
}

@Composable
private fun NewsLayerTile(
    layer: NewsLayerDraft,
    selected: Boolean,
    onIntent: (NewsIntent) -> Unit,
) {
    val isTitle = layer.kind.isTitleKind()
    val tokens = GlassTheme.tokens
    val accentStroke = if (isTitle) tokens.accent.copy(alpha = if (selected) 0.85f else 0.45f) else if (selected) tokens.accent.copy(alpha = 0.65f) else tokens.glassStroke
    val tint = if (selected) tokens.accent.copy(alpha = 0.22f) else tokens.glassFill
    GlassSurface(
        modifier = Modifier
            .width(132.dp)
            .height(58.dp)
            .clickable { onIntent(NewsIntent.SelectLayer(layer.id)) },
        shape = RoundedCornerShape(14.dp),
        tint = tint,
        strokeColor = accentStroke,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = layer.name.ifBlank { "Capa" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${layer.kind.toLayerLabel()} | ${layer.width}x${layer.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isTitle) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(tokens.accent.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("T", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tokens.accent)
                }
            }
        }
    }
}

@Composable
private fun NewsEditorInspector(
    state: NewsState,
    selectedLayer: NewsLayerDraft?,
    onIntent: (NewsIntent) -> Unit,
    compact: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selectedLayer == null) {
            NewsArticleInspector(state = state, onIntent = onIntent)
        } else {
            NewsLayerInspector(layer = selectedLayer, onIntent = onIntent, compact = compact)
        }
    }
}

@Composable
private fun NewsArticleInspector(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(state.editorStatus.toNewsStatusLabel())
            if (state.selectedId?.startsWith("local-") == true) StatusChip("solo en dispositivo")
        }
        NewsSectionLabel("Título de la noticia")
        GlassTextField(
            value = state.editorTitle,
            onValueChange = { onIntent(NewsIntent.UpdateTitle(it)) },
            placeholder = "Ej: Convocatoria TCAE Andalucía 2025",
        )
        NewsSectionLabel("Descripción")
        GlassTextField(
            value = state.editorText,
            onValueChange = { onIntent(NewsIntent.UpdateText(it)) },
            placeholder = "Texto que verán los usuarios",
            singleLine = false,
        )
        NewsSectionLabel("Especialidad")
        NewsChipSelector(
            options = listOf("TCAE" to "1", "EIR" to "2", "MIR" to "3", "FIR" to "4"),
            selected = state.editorSpecialty,
            onSelect = { onIntent(NewsIntent.UpdateSpecialty(it)) },
        )
        NewsSectionLabel("Tipo de noticia")
        NewsChipSelector(
            options = listOf("Convocatoria" to "1", "Aviso" to "2", "Oferta" to "3", "Recurso" to "4"),
            selected = state.editorType,
            onSelect = { onIntent(NewsIntent.UpdateType(it)) },
        )
    }
}

@Composable
private fun NewsLayerInspector(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
    compact: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(layer.kind.toLayerLabel())
            StatusChip(if (layer.visible) "visible" else "oculta")
            StatusChip(if (layer.locked) "bloqueada" else "editable")
        }
        GlassTextField(
            value = layer.name,
            onValueChange = { onIntent(NewsIntent.UpdateLayerName(layer.id, it)) },
            placeholder = "Nombre de capa",
        )
        if (!layer.kind.isImageKind()) {
            GlassTextField(
                value = layer.content,
                onValueChange = { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) },
                placeholder = "Contenido de la capa",
                singleLine = false,
            )
            NewsLayerStyleControls(layer = layer, onIntent = onIntent)
        } else {
            NewsLayerRotationControls(layer = layer, onIntent = onIntent)
        }
        NewsLayerSizeControls(layer = layer, onIntent = onIntent)
        NewsLayerCommands(layer = layer, onIntent = onIntent, compact = compact)
    }
}

@Composable
private fun NewsLayerStyleControls(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf("sans", "serif", "mono", "cursive")) { font ->
            NewsSmallButton(
                text = when (font) {
                    "mono" -> "01"
                    else -> "Aa"
                },
                width = 52,
                selected = layer.fontFamily == font || (layer.fontFamily.isBlank() && font == "sans"),
                onClick = { onIntent(NewsIntent.UpdateLayerFontFamily(layer.id, font)) },
            )
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf("#FFFFFF", "#111827", "#FACC15", "#38BDF8", "#22C55E")) { color ->
            val parsed = color.toNewsColor(Color.White)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (layer.textColor.equals(color, ignoreCase = true)) GlassTheme.tokens.accent.copy(alpha = 0.25f) else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .padding(4.dp)
                    .clickable { onIntent(NewsIntent.UpdateLayerTextColor(layer.id, color)) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(parsed, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

@Composable
private fun NewsLayerRotationControls(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("rotacion ${layer.rotationDegrees} grados")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsSmallButton("-15", width = 58, onClick = { onIntent(NewsIntent.UpdateLayerRotation(layer.id, layer.rotationDegrees - 15)) })
            NewsSmallButton("0", width = 50, onClick = { onIntent(NewsIntent.UpdateLayerRotation(layer.id, 0)) })
            NewsSmallButton("+15", width = 58, onClick = { onIntent(NewsIntent.UpdateLayerRotation(layer.id, layer.rotationDegrees + 15)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsSmallButton("-90", width = 58, onClick = { onIntent(NewsIntent.UpdateLayerRotation(layer.id, layer.rotationDegrees - 90)) })
            NewsSmallButton("+90", width = 58, onClick = { onIntent(NewsIntent.UpdateLayerRotation(layer.id, layer.rotationDegrees + 90)) })
        }
    }
}

@Composable
private fun NewsLayerSizeControls(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassTextField(layer.width.toString(), { onIntent(NewsIntent.UpdateLayerWidth(layer.id, it)) }, "Ancho", modifier = Modifier.width(92.dp))
        GlassTextField(layer.height.toString(), { onIntent(NewsIntent.UpdateLayerHeight(layer.id, it)) }, "Alto", modifier = Modifier.width(92.dp))
    }
}

@Composable
private fun NewsLayerCommands(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
    compact: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NewsSectionLabel("Orden en capas")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NewsActionButton(MobileIcon.Up, "Subir", Modifier.weight(1f)) { onIntent(NewsIntent.MoveLayerUp(layer.id)) }
            NewsActionButton(MobileIcon.Down, "Bajar", Modifier.weight(1f)) { onIntent(NewsIntent.MoveLayerDown(layer.id)) }
            NewsActionButton(MobileIcon.Upload, "Frente", Modifier.weight(1f)) { onIntent(NewsIntent.BringLayerToFront(layer.id)) }
            NewsActionButton(MobileIcon.Import, "Atrás", Modifier.weight(1f)) { onIntent(NewsIntent.SendLayerToBack(layer.id)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NewsActionButton(MobileIcon.Copy, "Copiar", Modifier.weight(1f)) { onIntent(NewsIntent.DuplicateLayer(layer.id)) }
            NewsActionButton(MobileIcon.Check, "Centrar", Modifier.weight(1f)) { onIntent(NewsIntent.CenterLayer(layer.id)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NewsActionButton(
                icon = MobileIcon.Eye,
                label = if (layer.visible) "Ocultar" else "Mostrar",
                modifier = Modifier.weight(1f),
                onClick = { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) },
            )
            NewsActionButton(
                icon = if (layer.locked) MobileIcon.Unlock else MobileIcon.Lock,
                label = if (layer.locked) "Desbloquear" else "Bloquear",
                modifier = Modifier.weight(1f),
                onClick = { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) },
            )
        }
        if (!layer.kind.isTitleKind()) {
            NewsActionButton(
                icon = MobileIcon.Trash,
                label = "Quitar capa",
                modifier = Modifier.fillMaxWidth(),
                danger = true,
                onClick = { onIntent(NewsIntent.RemoveLayer(layer.id)) },
            )
        } else {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tint = GlassTheme.tokens.accent.copy(alpha = 0.08f),
                strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.25f),
            ) {
                Text(
                    "La capa de título es única y no puede eliminarse.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTheme.tokens.accent.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun NewsActionState(state: NewsState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val actionState = state.actionState) {
            is UiAsync.Error -> GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tint = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f),
                strokeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.32f),
            ) {
                Text(
                    actionState.error.message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            UiAsync.Loading -> GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Guardando...",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UiAsync.Success -> GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tint = GlassTheme.tokens.accent.copy(alpha = 0.12f),
                strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.32f),
            ) {
                Text(
                    if (state.selectedId?.startsWith("local-") == true) "Borrador guardado localmente." else "Noticia sincronizada.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    color = GlassTheme.tokens.accent,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> Unit
        }
        state.validationReport?.let { report ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (report.canSave) "guardable" else "incompleta")
                StatusChip(if (report.canPublish) "publicable" else "bloqueada")
            }
        }
    }
}

@Composable
private fun NewsActionButton(
    icon: MobileIcon,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val tint = when {
        !enabled -> tokens.glassFillStrong
        danger -> Color(0xFFFF453A).copy(alpha = 0.18f)
        else -> tokens.glassFill
    }
    val strokeColor = when {
        danger && enabled -> Color(0xFFFF453A).copy(alpha = 0.45f)
        else -> tokens.glassStroke
    }
    val foreground = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        danger -> Color(0xFFFF453A)
        else -> MaterialTheme.colorScheme.onSurface
    }
    GlassSurface(
        modifier = modifier
            .height(42.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tint = tint,
        strokeColor = strokeColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(icon = icon, modifier = Modifier.size(16.dp), color = foreground)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsLayerChip(
    label: String,
    icon: MobileIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val iconColor = if (enabled) tokens.textSecondary else tokens.textSecondary.copy(alpha = 0.38f)
    val textColor = if (enabled) tokens.textSecondary else tokens.textSecondary.copy(alpha = 0.38f)
    GlassSurface(
        modifier = modifier
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        tint = if (enabled) tokens.glassFill else tokens.glassFillStrong,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LineIcon(icon = icon, modifier = Modifier.size(13.dp), color = iconColor)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = GlassTheme.tokens.textSecondary,
    )
}

@Composable
private fun NewsChipSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, id) ->
            val isSelected = selected == id
            GlassSurface(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clickable { onSelect(id) },
                shape = RoundedCornerShape(11.dp),
                tint = if (isSelected) tokens.accent.copy(alpha = 0.22f) else tokens.glassFill,
                strokeColor = if (isSelected) tokens.accent.copy(alpha = 0.65f) else tokens.glassStroke,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) tokens.accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsSmallButton(
    text: String,
    width: Int,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> GlassTheme.tokens.glassFillStrong
        selected -> GlassTheme.tokens.accent.copy(alpha = 0.32f)
        else -> GlassTheme.tokens.glassFill
    }
    GlassSurface(
        modifier = Modifier
            .width(width.dp)
            .height(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        tint = tint,
        strokeColor = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
    ) {
        Box(
            modifier = Modifier
                .width(width.dp)
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class NewsTemplatePalette(
    val background: List<Color>,
    val border: Color,
    val accent: Color,
)

private fun templatePalette(value: String): NewsTemplatePalette {
    return when (value.toTemplateStyle()) {
        "MINT" -> NewsTemplatePalette(
            background = listOf(Color(0xFF1C2535), Color(0xFF141B26), Color(0xFF0E141D)),
            border = Color(0xFF232C3B),
            accent = Color(0xFF6EF3D5),
        )
        "GOLD" -> NewsTemplatePalette(
            background = listOf(Color(0xFF1C2535), Color(0xFF141B26), Color(0xFF0E141D)),
            border = Color(0xFF2E2B25),
            accent = Color(0xFFE9C985),
        )
        else -> NewsTemplatePalette(
            background = listOf(Color(0xFF1C2535), Color(0xFF141B26), Color(0xFF0E141D)),
            border = Color(0xFF232C3B),
            accent = Color(0xFF89D5FF),
        )
    }
}

private fun NewsState.selectedLayer(): NewsLayerDraft? {
    return editorLayers.firstOrNull { it.id == selectedLayerId }
}

private fun String.toTemplateStyle(): String {
    return when (trim().uppercase()) {
        "B", "MINT" -> "MINT"
        "C", "GOLD" -> "GOLD"
        else -> "SKY"
    }
}

private fun String.isImageKind(): Boolean {
    return equals("imagen", ignoreCase = true) || equals("image", ignoreCase = true)
}

private fun String.isTitleKind(): Boolean {
    return equals("titulo", ignoreCase = true) || equals("title", ignoreCase = true)
}

private fun String.toLayerLabel(): String {
    return when {
        isImageKind() -> "imagen"
        isTitleKind() -> "titulo"
        else -> "texto"
    }
}

private fun String.toNewsFontFamily(): FontFamily {
    return when (trim().lowercase()) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }
}

private fun String.toNewsStatusLabel(): String = when (trim().lowercase()) {
    "draft" -> "borrador"
    "published" -> "publicada"
    "scheduled" -> "programada"
    "archived" -> "archivada"
    else -> this.ifBlank { "borrador" }
}

private fun String.toNewsColor(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    if (hex.length != 6) return fallback
    return runCatching {
        Color(
            red = hex.substring(0, 2).toInt(16),
            green = hex.substring(2, 4).toInt(16),
            blue = hex.substring(4, 6).toInt(16),
        )
    }.getOrElse { fallback }
}
