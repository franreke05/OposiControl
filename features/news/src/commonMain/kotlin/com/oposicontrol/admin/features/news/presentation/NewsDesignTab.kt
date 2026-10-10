package com.oposicontrol.admin.features.news.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FieldLabel
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WORKSPACE_COLOR_MILLIS
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.features.news.editor.NewsAlignment

/** Alto fijo de una fila de capa: no cambia al seleccionar, ocultar o bloquear. */
private val LAYER_ROW_HEIGHT = 44.dp
private val LAYER_ICON_SLOT = 28.dp
private val INSPECTOR_GAP = 16.dp
private const val HIDDEN_LAYER_ALPHA = 0.45f

/** Lo que la seccion y el scroll de `WorkspaceEditor` se quedan del ancho de columna: 2 x 20 de padding + 10 de canal. */
private const val EDITOR_INNER_INSET = 50

private val FONT_FAMILIES = listOf<Pair<String?, String>>("sans" to "Sans serif", "serif" to "Serif", "mono" to "Monoespaciada", "cursive" to "Cursiva")
private val FONT_WEIGHTS = listOf<Pair<String?, String>>("" to "Del tipo de capa", "normal" to "Normal", "medium" to "Media", "semibold" to "Seminegrita", "bold" to "Negrita")
private val LAYER_KINDS = listOf<Pair<String?, String>>("titulo" to "Título", "texto" to "Texto", "imagen" to "Imagen")

/**
 * Pestana «Diseño»: barra de herramientas, lienzo, lista de capas e inspector de la capa seleccionada. Con
 * anchura suficiente el inspector va al lado del lienzo (ver y ajustar son dos trabajos simultaneos); si no, debajo.
 */
@Composable
internal fun NewsDesignTab(state: NewsState, onIntent: (NewsIntent) -> Unit, contentWidth: Dp) {
    var zoom by remember(state.selectedId) { mutableStateOf(1f) }
    val innerWidth = contentWidth.value.toInt() - EDITOR_INNER_INSET
    val sideBySide = innerWidth >= NEWS_DESIGN_SIDE_BY_SIDE_MIN_WIDTH
    val canvasAreaWidth = if (sideBySide) innerWidth - NEWS_INSPECTOR_WIDTH - INSPECTOR_GAP.value.toInt() else innerWidth
    val editable = state.isEditable() && state.actionState !is UiAsync.Loading
    val selected = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }

    NewsDesignToolbar(state, selected, editable, onIntent)
    if (sideBySide) {
        Row(horizontalArrangement = Arrangement.spacedBy(INSPECTOR_GAP), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.width(canvasAreaWidth.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                NewsCanvasFrame(state, onIntent, canvasAreaWidth, zoom, { zoom = it }, interactive = true)
                NewsLayersList(state, editable, onIntent)
            }
            Column(modifier = Modifier.width(NEWS_INSPECTOR_WIDTH.dp)) {
                NewsLayerInspector(state, selected, editable, onIntent)
            }
        }
    } else {
        NewsCanvasFrame(state, onIntent, canvasAreaWidth, zoom, { zoom = it }, interactive = true)
        NewsLayersList(state, editable, onIntent)
        NewsLayerInspector(state, selected, editable, onIntent)
    }
}

