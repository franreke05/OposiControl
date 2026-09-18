package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Etiquetas de prueba de la columna del editor. Sólo semántica: no se pintan. */
internal const val EXAM_HEADER_TAG: String = "exam-header"
internal const val EXAM_SUMMARY_STRIP_TAG: String = "exam-summary-strip"
internal const val EXAM_EDITOR_FORM_TAG: String = "exam-editor-form"
internal const val EXAM_SAVE_BAR_TAG: String = "exam-save-bar"

/**
 * La columna central en master-detail: la cabecera del examen, la franja de
 * resumen cuando no hay columna de resumen, y el marco del editor —pestañas,
 * formulario y barra de guardar—.
 *
 * **Lo que arregla** (P0-2). La columna era un `Column` con el marco en
 * `weight(1f)`: cabecera y franja medían lo que quisieran y el formulario se
 * quedaba con el resto, fuera cuanto fuera. En 1366×768 el resto era 0px. La
 * franja ya no crece ([ExamSummaryStrip]), pero la cabecera sí puede —dos líneas
 * de descripción, metadatos que saltan de línea— y una ventana puede ser más baja
 * que 768.
 *
 * **La regla.** El formulario tiene un alto mínimo garantizado,
 * [ExamWorkspaceSizing.editorFormMinHeight]:
 *
 *  - Si la columna se lo puede dar con la cabecera a la vista, todo queda como
 *    siempre: la cabecera fija y el formulario desplaza dentro de su marco.
 *  - Si no, la columna **pasa a desplazar la página**: al bajar, la cabecera y la
 *    franja se van por arriba y el marco del editor crece hasta ocupar la columna
 *    entera, con las pestañas arriba y la barra de guardar **anclada abajo y
 *    siempre visible**. Al subir, vuelven. La barra de guardar nunca se desplaza:
 *    es lo que la justifica (D8).
 *
 * Por qué así y no con un `verticalScroll` de toda la columna: con un scroll de
 * página la barra de guardar quedaría debajo del pliegue hasta llegar al final,
 * que es justo lo que no puede pasar; y un marco que arranca con el alto de la
 * ventana entera empezaría con su barra fuera. Aquí el marco se ancla al fondo y
 * lo que cede es la cabecera.
 *
 * El desplazamiento se alimenta de dos sitios: la rueda sobre el formulario
 * (primero se va la cabecera y después desplaza el formulario; al subir, primero
 * el formulario vuelve arriba y después baja la cabecera) y la rueda sobre la
 * propia cabecera o la franja. Cambiar de examen devuelve la cabecera a su sitio.
 *
 * El alto que el marco gasta fuera del formulario no se copia en una constante:
 * se mide ([ExamWorkspaceTabs] informa del alto de su formulario), y
 * [ExamWorkspaceSizing.editorFrameChromeEstimate] sólo cubre el primer pase.
 */
