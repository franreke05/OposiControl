package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme

// Medidas del panel de capas. Todas fijas: una fila de capa mide siempre lo
// mismo, esté seleccionada, oculta o bloqueada, porque una lista que cambia de
// altura al interactuar obliga a volver a buscar dónde estabas.
private val LayerRowHeight = 48.dp
private val LayerRowShape = RoundedCornerShape(12.dp)
private val LayerChipShape = RoundedCornerShape(10.dp)
private val LayerSlot = 32.dp
private val LayerHandleSlot = 24.dp
private val LayerIconSize = 16.dp
private val LayerEdge = 8.dp

/**
 * Ancho exacto del bloque de acciones: ojo (32) + 4 + candado (32) + 4 + asa (24).
 *
 * El texto de la fila reserva ese hueco con un padding, no con `weight`: así el
 * nombre de la capa se recorta con puntos suspensivos antes de llegar a los
 * iconos en vez de pasar por debajo de ellos.
 */
private val LayerActionsWidth = LayerSlot + 4.dp + LayerSlot + 4.dp + LayerHandleSlot
private val LayerTextStartInset = LayerEdge + LayerSlot + 12.dp
private val LayerTextEndInset = LayerActionsWidth + LayerEdge + LayerEdge

/** Dos campos numéricos por fila; el ancho es fijo para que la rejilla no baile. */
private val LayerNumberFieldWidth = 128.dp

/** Una capa oculta se atenúa: sigue ahí, pero no pinta en el lienzo. */
private const val HiddenLayerAlpha = 0.45f

@Composable
internal fun NewsLayersEditor(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Capas de la composición",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Gestiona el orden y la visibilidad de los elementos.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }

        NewsLayerAddBar(onIntent)
        NewsLayerList(state, onIntent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsLayerAddBar(onIntent: (NewsIntent) -> Unit) {
    val imagePicker = rememberNewsImagePicker { onIntent(NewsIntent.AddLayerWithImage(it)) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton("+ Título", { onIntent(NewsIntent.AddTitleLayer) }, size = ButtonSize.COMPACT)
        SecondaryButton("+ Texto", { onIntent(NewsIntent.AddLayer) }, size = ButtonSize.COMPACT)
        SecondaryButton("+ Imagen", imagePicker, size = ButtonSize.COMPACT)
    }
}

@Composable
private fun NewsLayerList(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val layers = state.editorLayers.sortedByDescending { it.sortOrder }
    if (layers.isEmpty()) {
        Text(
            "Todavía no hay capas. Añade un título, un texto o una imagen para empezar.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (layers.size == 1) "1 capa" else "${layers.size} capas",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
            Text(
                "De delante hacia atrás",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            layers.forEach { layer ->
                NewsLayerRow(layer, layer.id == state.selectedLayerId, onIntent)
            }
        }
    }
}

/**
 * Fila de capa: tipo, nombre, resumen y los tres controles de siempre.
 *
 * Se compone dentro de un [Box] y no de un [Row] para que cada bloque tenga un
 * hueco propio medido: el icono de tipo a la izquierda, el texto ocupando lo que
 * queda entre los dos márgenes reservados, y las acciones ancladas a la derecha.
 * El asa es un indicador de orden, no un agarre: aquí no hay arrastre, el orden
 * se cambia con «Adelante» y «Atrás».
 */
@Composable
private fun NewsLayerRow(layer: NewsLayerDraft, selected: Boolean, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val contentAlpha = if (layer.visible) 1f else HiddenLayerAlpha
    Box(
        Modifier
            .fillMaxWidth()
            .height(LayerRowHeight)
            .clip(LayerRowShape)
            .background(
                when {
                    selected -> tokens.surfaceSelected
                    hovered -> tokens.surfaceHover
                    else -> tokens.surfaceMuted
                },
            )
            .border(1.dp, if (selected) tokens.accentInk else tokens.border, LayerRowShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Seleccionar capa",
            ) { onIntent(NewsIntent.SelectLayer(layer.id)) },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = LayerEdge)
                .size(LayerSlot)
                .clip(LayerChipShape)
                .background(tokens.surfaceSolid)
                .alpha(contentAlpha),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(
                layer.typeIcon(),
                size = LayerIconSize,
                tint = if (selected) tokens.accentInk else tokens.textSecondary,
            )
        }

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = LayerTextStartInset, end = LayerTextEndInset)
                .alpha(contentAlpha),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                layer.name.ifBlank { "Capa" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                layer.rowSummary(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = LayerEdge),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopIconButton(
                label = if (layer.visible) "Ocultar capa" else "Mostrar capa",
                onClick = { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) },
                enabled = !layer.locked,
                size = LayerSlot,
            ) {
                AppIconImage(AppIcon.VIEW, Modifier.alpha(contentAlpha), size = LayerIconSize)
            }
            DesktopIconButton(
                label = if (layer.locked) "Desbloquear capa" else "Bloquear capa",
                onClick = { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) },
                selected = layer.locked,
                size = LayerSlot,
            ) {
                AppIconImage(AppIcon.BLOCKED, size = LayerIconSize)
            }
            Box(Modifier.size(LayerHandleSlot), contentAlignment = Alignment.Center) {
                AppIconImage(AppIcon.SORT, size = LayerIconSize, tint = tokens.textDisabled)
            }
        }
    }
}