/** Deshacer/rehacer, anadir capas, alinear y ordenar la seleccionada. Cada boton esta porque hace algo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsDesignToolbar(state: NewsState, selected: NewsLayerDraft?, editable: Boolean, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val imagePicker = rememberNewsImagePicker { onIntent(NewsIntent.AddLayerWithImage(it)) }
    val canMove = editable && selected != null && !selected.locked
    val lastIndex = state.editorLayers.lastIndex
    WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            SecondaryButton("Deshacer", { onIntent(NewsIntent.Undo) }, size = ButtonSize.COMPACT, enabled = editable && state.canUndo, icon = { AppIconImage(AppIcon.BACK, size = 14.dp) })
            SecondaryButton("Rehacer", { onIntent(NewsIntent.Redo) }, size = ButtonSize.COMPACT, enabled = editable && state.canRedo, icon = { AppIconImage(AppIcon.CHEVRON_RIGHT, size = 14.dp) })
            NewsToolbarDivider()
            SecondaryButton("Título", { onIntent(NewsIntent.AddTitleLayer) }, size = ButtonSize.COMPACT, enabled = editable, icon = { AppIconImage(AppIcon.ADD, size = 14.dp) })
            SecondaryButton("Texto", { onIntent(NewsIntent.AddLayer) }, size = ButtonSize.COMPACT, enabled = editable, icon = { AppIconImage(AppIcon.ADD, size = 14.dp) })
            SecondaryButton("Imagen", imagePicker, size = ButtonSize.COMPACT, enabled = editable, icon = { AppIconImage(AppIcon.UPLOAD, size = 14.dp) })
            NewsToolbarDivider()
            GhostButton("Ampliar", { onIntent(NewsIntent.ToggleCanvasFullscreen) }, size = ButtonSize.COMPACT, icon = { AppIconImage(AppIcon.VIEW, size = 14.dp) })
        }
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = if (selected == null) "Selecciona una capa para alinearla u ordenarla." else "Capa: ${selected.name.ifBlank { "Capa" }}",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            NewsAlignment.entries.forEach { alignment ->
                GhostButton(alignmentLabel(alignment), { selected?.let { onIntent(NewsIntent.AlignLayer(it.id, alignment)) } }, size = ButtonSize.COMPACT, enabled = canMove)
            }
            NewsToolbarDivider()
            GhostButton("Al frente", { selected?.let { onIntent(NewsIntent.BringLayerToFront(it.id)) } }, size = ButtonSize.COMPACT, enabled = canMove && selected.sortOrder < lastIndex)
            GhostButton("Adelante", { selected?.let { onIntent(NewsIntent.MoveLayerUp(it.id)) } }, size = ButtonSize.COMPACT, enabled = canMove && selected.sortOrder < lastIndex)
            GhostButton("Atrás", { selected?.let { onIntent(NewsIntent.MoveLayerDown(it.id)) } }, size = ButtonSize.COMPACT, enabled = canMove && selected.sortOrder > 0)
            GhostButton("Al fondo", { selected?.let { onIntent(NewsIntent.SendLayerToBack(it.id)) } }, size = ButtonSize.COMPACT, enabled = canMove && selected.sortOrder > 0)
            NewsToolbarDivider()
            GhostButton("Duplicar", { selected?.let { onIntent(NewsIntent.DuplicateLayer(it.id)) } }, size = ButtonSize.COMPACT, enabled = editable && selected != null)
            DangerOutlineButton("Quitar", { selected?.let { onIntent(NewsIntent.RemoveLayer(it.id)) } }, size = ButtonSize.COMPACT, enabled = canMove)
        }
    }
}

@Composable
private fun NewsToolbarDivider() {
    Box(Modifier.padding(horizontal = Spacing.xxs).width(1.dp).height(Sizing.buttonCompact).background(GlassTheme.tokens.border))
}

private fun alignmentLabel(alignment: NewsAlignment): String = when (alignment) {
    NewsAlignment.LEFT -> "Izquierda"
    NewsAlignment.CENTER_H -> "Centro H"
    NewsAlignment.RIGHT -> "Derecha"
    NewsAlignment.TOP -> "Arriba"
    NewsAlignment.CENTER_V -> "Centro V"
    NewsAlignment.BOTTOM -> "Abajo"
}

/** Las capas, de delante hacia atras: el indice de lo que hay en el lienzo. */
@Composable
private fun NewsLayersList(state: NewsState, editable: Boolean, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val layers = state.editorLayers.sortedByDescending { it.sortOrder }
    WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Capas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(
                text = if (layers.isEmpty()) "Ninguna" else "${layers.size} · de delante hacia atrás",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
        }
        if (layers.isEmpty()) {
            Text("Todavía no hay capas. Añade un título, un texto o una imagen desde la barra.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        layers.forEach { layer -> NewsLayerRow(layer, layer.id == state.selectedLayerId, editable, state.uploads[newsLayerUploadTarget(layer.id)], onIntent) }
    }
}

@Composable
private fun NewsLayerRow(layer: NewsLayerDraft, selected: Boolean, editable: Boolean, upload: NewsUploadState?, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> tokens.surfaceSelected
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceMuted
        },
        animationSpec = tween(WORKSPACE_COLOR_MILLIS),
    )
    val borderColor by animateColorAsState(targetValue = if (selected) tokens.accent.copy(alpha = 0.45f) else tokens.border, animationSpec = tween(WORKSPACE_COLOR_MILLIS))
    val contentAlpha = if (layer.visible) 1f else HIDDEN_LAYER_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LAYER_ROW_HEIGHT)
            .clip(Radius.md)
            .background(background)
            .border(1.dp, borderColor, Radius.md)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Seleccionar capa") { onIntent(NewsIntent.SelectLayer(layer.id)) }
            .padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LAYER_ICON_SLOT).clip(Radius.sm).background(tokens.surfaceSolid).alpha(contentAlpha), contentAlignment = Alignment.Center) {
            AppIconImage(layer.typeIcon(), size = 14.dp, tint = if (selected) tokens.accentInk else tokens.textSecondary)
        }
        Column(modifier = Modifier.weight(1f).alpha(contentAlpha), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(layer.name.ifBlank { "Capa" }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    upload != null && upload.failed -> "Subida fallida · reintenta en el inspector"
                    upload != null -> "Subiendo · ${(upload.progress * 100).toInt()} %"
                    else -> layer.rowSummary()
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (upload?.failed == true) tokens.dangerInk else tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (layer.locked) WorkspacePill(text = "Bloqueada", icon = AppIcon.BLOCKED)
        DesktopIconButton(
            label = if (layer.visible) "Ocultar capa" else "Mostrar capa",
            onClick = { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) },
            enabled = editable && !layer.locked,
            selected = !layer.visible,
            size = LAYER_ICON_SLOT,
        ) { AppIconImage(AppIcon.VIEW, size = 14.dp) }
        DesktopIconButton(
            label = if (layer.locked) "Desbloquear capa" else "Bloquear capa",
            onClick = { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) },
            enabled = editable,
            selected = layer.locked,
            size = LAYER_ICON_SLOT,
        ) { AppIconImage(AppIcon.BLOCKED, size = 14.dp) }
    }
}