@Composable
internal fun ExamEditorColumnLayout(
    resetKey: Any?,
    header: @Composable () -> Unit,
    strip: (@Composable () -> Unit)?,
    frame: @Composable (onFormViewportHeight: (Int) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gap = with(density) { ExamWorkspaceSizing.columnGap.roundToPx() }
    val minForm = with(density) { ExamWorkspaceSizing.editorFormMinHeight.roundToPx() }
    val chromeEstimate = with(density) { ExamWorkspaceSizing.editorFrameChromeEstimate.roundToPx() }
    // Cambiar de examen devuelve la cabecera a su sitio: quien abre otro examen
    // tiene que ver cuál ha abierto.
    val collapse = remember(resetKey) { ExamEditorCollapse() }

    Layout(
        content = {
            Box(Modifier.layoutId(HEADER_SLOT), propagateMinConstraints = true) { header() }
            if (strip != null) {
                Box(Modifier.layoutId(STRIP_SLOT), propagateMinConstraints = true) { strip() }
            }
            Box(Modifier.layoutId(FRAME_SLOT), propagateMinConstraints = true) {
                frame(collapse::onFormViewportHeight)
            }
        },
        modifier = modifier
            .clipToBounds()
            .nestedScroll(collapse.connection)
            .scrollable(state = collapse.scrollable, orientation = Orientation.Vertical),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val fullWidth = Constraints(minWidth = width, maxWidth = width)
        val header = measurables.first { it.layoutId == HEADER_SLOT }.measure(fullWidth)
        val strip = measurables.firstOrNull { it.layoutId == STRIP_SLOT }?.measure(fullWidth)
        val block = header.height + gap + (strip?.let { it.height + gap } ?: 0)
        val chrome = collapse.chrome.takeIf { it > 0 } ?: chromeEstimate
        // Sin alto acotado no hay nada que repartir: el marco mide lo mínimo.
        val viewport = if (constraints.hasBoundedHeight) constraints.maxHeight else block + chrome + minForm
        val range = examEditorCollapseRange(viewport, block, chrome, minForm)
        collapse.range = range
        val offset = collapse.offset.roundToInt().coerceIn(range.first, range.last)
        val frameHeight = (viewport - block + offset).coerceAtLeast(0)
        collapse.lastFrameHeight = frameHeight
        val frame = measurables.first { it.layoutId == FRAME_SLOT }.measure(Constraints.fixed(width, frameHeight))
        layout(width, viewport) {
            header.place(0, -offset)
            strip?.place(0, header.height + gap - offset)
            frame.place(0, block - offset)
        }
    }
}

/**
 * Cuánto puede desplazarse la cabecera, en px: `0..0` si el formulario ya tiene
 * su mínimo con la cabecera a la vista.
 *
 * Si no lo tiene, la cabecera y la franja pueden irse enteras ([headerBlockPx])
 * y el marco del editor llega a ocupar toda la columna. El inicio del recorrido
 * es 0 salvo en una ventana tan baja que el marco no quepa ni con el formulario
 * a cero: ahí la cabecera arranca ya desplazada lo justo para que las pestañas y
 * la barra de guardar se vean enteras.
 *
 * @param viewportPx alto de la columna.
 * @param headerBlockPx cabecera + franja + sus huecos.
 * @param chromePx lo que el marco gasta fuera del formulario.
 * @param minFormPx el alto mínimo garantizado del formulario.
 */
internal fun examEditorCollapseRange(viewportPx: Int, headerBlockPx: Int, chromePx: Int, minFormPx: Int): IntRange {
    val formAtRest = viewportPx - headerBlockPx - chromePx
    if (formAtRest >= minFormPx) return 0..0
    val first = (chromePx - (viewportPx - headerBlockPx)).coerceIn(0, headerBlockPx)
    return first..headerBlockPx
}

private const val HEADER_SLOT = "header"
private const val STRIP_SLOT = "strip"
private const val FRAME_SLOT = "frame"

/**
 * El estado del desplazamiento de la cabecera.
 *
 * [offset] y [chrome] son estado de Compose porque los lee la medida: cambiarlos
 * recoloca la columna. [range] y [lastFrameHeight] los escribe la medida y los
 * leen los gestos y la medición del formulario; no pintan nada por sí solos.
 */
@Stable
private class ExamEditorCollapse {
    var offset by mutableFloatStateOf(0f)
    var chrome by mutableIntStateOf(0)
    var range: IntRange = 0..0
    var lastFrameHeight: Int = -1

    /** El marco informa del alto de su formulario; el resto de su alto es lo que gasta fuera. */
    fun onFormViewportHeight(formHeight: Int) {
        if (lastFrameHeight < 0) return
        val measured = lastFrameHeight - formHeight
        if (measured > 0 && measured != chrome) chrome = measured
    }

    /**
     * Aplica [delta] px —negativo es hacia el final: la cabecera se va— dentro del
     * recorrido, y devuelve lo consumido con el mismo signo.
     */
    fun consume(delta: Float): Float {
        val low = range.first.toFloat()
        val high = range.last.toFloat()
        val current = offset.coerceIn(low, high)
        val target = (current - delta).coerceIn(low, high)
        offset = target
        return current - target
    }

    /** La rueda sobre la cabecera o la franja, y lo que sobre del formulario al subir. */
    val scrollable: ScrollableState = ScrollableState { delta -> consume(delta) }

    /** Al bajar sobre el formulario, primero se va la cabecera; después desplaza el formulario. */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero
    }
}