/**
 * Propiedades de la capa seleccionada.
 *
 * Vive aquí, con la lista, porque comparte con ella el vocabulario de la capa
 * —resumen de fila, tipo, estado de bloqueo—, pero la pinta el inspector: en el
 * taller es una columna propia, al lado del lienzo, no un bloque al final de la
 * lista al que hay que bajar cada vez que se mueve un título.
 */
@Composable
internal fun NewsLayerProperties(layer: NewsLayerDraft, lastIndex: Int, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                layer.name.ifBlank { "Capa" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                layer.rowSummary(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        NewsLayerActions(layer, lastIndex, onIntent)

        if (layer.locked) {
            Text(
                "Capa bloqueada. Desbloquéala para editar su contenido y su posición.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.warning,
            )
            return@Column
        }

        NewsField("Nombre de capa", layer.name, { onIntent(NewsIntent.UpdateLayerName(layer.id, it)) })
        if (layer.isImageLayer()) {
            Text(
                newsAssetName(layer.content),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            val replace = rememberNewsImagePicker { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) }
            SecondaryButton("Cambiar imagen de capa", replace, size = ButtonSize.COMPACT)
        } else {
            NewsField("Texto de capa", layer.content, { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) }, lines = 3)
        }

        // Los seis números dejan de ser seis cajas sueltas: van en parejas, que es
        // como se piensan —X con Y, ancho con alto, opacidad con rotación—.
        NewsLayerNumberRow(
            "Posición X", layer.x.toString(), { onIntent(NewsIntent.UpdateLayerX(layer.id, it)) },
            "Posición Y", layer.y.toString(), { onIntent(NewsIntent.UpdateLayerY(layer.id, it)) },
        )
        NewsLayerNumberRow(
            "Ancho", layer.width.toString(), { onIntent(NewsIntent.UpdateLayerWidth(layer.id, it)) },
            "Alto", layer.height.toString(), { onIntent(NewsIntent.UpdateLayerHeight(layer.id, it)) },
        )
        NewsLayerNumberRow(
            "Opacidad %", layer.opacity.toString(), { onIntent(NewsIntent.UpdateLayerOpacity(layer.id, it)) },
            "Rotación °", layer.rotationDegrees.toString(),
            { onIntent(NewsIntent.UpdateLayerRotation(layer.id, it.toIntOrNull() ?: layer.rotationDegrees)) },
        )

        if (!layer.isImageLayer()) {
            NewsChoice(
                "Tipografía",
                when (layer.fontFamily) {
                    "serif" -> "Serif"
                    "mono" -> "Monoespaciada"
                    "cursive" -> "Cursiva"
                    else -> "Sans serif"
                },
                listOf("sans" to "Sans serif", "serif" to "Serif", "mono" to "Monoespaciada", "cursive" to "Cursiva"),
            ) { onIntent(NewsIntent.UpdateLayerFontFamily(layer.id, it)) }
            // «Del tipo de capa» es una opción explícita, no la ausencia de
            // elección: sin ella, fijar un peso una vez sería irreversible y
            // no habría forma de devolver la capa a su aspecto por defecto.
            NewsChoice(
                "Peso",
                when (layer.fontWeight) {
                    "bold" -> "Negrita"
                    "semibold" -> "Seminegrita"
                    "medium" -> "Media"
                    "normal" -> "Normal"
                    else -> "Del tipo de capa"
                },
                listOf(
                    "" to "Del tipo de capa",
                    "normal" to "Normal",
                    "medium" to "Media",
                    "semibold" to "Seminegrita",
                    "bold" to "Negrita",
                ),
            ) { onIntent(NewsIntent.UpdateLayerFontWeight(layer.id, it)) }
            NewsField(
                "Tamaño (px, 0 = del tipo de capa)",
                layer.fontSizeSp.toString(),
                { onIntent(NewsIntent.UpdateLayerFontSize(layer.id, it)) },
            )
            NewsField(
                "Color del texto (hexadecimal)",
                layer.textColor,
                { onIntent(NewsIntent.UpdateLayerTextColor(layer.id, it)) },
            )
        }
    }
}