/**
 * Propiedades de la capa seleccionada con valores EXACTOS: posicion, tamano, opacidad, rotacion, tipografia y
 * color. Una capa bloqueada ensena sus valores pero no los deja tocar hasta desbloquearla.
 */
@Composable
private fun NewsLayerInspector(state: NewsState, layer: NewsLayerDraft?, editable: Boolean, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Capa seleccionada", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
        if (layer == null) {
            Text("Ninguna. Haz clic en una capa del lienzo o de la lista para ver y ajustar sus propiedades.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            return@WorkspacePanel
        }
        val locked = layer.locked
        val canEdit = editable && !locked
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            WorkspacePill(text = newsLayerKindLabel(layer.kind))
            if (locked) WorkspacePill(text = "Bloqueada", icon = AppIcon.BLOCKED)
            if (!layer.visible) WorkspacePill(text = "Oculta")
        }
        if (locked) {
            Text("Bloqueada: no se mueve ni se edita. Desbloquéala para cambiarla.", style = MaterialTheme.typography.bodySmall, color = tokens.warningInk)
            SecondaryButton("Desbloquear", { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) }, size = ButtonSize.COMPACT, enabled = editable)
        }
        LabeledField("Nombre", layer.name, { onIntent(NewsIntent.UpdateLayerName(layer.id, it)) }, readOnly = !canEdit)
        DropdownField(
            label = "Tipo",
            selectedLabel = newsLayerKindLabel(layer.kind),
            options = LAYER_KINDS,
            onSelect = { it?.let { kind -> onIntent(NewsIntent.UpdateLayerKind(layer.id, kind)) } },
            enabled = canEdit,
        )
        if (layer.isImageLayer()) {
            val upload = state.uploads[newsLayerUploadTarget(layer.id)]
            FieldLabel(label = "Imagen")
            if (upload != null) {
                NewsUploadRow(upload, onIntent)
            } else {
                Text(if (layer.content.isBlank()) "Sin imagen todavía." else newsAssetName(layer.content), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
            val replace = rememberNewsImagePicker { onIntent(NewsIntent.UploadLayerImage(layer.id, it)) }
            SecondaryButton(if (layer.content.isBlank()) "Elegir imagen" else "Cambiar imagen", replace, size = ButtonSize.COMPACT, enabled = canEdit && (upload == null || upload.failed))
        } else {
            LabeledField("Texto", layer.content, { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) }, readOnly = !canEdit, singleLine = false, minLines = 3)
        }
        WorkspaceEqualRow(columns = 2, gap = Spacing.xs) {
            NewsNumberField("X", layer.x, canEdit) { onIntent(NewsIntent.UpdateLayerX(layer.id, it)) }
            NewsNumberField("Y", layer.y, canEdit) { onIntent(NewsIntent.UpdateLayerY(layer.id, it)) }
        }
        WorkspaceEqualRow(columns = 2, gap = Spacing.xs) {
            NewsNumberField("Ancho", layer.width, canEdit) { onIntent(NewsIntent.UpdateLayerWidth(layer.id, it)) }
            NewsNumberField("Alto", layer.height, canEdit) { onIntent(NewsIntent.UpdateLayerHeight(layer.id, it)) }
        }
        WorkspaceEqualRow(columns = 2, gap = Spacing.xs) {
            NewsNumberField("Opacidad %", layer.opacity, canEdit) { onIntent(NewsIntent.UpdateLayerOpacity(layer.id, it)) }
            NewsNumberField("Rotación °", layer.rotationDegrees, canEdit) { onIntent(NewsIntent.UpdateLayerRotation(layer.id, it.toIntOrNull() ?: layer.rotationDegrees)) }
        }
        if (!layer.isImageLayer()) {
            DropdownField(
                label = "Tipografía",
                selectedLabel = FONT_FAMILIES.firstOrNull { it.first == layer.fontFamily }?.second ?: "Sans serif",
                options = FONT_FAMILIES,
                onSelect = { it?.let { family -> onIntent(NewsIntent.UpdateLayerFontFamily(layer.id, family)) } },
                enabled = canEdit,
            )
            DropdownField(
                label = "Peso",
                selectedLabel = FONT_WEIGHTS.firstOrNull { it.first == layer.fontWeight }?.second ?: "Del tipo de capa",
                options = FONT_WEIGHTS,
                onSelect = { onIntent(NewsIntent.UpdateLayerFontWeight(layer.id, it.orEmpty())) },
                enabled = canEdit,
            )
            WorkspaceEqualRow(columns = 2, gap = Spacing.xs) {
                LabeledField(
                    label = "Tamaño (sp)",
                    value = if (layer.fontSizeSp == 0) "" else layer.fontSizeSp.toString(),
                    onValueChange = { onIntent(NewsIntent.UpdateLayerFontSize(layer.id, it.ifBlank { "0" })) },
                    placeholder = "${layer.effectiveFontSizeSp()} (del tipo)",
                    readOnly = !canEdit,
                    supportingText = "8 a 200. Vacío = del tipo de capa.",
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    LabeledField(
                        label = "Color (hex)",
                        value = layer.textColor,
                        onValueChange = { onIntent(NewsIntent.UpdateLayerTextColor(layer.id, it)) },
                        placeholder = "#FFFFFF",
                        readOnly = !canEdit,
                        isError = layer.textColor.isNotBlank() && !layer.textColor.isHexColor(),
                        supportingText = if (layer.textColor.isNotBlank() && !layer.textColor.isHexColor()) "Usa seis dígitos: #RRGGBB." else "Vacío = color del lienzo.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).clip(Radius.sm).background(newsLayerColor(layer.textColor, tokens.surfaceSolid)).border(1.dp, tokens.border, Radius.sm))
                        Text("Muestra", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            SecondaryButton(if (layer.visible) "Ocultar" else "Mostrar", { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) }, size = ButtonSize.COMPACT, enabled = canEdit)
            SecondaryButton(if (locked) "Desbloquear" else "Bloquear", { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) }, size = ButtonSize.COMPACT, enabled = editable)
        }
    }
}

@Composable
private fun NewsNumberField(label: String, value: Int, enabled: Boolean, onChange: (String) -> Unit) {
    LabeledField(label = label, value = value.toString(), onValueChange = onChange, readOnly = !enabled)
}

private fun String.isHexColor(): Boolean = trim().removePrefix("#").let { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }

/** Icono del tipo de capa: ninguna clave inventada, los mas cercanos del set. */
private fun NewsLayerDraft.typeIcon(): AppIcon = when {
    isImageLayer() -> AppIcon.ASSETS
    isTitleLayer() -> AppIcon.NEWS
    else -> AppIcon.NEWS_TYPES
}
