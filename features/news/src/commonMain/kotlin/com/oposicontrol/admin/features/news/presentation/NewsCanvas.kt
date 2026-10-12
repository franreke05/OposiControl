package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.theme.contentOn
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_CORNER_DP
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_PHONE_HEIGHT_DP
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_PHONE_WIDTH_DP
import com.oposicontrol.admin.features.news.editor.NEWS_TEXT_INSET_X
import com.oposicontrol.admin.features.news.editor.NEWS_TEXT_INSET_Y
import com.oposicontrol.admin.features.news.editor.NewsCanvasKeyAction
import com.oposicontrol.admin.features.news.editor.NewsFrame
import com.oposicontrol.admin.features.news.editor.NewsGuide
import com.oposicontrol.admin.features.news.editor.NewsResizeCorner

import com.oposicontrol.admin.features.news.editor.newsCanvasKeyAction
import com.oposicontrol.admin.features.news.editor.resizeFrame
import com.oposicontrol.admin.features.news.editor.snapFrame
import kotlin.math.roundToInt

/** Tirador de redimension: 8 dp, centrado en la esquina. */
private val HANDLE_SIZE = 8.dp
private val CANVAS_TABLE_PADDING = 16.dp
private val ZOOM_MIN = 0.5f
private val ZOOM_MAX = 2f
private const val ZOOM_STEP = 0.25f

/** Guia de una capa sin contenido: se ve lo justo para encontrarla y nunca se confunde con texto escrito. */
private const val EMPTY_LAYER_HINT_ALPHA = 0.45f
private const val EMPTY_LAYER_OUTLINE_ALPHA = 0.30f

/**
 * El lienzo sobre su mesa: el documento (lo que OposiBot pinta) dentro de una superficie hundida con borde, con la
 * barra de zoom y las medidas. [availableWidth] es el ancho en dp que la columna le deja; el lienzo se escala para
 * caber y el zoom multiplica esa escala (por encima del hueco, se desplaza en horizontal, no se corta).
 *
 * [interactive] = seleccionar, arrastrar, redimensionar y teclado. La vista previa del rail lo pone a `false`.
 */
@Composable
internal fun NewsCanvasFrame(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    availableWidth: Int,
    zoom: Float,
    onZoom: (Float) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    showChrome: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    val innerWidth = (availableWidth - 2 * CANVAS_TABLE_PADDING.value.toInt() - 2).coerceAtLeast(120)
    val fitScale = innerWidth.toFloat() / state.editorCanvasWidth.coerceAtLeast(1)
    val scale = fitScale * zoom
    val canvasDp = (state.editorCanvasWidth * scale).roundToInt()
    val editable = interactive && state.isEditable() && state.actionState !is com.oposicontrol.admin.core.common.presentation.UiAsync.Loading

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (showChrome) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Banner de Inicio · $NEWS_BANNER_PHONE_WIDTH_DP × $NEWS_BANNER_PHONE_HEIGHT_DP dp (${state.editorCanvasWidth} × ${state.editorCanvasHeight} px) · " +
                        "${state.editorLayers.count { it.visible }} de ${state.editorLayers.size} capas visibles",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("−", { onZoom((zoom - ZOOM_STEP).coerceAtLeast(ZOOM_MIN)) }, size = ButtonSize.COMPACT, enabled = zoom > ZOOM_MIN)
                    Text("${(zoom * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                    GhostButton("+", { onZoom((zoom + ZOOM_STEP).coerceAtMost(ZOOM_MAX)) }, size = ButtonSize.COMPACT, enabled = zoom < ZOOM_MAX)
                    GhostButton("Ajustar", { onZoom(1f) }, size = ButtonSize.COMPACT, enabled = zoom != 1f)
                }
            }
        }
        val focus = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        val scroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radius.card)
                .background(tokens.surfaceMuted)
                .border(1.dp, if (focused) tokens.accentInk else tokens.border, Radius.card)
                .padding(CANVAS_TABLE_PADDING)
                .then(if (zoom > 1f) Modifier.horizontalScroll(scroll) else Modifier)
                .then(
                    if (!interactive) Modifier else Modifier
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val action = newsCanvasKeyAction(event.key, event.isShiftPressed, event.isCtrlPressed || event.isMetaPressed) ?: return@onPreviewKeyEvent false
                            dispatchCanvasKey(action, state, editable, onIntent)
                        },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.border(1.dp, tokens.borderStrong)) {
                NewsCanvasSurface(state, onIntent, canvasDp, scale, editable, onFocus = { if (interactive) focus.requestFocus() })
            }
        }
        if (showChrome && interactive) {
            Text(
                text = "Flechas mueven 1 dp (Mayús: 10) · Supr quita la capa · Ctrl+D duplica · Ctrl+Z / Ctrl+Y deshacen y rehacen · Ctrl+S guarda · Tab cambia de capa.",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
    }
}

