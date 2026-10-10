package com.oposicontrol.admin.features.news.editor

import kotlin.math.abs
import kotlin.math.roundToInt

/** Marco de una capa en pixeles del lienzo. */
data class NewsFrame(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

/** Una guia que el lienzo pinta mientras una capa esta imantada a ella. */
data class NewsGuide(val vertical: Boolean, val position: Int)

/** Resultado de imantar un marco: donde queda y que guias explican por que. */
data class NewsSnap(val frame: NewsFrame, val guides: List<NewsGuide>)

enum class NewsAlignment { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM }

enum class NewsResizeCorner(val atEnd: Boolean, val atBottom: Boolean) {
    TOP_START(false, false),
    TOP_END(true, false),
    BOTTOM_START(false, true),
    BOTTOM_END(true, true),
}

/** Distancia (px del lienzo) a la que una arista se imanta a una guia. */
const val NEWS_SNAP_THRESHOLD: Int = 6

/**
 * Recorta un marco al lienzo conservando su origen cuando es posible: primero se acota la posicion, despues el
 * tamano al hueco que queda. Una capa fuera del lienzo no se ve en OposiBot, asi que aqui nunca puede quedar fuera.
 */
fun clampFrame(frame: NewsFrame, canvasWidth: Int, canvasHeight: Int, minSide: Int = NEWS_LAYER_MIN_SIDE): NewsFrame {
    val safeWidth = canvasWidth.coerceAtLeast(minSide)
    val safeHeight = canvasHeight.coerceAtLeast(minSide)
    val x = frame.x.coerceIn(0, safeWidth - minSide)
    val y = frame.y.coerceIn(0, safeHeight - minSide)
    return NewsFrame(
        x = x,
        y = y,
        width = frame.width.coerceIn(minSide, safeWidth - x),
        height = frame.height.coerceIn(minSide, safeHeight - y),
    )
}

/**
 * Mete un marco en el lienzo CONSERVANDO su tamano: lo que hace un movimiento. Si la capa es mayor que el lienzo, el
 * tamano se recorta al lienzo; si cabe, solo se empuja la posicion. Compara con [clampFrame], que conserva el origen
 * (lo que hace un valor tecleado).
 */
fun fitFrame(frame: NewsFrame, canvasWidth: Int, canvasHeight: Int, minSide: Int = NEWS_LAYER_MIN_SIDE): NewsFrame {
    val width = frame.width.coerceIn(minSide, canvasWidth.coerceAtLeast(minSide))
    val height = frame.height.coerceIn(minSide, canvasHeight.coerceAtLeast(minSide))
    return NewsFrame(
        x = frame.x.coerceIn(0, (canvasWidth - width).coerceAtLeast(0)),
        y = frame.y.coerceIn(0, (canvasHeight - height).coerceAtLeast(0)),
        width = width,
        height = height,
    )
}

/** Desplaza un marco [dx], [dy] sin cambiar su tamano; se detiene en los bordes. */
fun nudgeFrame(frame: NewsFrame, dx: Int, dy: Int, canvasWidth: Int, canvasHeight: Int): NewsFrame =
    fitFrame(frame.copy(x = frame.x + dx, y = frame.y + dy), canvasWidth, canvasHeight)

fun alignFrame(frame: NewsFrame, alignment: NewsAlignment, canvasWidth: Int, canvasHeight: Int): NewsFrame = when (alignment) {
    NewsAlignment.LEFT -> frame.copy(x = 0)
    NewsAlignment.CENTER_H -> frame.copy(x = ((canvasWidth - frame.width) / 2).coerceAtLeast(0))
    NewsAlignment.RIGHT -> frame.copy(x = (canvasWidth - frame.width).coerceAtLeast(0))
    NewsAlignment.TOP -> frame.copy(y = 0)
    NewsAlignment.CENTER_V -> frame.copy(y = ((canvasHeight - frame.height) / 2).coerceAtLeast(0))
    NewsAlignment.BOTTOM -> frame.copy(y = (canvasHeight - frame.height).coerceAtLeast(0))
}

/**
 * Redimensiona desde una esquina a partir del marco INICIAL del gesto y el desplazamiento acumulado: asi el
 * redondeo no se acumula frame a frame. La esquina opuesta se queda clavada; el lado nunca baja de [minSide].
 */
fun resizeFrame(
    start: NewsFrame,
    corner: NewsResizeCorner,
    dx: Float,
    dy: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    minSide: Int = NEWS_LAYER_MIN_SIDE,
): NewsFrame {
    val right = start.right
    val bottom = start.bottom
    var x = start.x
    var y = start.y
    var width = start.width
    var height = start.height
    if (corner.atEnd) {
        width = (start.width + dx).roundToInt().coerceIn(minSide, canvasWidth - start.x)
    } else {
        x = (start.x + dx).roundToInt().coerceIn(0, right - minSide)
        width = right - x
    }
    if (corner.atBottom) {
        height = (start.height + dy).roundToInt().coerceIn(minSide, canvasHeight - start.y)
    } else {
        y = (start.y + dy).roundToInt().coerceIn(0, bottom - minSide)
        height = bottom - y
    }
    return clampFrame(NewsFrame(x, y, width, height), canvasWidth, canvasHeight, minSide)
}

/**
 * Imanta un marco que se esta MOVIENDO a los bordes y centros del lienzo y de las demas capas.
 *
 * Solo cambia la posicion, nunca el tamano. Para cada eje gana la guia mas cercana dentro de [threshold]; si ninguna
 * esta a tiro, el marco queda donde el raton lo dejo. Las guias devueltas son las que han imantado, no todas las
 * candidatas: pintar una guia que no ha hecho nada es ruido.
 */
fun snapFrame(
    frame: NewsFrame,
    canvasWidth: Int,
    canvasHeight: Int,
    others: List<NewsFrame> = emptyList(),
    threshold: Int = NEWS_SNAP_THRESHOLD,
): NewsSnap {
    val fitted = fitFrame(frame, canvasWidth, canvasHeight)
    val verticalTargets = listOf(0, canvasWidth / 2, canvasWidth) + others.flatMap { listOf(it.x, it.centerX, it.right) }
    val horizontalTargets = listOf(0, canvasHeight / 2, canvasHeight) + others.flatMap { listOf(it.y, it.centerY, it.bottom) }

    val snapX = closestSnap(listOf(fitted.x, fitted.centerX, fitted.right), verticalTargets, threshold)
    val snapY = closestSnap(listOf(fitted.y, fitted.centerY, fitted.bottom), horizontalTargets, threshold)

    val snapped = fitted.copy(
        x = snapX?.let { fitted.x + it.delta } ?: fitted.x,
        y = snapY?.let { fitted.y + it.delta } ?: fitted.y,
    )
    val guides = buildList {
        snapX?.let { add(NewsGuide(vertical = true, position = it.target)) }
        snapY?.let { add(NewsGuide(vertical = false, position = it.target)) }
    }
    return NewsSnap(fitFrame(snapped, canvasWidth, canvasHeight), guides)
}

private data class SnapHit(val delta: Int, val target: Int)

/** La pareja (arista, guia) mas cercana en un eje, si alguna entra en el umbral. */
private fun closestSnap(edges: List<Int>, targets: List<Int>, threshold: Int): SnapHit? =
    edges.flatMap { edge -> targets.map { target -> SnapHit(delta = target - edge, target = target) } }
        .filter { abs(it.delta) <= threshold }
        .minByOrNull { abs(it.delta) }