/**
 * Acciones de la capa seleccionada, agrupadas por trabajo: estado, orden, copia
 * y borrado. Los grupos se separan 12dp y los botones de un mismo grupo 4dp, que
 * es lo que hace que se lean como tres bloques y no como siete botones en fila.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsLayerActions(layer: NewsLayerDraft, lastIndex: Int, onIntent: (NewsIntent) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(
                if (layer.locked) "Desbloquear" else "Bloquear",
                { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) },
                size = ButtonSize.COMPACT,
            )
            SecondaryButton(
                if (layer.visible) "Ocultar" else "Mostrar",
                { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) },
                size = ButtonSize.COMPACT,
                enabled = !layer.locked,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(
                "Adelante",
                { onIntent(NewsIntent.MoveLayerUp(layer.id)) },
                size = ButtonSize.COMPACT,
                enabled = !layer.locked && layer.sortOrder < lastIndex,
            )
            SecondaryButton(
                "Atrás",
                { onIntent(NewsIntent.MoveLayerDown(layer.id)) },
                size = ButtonSize.COMPACT,
                enabled = !layer.locked && layer.sortOrder > 0,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GhostButton(
                "Centrar",
                { onIntent(NewsIntent.CenterLayer(layer.id)) },
                size = ButtonSize.COMPACT,
                enabled = !layer.locked,
            )
            GhostButton(
                "Duplicar",
                { onIntent(NewsIntent.DuplicateLayer(layer.id)) },
                size = ButtonSize.COMPACT,
            )
        }
        DangerOutlineButton(
            "Quitar capa",
            { onIntent(NewsIntent.RemoveLayer(layer.id)) },
            size = ButtonSize.COMPACT,
            enabled = !layer.locked,
        )
    }
}

@Composable
private fun NewsLayerNumberRow(
    leftLabel: String,
    leftValue: String,
    onLeft: (String) -> Unit,
    rightLabel: String,
    rightValue: String,
    onRight: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(LayerNumberFieldWidth)) { NewsField(leftLabel, leftValue, onLeft) }
        Column(Modifier.width(LayerNumberFieldWidth)) { NewsField(rightLabel, rightValue, onRight) }
    }
}

private fun NewsLayerDraft.isImageLayer(): Boolean = kind.trim().lowercase() in listOf("imagen", "image")

private fun NewsLayerDraft.isTitleLayer(): Boolean = kind.trim().lowercase() in listOf("titulo", "title")

/**
 * Icono del tipo de capa.
 *
 * El set no tiene glifo de tipografía ni de alineación, así que se usan los más
 * cercanos que existen: el periódico para el titular, las líneas de la lista
 * para el texto corrido y la imagen para el archivo. Ninguna clave inventada.
 */
private fun NewsLayerDraft.typeIcon(): AppIcon = when {
    isImageLayer() -> AppIcon.ASSETS
    isTitleLayer() -> AppIcon.NEWS
    else -> AppIcon.NEWS_TYPES
}

private fun NewsLayerDraft.kindLabel(): String = when {
    isImageLayer() -> "Imagen"
    isTitleLayer() -> "Título"
    else -> "Texto"
}

private fun NewsLayerDraft.rowSummary(): String {
    val detail = when {
        content.isBlank() -> ""
        isImageLayer() -> newsAssetName(content)
        else -> content.replace('\n', ' ').trim()
    }
    return if (detail.isBlank()) "${kindLabel()} · sin contenido" else "${kindLabel()} · $detail"
}