/** Traduce una accion de teclado a su intent. `true` = consumida. */
private fun dispatchCanvasKey(action: NewsCanvasKeyAction, state: NewsState, editable: Boolean, onIntent: (NewsIntent) -> Unit): Boolean {
    val layer = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }
    return when (action) {
        NewsCanvasKeyAction.Undo -> { if (state.canUndo && editable) onIntent(NewsIntent.Undo); true }
        NewsCanvasKeyAction.Redo -> { if (state.canRedo && editable) onIntent(NewsIntent.Redo); true }
        NewsCanvasKeyAction.Save -> { if (state.saveStatus().canSave) onIntent(NewsIntent.SaveDraft); true }
        NewsCanvasKeyAction.Deselect -> { onIntent(NewsIntent.DeselectLayer); true }
        NewsCanvasKeyAction.NextLayer -> { onIntent(NewsIntent.SelectAdjacentLayer(backwards = false)); true }
        NewsCanvasKeyAction.PreviousLayer -> { onIntent(NewsIntent.SelectAdjacentLayer(backwards = true)); true }
        NewsCanvasKeyAction.Duplicate -> { if (layer != null && editable) onIntent(NewsIntent.DuplicateLayer(layer.id)); layer != null }
        NewsCanvasKeyAction.DeleteLayer -> { if (layer != null && editable && !layer.locked) onIntent(NewsIntent.RemoveLayer(layer.id)); layer != null }
        is NewsCanvasKeyAction.Nudge -> {
            if (layer != null && editable && !layer.locked) onIntent(NewsIntent.NudgeLayer(layer.id, action.dx, action.dy))
            layer != null
        }
    }
}

/**
 * Vista previa fija, sin cromo ni gestos: el banner tal como lo enmarca OposiBot (esquinas de 8 dp y borde blanco al
 * 40 %, escalados al ancho pedido). [availableWidth] en dp: 358 es el movil a tamano real y 704 la tableta.
 */
@Composable
internal fun NewsCanvasPreview(state: NewsState, availableWidth: Int, modifier: Modifier = Modifier) {
    val scale = availableWidth.toFloat() / state.editorCanvasWidth.coerceAtLeast(1)
    val shape = RoundedCornerShape((NEWS_BANNER_CORNER_DP * availableWidth.toFloat() / NEWS_BANNER_PHONE_WIDTH_DP).dp)
    Box(modifier = modifier.clip(shape).border(0.5.dp, NEWS_BANNER_BORDER, shape)) {
        NewsCanvasSurface(state, onIntent = {}, canvasDp = availableWidth, scale = scale, editable = false, onFocus = {})
    }
}

/**
 * El documento pintado: plantilla de color o imagen de fondo y las capas visibles en orden. Con [editable], cada
 * capa se puede seleccionar, mover (imantada a bordes y centros) y redimensionar desde sus esquinas.
 */
