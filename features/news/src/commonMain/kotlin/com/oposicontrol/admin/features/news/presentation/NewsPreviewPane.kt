package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.roundToInt
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

/**
 * El lienzo dentro de una superficie de trabajo, no flotando sobre el panel.
 *
 * Antes era un rectángulo oscuro pegado al borde de la tarjeta: sin marco, sin
 * fondo de mesa y sin medidas a la vista, así que no se distinguía el LIENZO
 * (lo que se publica) del PANEL (la herramienta). Aquí el lienzo se apoya sobre
 * una mesa con su propio tono, lleva borde propio y las medidas debajo.
 */
@Composable
internal fun NewsCanvasFrame(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    canvasWidth: Int,
    modifier: Modifier = Modifier,
    footer: Boolean = true,
    toolbar: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    // El zoom es estado de VISTA, no del documento: acercarse a mirar una capa
    // no es un cambio que deba guardarse ni viajar al servidor. Por eso vive
    // aquí y no en `NewsState`, y por eso no ensucia `isDirty`.
    var zoom by remember { mutableStateOf(1f) }
    val zoomed = (canvasWidth * zoom).roundToInt().coerceIn(120, 1600)
    val selected = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toolbar) {
            NewsCanvasToolbar(
                zoom = zoom,
                onZoom = { zoom = it },
                selected = selected,
                canvasWidth = state.editorCanvasWidth,
                canvasHeight = state.editorCanvasHeight,
                onIntent = onIntent,
            )
        }
        val focus = remember { FocusRequester() }
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.surfaceSolid)
                .border(1.dp, tokens.border, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .then(
                    if (!toolbar) Modifier else Modifier
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { event -> handleCanvasKey(event, selected, state, onIntent) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.border(1.dp, tokens.border)) {
                NewsCanvas(state, onIntent, zoomed)
            }
        }
        if (toolbar) {
            Text(
                "Flechas mueven 1 px · con Mayús, 10 px · Supr elimina la capa seleccionada.",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
        if (footer) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.editorCanvasWidth} × ${state.editorCanvasHeight} px",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                )
                Text(
                    "${state.editorLayers.count { it.visible }} de ${state.editorLayers.size} capas visibles",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Barra del lienzo: zoom y alineación de la capa seleccionada.
 *
 * Alinear NO necesita modelo nuevo: es `UpdateLayerFrame` con la x o la y
 * calculadas contra el lienzo. Era la pieza que faltaba para dejar de colocar
 * las capas «a ojo» escribiendo números.
 *
 * Aquí NO hay deshacer/rehacer ni guías: no existen en el estado, y un botón
 * que no hace nada miente más que su ausencia.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsCanvasToolbar(
    zoom: Float,
    onZoom: (Float) -> Unit,
    selected: NewsLayerDraft?,
    canvasWidth: Int,
    canvasHeight: Int,
    onIntent: (NewsIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val canAlign = selected != null && !selected.locked

    fun align(x: Int? = null, y: Int? = null) {
        val layer = selected ?: return
        onIntent(
            NewsIntent.UpdateLayerFrame(
                id = layer.id,
                x = x ?: layer.x,
                y = y ?: layer.y,
                width = layer.width,
                height = layer.height,
            ),
        )
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GhostButton("Izquierda", { align(x = 0) }, enabled = canAlign)
            GhostButton("Centro H", { align(x = ((canvasWidth - (selected?.width ?: 0)) / 2).coerceAtLeast(0)) }, enabled = canAlign)
            GhostButton("Derecha", { align(x = (canvasWidth - (selected?.width ?: 0)).coerceAtLeast(0)) }, enabled = canAlign)
            GhostButton("Arriba", { align(y = 0) }, enabled = canAlign)
            GhostButton("Centro V", { align(y = ((canvasHeight - (selected?.height ?: 0)) / 2).coerceAtLeast(0)) }, enabled = canAlign)
            GhostButton("Abajo", { align(y = (canvasHeight - (selected?.height ?: 0)).coerceAtLeast(0)) }, enabled = canAlign)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−", { onZoom((zoom - 0.25f).coerceAtLeast(0.5f)) }, enabled = zoom > 0.5f)
            Text(
                "${(zoom * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
            GhostButton("+", { onZoom((zoom + 0.25f).coerceAtMost(2f)) }, enabled = zoom < 2f)
            GhostButton("Ajustar", { onZoom(1f) }, enabled = zoom != 1f)
        }
    }
}

@Composable
internal fun NewsPreviewPane(state: NewsState, onIntent: (NewsIntent) -> Unit, width: Int) {
    NewsPanel("Vista previa") {
        Text("Composición final de la noticia.", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        val canvasWidth = (width - 72).coerceAtLeast(160).coerceAtMost(640)
        NewsCanvasFrame(state, onIntent, canvasWidth)
        GhostButton("Ampliar composición", { onIntent(NewsIntent.ToggleCanvasFullscreen) })
        Text("Referencia de composición; no reproduce la pantalla final de OposiBot.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
    }
    if (state.canvasFullscreen) Dialog(onDismissRequest = { onIntent(NewsIntent.ToggleCanvasFullscreen) }) {
        var modalWidth by remember { mutableStateOf(600) }
        val density = LocalDensity.current
        NewsPanel("Composición ampliada", Modifier.width(860.dp).onSizeChanged { modalWidth = with(density) { it.width.toDp().value.toInt() } }) {
            NewsCanvasFrame(state, onIntent, (modalWidth - 72).coerceAtLeast(100))
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
                NewsCanvasLayerBox(
                    layer = layer,
                    selected = selected,
                    scale = scale,
                    canvasWidth = state.editorCanvasWidth,
                    canvasHeight = state.editorCanvasHeight,
                    onIntent = onIntent,
                ) {
                    if (layer.kind.lowercase() in listOf("imagen", "image")) NewsAssetImage(layer.content, Modifier.matchParentSize())
                    else Text(layer.content, color = newsLayerColor(layer.textColor, GlassTheme.tokens.contentOn(canvasBase)),
                        fontFamily = when (layer.fontFamily) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> FontFamily.SansSerif },
                        fontSize = (layer.effectiveFontSizeSp() * scale).sp,
                        fontWeight = layer.effectiveFontWeight(),
                        overflow = TextOverflow.Clip)
                }
            }
            if (state.editorLayers.none { it.visible }) Text("Añade una capa para empezar", Modifier.padding(20.dp), color = GlassTheme.tokens.textSecondary)
        }
}

/**
 * Una capa del lienzo, movible y redimensionable con el ratón.
 *
 * El intent `UpdateLayerFrame(id, x, y, width, height)` ya existía y ya se
 * persistía en `capas jsonb`, pero NINGÚN gesto lo disparaba: la única forma de
 * mover un título era escribir números en el inspector y mirar el resultado.
 * Aquí se conecta el gesto a ese intent que ya funcionaba.
 *
 * Reglas del gesto:
 * - Una capa BLOQUEADA no se mueve ni se redimensiona, y no muestra tiradores.
 *   Bloquear tiene que significar algo al tocarla, no solo en la lista.
 * - El acumulador arranca del valor real de la capa en cada `onDragStart`, no de
 *   la última posición pintada: así el redondeo a entero no va sumando deriva
 *   arrastre tras arrastre.
 * - Todo se recorta al lienzo. Una capa fuera de los límites no se ve en
 *   OposiBot, y dejar arrastrarla al vacío es prometer algo que no ocurre.
 */
@Composable
private fun NewsCanvasLayerBox(
    layer: NewsLayerDraft,
    selected: Boolean,
    scale: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    onIntent: (NewsIntent) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    var accX by remember(layer.id) { mutableStateOf(0f) }
    var accY by remember(layer.id) { mutableStateOf(0f) }
    var accW by remember(layer.id) { mutableStateOf(0f) }
    var accH by remember(layer.id) { mutableStateOf(0f) }

    fun emit(x: Float, y: Float, w: Float, h: Float) {
        val width = w.roundToInt().coerceIn(MIN_LAYER_SIDE, canvasWidth)
        val height = h.roundToInt().coerceIn(MIN_LAYER_SIDE, canvasHeight)
        onIntent(
            NewsIntent.UpdateLayerFrame(
                id = layer.id,
                x = x.roundToInt().coerceIn(0, (canvasWidth - width).coerceAtLeast(0)),
                y = y.roundToInt().coerceIn(0, (canvasHeight - height).coerceAtLeast(0)),
                width = width,
                height = height,
            ),
        )
    }

    Box(
        Modifier
            .offset((layer.x * scale).dp, (layer.y * scale).dp)
            .size((layer.width * scale).dp, (layer.height * scale).dp)
            .graphicsLayer {
                alpha = layer.opacity.coerceIn(0, 100) / 100f
                rotationZ = layer.rotationDegrees.toFloat()
            }
            .then(if (selected) Modifier.border(1.dp, tokens.accentInk) else Modifier)
            .clickable { onIntent(NewsIntent.SelectLayer(layer.id)) }
            .then(
                if (layer.locked) Modifier else Modifier.pointerInput(layer.id, scale) {
                    detectDragGestures(
                        onDragStart = {
                            onIntent(NewsIntent.SelectLayer(layer.id))
                            accX = layer.x.toFloat()
                            accY = layer.y.toFloat()
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            accX += delta.x / scale
                            accY += delta.y / scale
                            emit(accX, accY, layer.width.toFloat(), layer.height.toFloat())
                        },
                    )
                },
            ),
        content = content,
    )

    // Tiradores: solo en la capa seleccionada y desbloqueada. Si no se pueden
    // usar, no se pintan — un tirador decorativo es una promesa falsa.
    if (selected && !layer.locked) {
        val handles = listOf(
            NewsResizeCorner.TOP_START,
            NewsResizeCorner.TOP_END,
            NewsResizeCorner.BOTTOM_START,
            NewsResizeCorner.BOTTOM_END,
        )
        handles.forEach { corner ->
            val hx = layer.x + if (corner.atEnd) layer.width else 0
            val hy = layer.y + if (corner.atBottom) layer.height else 0
            Box(
                Modifier
                    .offset((hx * scale).dp - 4.dp, (hy * scale).dp - 4.dp)
                    .size(8.dp)
                    .background(tokens.accentInk)
                    .pointerInput(layer.id, scale, corner) {
                        detectDragGestures(
                            onDragStart = {
                                accX = layer.x.toFloat()
                                accY = layer.y.toFloat()
                                accW = layer.width.toFloat()
                                accH = layer.height.toFloat()
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                val dx = delta.x / scale
                                val dy = delta.y / scale
                                if (corner.atEnd) {
                                    accW += dx
                                } else {
                                    accX += dx
                                    accW -= dx
                                }
                                if (corner.atBottom) {
                                    accH += dy
                                } else {
                                    accY += dy
                                    accH -= dy
                                }
                                emit(accX, accY, accW, accH)
                            },
                        )
                    },
            )
        }
    }
}

/**
 * Teclado del lienzo.
 *
 * Mover con las flechas es lo que distingue colocar de aproximar: el ratón deja
 * la capa cerca y el teclado la deja donde toca. Mayús salta de 10 en 10 para
 * recorrer el lienzo sin cien pulsaciones.
 *
 * Solo actúa sobre una capa seleccionada y desbloqueada, y solo consume la
 * tecla cuando hace algo: devolver `true` siempre secuestraría la navegación
 * por teclado del resto del panel.
 */
private fun handleCanvasKey(
    event: KeyEvent,
    selected: NewsLayerDraft?,
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val layer = selected?.takeIf { !it.locked } ?: return false

    if (event.key == Key.Delete) {
        onIntent(NewsIntent.RemoveLayer(layer.id))
        return true
    }

    val step = if (event.isShiftPressed) 10 else 1
    val dx = when (event.key) {
        Key.DirectionLeft -> -step
        Key.DirectionRight -> step
        else -> 0
    }
    val dy = when (event.key) {
        Key.DirectionUp -> -step
        Key.DirectionDown -> step
        else -> 0
    }
    if (dx == 0 && dy == 0) return false

    onIntent(
        NewsIntent.UpdateLayerFrame(
            id = layer.id,
            x = (layer.x + dx).coerceIn(0, (state.editorCanvasWidth - layer.width).coerceAtLeast(0)),
            y = (layer.y + dy).coerceIn(0, (state.editorCanvasHeight - layer.height).coerceAtLeast(0)),
            width = layer.width,
            height = layer.height,
        ),
    )
    return true
}

/** Lado mínimo de una capa: por debajo deja de poder agarrarse con el ratón. */
private const val MIN_LAYER_SIDE = 16

private enum class NewsResizeCorner(val atEnd: Boolean, val atBottom: Boolean) {
    TOP_START(false, false),
    TOP_END(true, false),
    BOTTOM_START(false, true),
    BOTTOM_END(true, true),
}

/**
 * Tamaño con el que se pinta la capa.
 *
 * `fontSizeSp = 0` NO es «tamaño cero»: significa «derivado del tipo de capa»,
 * que es exactamente como se pintaba antes de que el campo existiera. Así, lo
 * ya guardado se sigue viendo igual y solo cambia quien lo ajuste a mano.
 */
internal fun NewsLayerDraft.effectiveFontSizeSp(): Int =
    fontSizeSp.takeIf { it > 0 } ?: if (kind in listOf("titulo", "title")) 25 else 16

/** Mismo criterio para el peso: `"normal"` en una capa de título sigue siendo negrita. */
internal fun NewsLayerDraft.effectiveFontWeight(): FontWeight = when (fontWeight) {
    "bold" -> FontWeight.Bold
    "semibold" -> FontWeight.SemiBold
    "medium" -> FontWeight.Medium
    else -> if (kind in listOf("titulo", "title")) FontWeight.Bold else FontWeight.Normal
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