@Composable
private fun NewsCanvasSurface(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    canvasDp: Int,
    scale: Float,
    editable: Boolean,
    onFocus: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    // El documento NO usa el tema del panel: su fondo y su tinta son contenido editorial y tienen que verse igual en
    // claro, en oscuro y en la app. Solo el cromo (seleccion, guias, tiradores) usa tokens.
    val template = newsTemplate(state.editorBackgroundType)
    var guides by remember(state.selectedId) { mutableStateOf<List<NewsGuide>>(emptyList()) }
    val heightDp = (state.editorCanvasHeight * scale).roundToInt()
    Box(
        Modifier
            .width(canvasDp.dp)
            .height(heightDp.dp)
            .clipToBounds()
            .background(Brush.linearGradient(template.background))
            .then(
                if (editable) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onFocus()
                    onIntent(NewsIntent.DeselectLayer)
                } else Modifier,
            ),
    ) {
        if (state.editorBackgroundUrl.isNotBlank()) {
            NewsAssetImage(state.editorBackgroundUrl, Modifier.width(canvasDp.dp).height(heightDp.dp))
            // Velo sobre la foto para que el texto claro se lea encima de cualquier imagen.
            Box(Modifier.width(canvasDp.dp).height(heightDp.dp).background(NEWS_IMAGE_SCRIM))
        } else {
            NewsTemplateArtwork(template, Modifier.width(canvasDp.dp).height(heightDp.dp))
        }
        val visible = state.editorLayers.filter { it.visible }.sortedBy { it.sortOrder }
        visible.forEach { layer ->
            NewsCanvasLayerBox(
                layer = layer,
                selected = layer.id == state.selectedLayerId,
                scale = scale,
                canvasWidth = state.editorCanvasWidth,
                canvasHeight = state.editorCanvasHeight,
                others = visible.filterNot { it.id == layer.id }.map { it.frame() },
                editable = editable,
                onIntent = onIntent,
                onFocus = onFocus,
                onGuides = { guides = it },
            ) {
                // Una capa vacia no se pinta en la app. Mientras se edita, su nombre y su contorno dicen donde esta; las
                // vistas previas (no editables) ensenan exactamente lo que saldra: nada.
                val empty = layer.content.isBlank()
                if (empty && editable) {
                    Box(Modifier.matchParentSize().border(1.dp, NEWS_DOCUMENT_INK.copy(alpha = EMPTY_LAYER_OUTLINE_ALPHA)))
                }
                if (layer.isImageLayer()) {
                    if (!empty) NewsAssetImage(layer.content, Modifier.width((layer.width * scale).dp).height((layer.height * scale).dp))
                    else if (editable) Text("Imagen pendiente", Modifier.padding(4.dp), color = NEWS_DOCUMENT_INK.copy(alpha = EMPTY_LAYER_HINT_ALPHA), style = MaterialTheme.typography.labelSmall)
                } else if (empty) {
                    if (editable) {
                        Text(
                            text = layer.name.ifBlank { "Capa vacía" },
                            color = NEWS_DOCUMENT_INK.copy(alpha = EMPTY_LAYER_HINT_ALPHA),
                            fontSize = (layer.effectiveFontSizeSp() * scale).sp,
                            lineHeight = (layer.effectiveFontSizeSp() * scale * 1.2f).sp,
                            fontWeight = layer.effectiveFontWeight(),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(horizontal = (NEWS_TEXT_INSET_X * scale).dp, vertical = (NEWS_TEXT_INSET_Y * scale).dp),
                        )
                    }
                } else {
                    var clipped by remember(layer.id) { mutableStateOf(false) }
                    if (clipped && editable) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .background(tokens.warning)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text("Texto cortado", color = tokens.contentOn(tokens.warning), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        text = layer.content,
                        onTextLayout = { clipped = it.hasVisualOverflow },
                        color = newsLayerColor(layer.textColor, NEWS_DOCUMENT_INK),
                        fontFamily = when (layer.fontFamily) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> FontFamily.SansSerif },
                        fontSize = (layer.effectiveFontSizeSp() * scale).sp,
                        lineHeight = (layer.effectiveFontSizeSp() * scale * 1.2f).sp,
                        fontWeight = layer.effectiveFontWeight(),
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(horizontal = (NEWS_TEXT_INSET_X * scale).dp, vertical = (NEWS_TEXT_INSET_Y * scale).dp),
                    )
                }
            }
        }
        guides.forEach { guide ->
            if (guide.vertical) {
                Box(Modifier.offset(x = (guide.position * scale).dp).width(1.dp).height(heightDp.dp).background(tokens.accentInk))
            } else {
                Box(Modifier.offset(y = (guide.position * scale).dp).height(1.dp).width(canvasDp.dp).background(tokens.accentInk))
            }
        }
        if (visible.isEmpty() && editable) {
            Text("Añade una capa para empezar", Modifier.padding(20.dp), color = NEWS_DOCUMENT_INK.copy(alpha = EMPTY_LAYER_HINT_ALPHA), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NewsCanvasLayerBox(
    layer: NewsLayerDraft,
    selected: Boolean,
    scale: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    others: List<NewsFrame>,
    editable: Boolean,
    onIntent: (NewsIntent) -> Unit,
    onFocus: () -> Unit,
    onGuides: (List<NewsGuide>) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    val currentLayer by rememberUpdatedState(layer)
    val canDrag = editable && !layer.locked
    // El acumulador arranca del marco real de la capa en cada gesto, no de la ultima posicion pintada: asi el
    // redondeo a entero no va sumando deriva arrastre tras arrastre.
    var start by remember(layer.id) { mutableStateOf(layer.frame()) }
    var accX by remember(layer.id) { mutableStateOf(0f) }
    var accY by remember(layer.id) { mutableStateOf(0f) }

    Box(
        Modifier
            .offset((layer.x * scale).dp, (layer.y * scale).dp)
            .size((layer.width * scale).dp, (layer.height * scale).dp)
            .graphicsLayer {
                alpha = layer.opacity.coerceIn(0, 100) / 100f
                rotationZ = layer.rotationDegrees.toFloat()
            }
            .then(if (selected && editable) Modifier.border(1.dp, tokens.accentInk) else Modifier)
            .then(
                if (!editable) Modifier else Modifier
                    .pointerHoverIcon(if (canDrag) PointerIcon.Hand else PointerIcon.Default)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onFocus()
                        onIntent(NewsIntent.SelectLayer(layer.id))
                    },
            )
            .then(
                if (!canDrag) Modifier else Modifier.pointerInput(layer.id, scale) {
                    detectDragGestures(
                        onDragStart = {
                            onFocus()
                            onIntent(NewsIntent.SelectLayer(currentLayer.id))
                            start = currentLayer.frame()
                            accX = 0f
                            accY = 0f
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            accX += delta.x / scale
                            accY += delta.y / scale
                            val moved = start.copy(x = (start.x + accX).roundToInt(), y = (start.y + accY).roundToInt())
                            // Mover conserva el tamano: la capa se detiene en el borde, no se encoge contra el.
                            val snap = snapFrame(moved, canvasWidth, canvasHeight, others)
                            onGuides(snap.guides)
                            onIntent(NewsIntent.UpdateLayerFrame(currentLayer.id, snap.frame.x, snap.frame.y, snap.frame.width, snap.frame.height, gesture = true))
                        },
                        onDragEnd = { onGuides(emptyList()) },
                        onDragCancel = { onGuides(emptyList()) },
                    )
                },
            ),
        content = content,
    )

    // Tiradores: solo en la capa seleccionada y desbloqueada. Un tirador decorativo es una promesa falsa.
    if (selected && canDrag) {
        NewsResizeCorner.entries.forEach { corner ->
            val hx = layer.x + if (corner.atEnd) layer.width else 0
            val hy = layer.y + if (corner.atBottom) layer.height else 0
            Box(
                Modifier
                    .offset((hx * scale).dp - HANDLE_SIZE / 2, (hy * scale).dp - HANDLE_SIZE / 2)
                    .size(HANDLE_SIZE)
                    .background(tokens.accentInk)
                    .border(1.dp, tokens.surfaceSolid)
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(layer.id, scale, corner) {
                        detectDragGestures(
                            onDragStart = {
                                onFocus()
                                start = currentLayer.frame()
                                accX = 0f
                                accY = 0f
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                accX += delta.x / scale
                                accY += delta.y / scale
                                val resized = resizeFrame(start, corner, accX, accY, canvasWidth, canvasHeight)
                                onIntent(NewsIntent.UpdateLayerFrame(currentLayer.id, resized.x, resized.y, resized.width, resized.height, gesture = true))
                            },
                        )
                    },
            )
        }
    }
}

/** El color almacenado pertenece al contenido editorial; el cromo usa exclusivamente tokens. */
internal fun newsLayerColor(value: String, fallback: Color): Color = runCatching {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) fallback else Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
}.getOrDefault(fallback)

@Composable
internal expect fun NewsAssetImage(path: String, modifier: Modifier)

@Composable
internal expect fun rememberNewsImagePicker(onPicked: (String) -> Unit): () -> Unit
