package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import kotlin.math.roundToInt

private const val NewsPreviewWidth = 320
private const val NewsPreviewHeight = 200

@Composable
fun NewsScreen(
    viewModel: NewsViewModel,
) {
    val state by viewModel.state.collectAsState()
    NewsContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
fun NewsContent(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    CrudSplitLayout(
        listPane = { NewsListPane(state = state, onIntent = onIntent) },
        detailPane = { NewsEditorPane(state = state, onIntent = onIntent) },
    )
}

@Composable
private fun NewsListPane(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassActionButton(
            text = "Nueva noticia",
            modifier = Modifier.width(152.dp),
            onClick = { onIntent(NewsIntent.CreateNew) },
        )
        FilterBar(
            searchValue = state.searchQuery,
            onSearchChange = { onIntent(NewsIntent.UpdateSearch(it)) },
            placeholder = "Buscar noticias",
            filters = {
                NewsSmallFilterField(
                    value = state.statusFilter,
                    label = "Estado",
                    onValueChange = { onIntent(NewsIntent.UpdateStatusFilter(it)) },
                    width = 96,
                )
                NewsSmallFilterField(
                    value = state.specialtyFilter,
                    label = "Esp. id",
                    onValueChange = { onIntent(NewsIntent.UpdateSpecialtyFilter(it)) },
                    width = 118,
                )
                NewsSmallFilterField(
                    value = state.typeFilter,
                    label = "Tipo",
                    onValueChange = { onIntent(NewsIntent.UpdateTypeFilter(it)) },
                    width = 84,
                )
            },
        )
        NewsSectionList(
            title = "Publicadas",
            state = state.publishedNews,
            selectedId = state.selectedId,
            emptyMessage = "No hay noticias publicadas para este filtro.",
            isPublishedSection = true,
            isDirty = state.isDirty,
            onIntent = onIntent,
        )
        NewsSectionList(
            title = "Guardadas/locales",
            state = state.localNews,
            selectedId = state.selectedId,
            emptyMessage = "No hay borradores ni noticias locales.",
            isPublishedSection = false,
            isDirty = state.isDirty,
            onIntent = onIntent,
        )
    }
}

@Composable
private fun NewsSectionList(
    title: String,
    state: UiAsync<List<NewsItem>>,
    selectedId: String?,
    emptyMessage: String,
    isPublishedSection: Boolean,
    isDirty: Boolean = false,
    onIntent: (NewsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
            )
            AsyncStatePane(state, emptyMessage = emptyMessage) { items ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        NewsListCard(
                            item = item,
                            selected = item.id == selectedId,
                            isPublishedSection = isPublishedSection,
                            isDirty = isDirty && item.id != selectedId,
                            onClick = { onIntent(NewsIntent.Select(item.id)) },
                            onSaveLocal = { onIntent(NewsIntent.SaveDraftFor(item.id)) },
                            onPublish = { onIntent(NewsIntent.PublishFor(item.id)) },
                            onArchive = { onIntent(NewsIntent.ArchiveFor(item.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsSmallFilterField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    width: Int,
) {
    GlassTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        modifier = Modifier.width(width.dp),
    )
}

@Composable
private fun NewsListCard(
    item: NewsItem,
    selected: Boolean,
    isPublishedSection: Boolean,
    isDirty: Boolean = false,
    onClick: () -> Unit,
    onSaveLocal: () -> Unit,
    onPublish: () -> Unit,
    onArchive: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f) else GlassTheme.tokens.glassStroke,
        shadowElevation = if (selected) 12.dp else 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NewsMiniPreview(item = item)
            Column(
                modifier = Modifier.width(242.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.width(136.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title.ifBlank { "Noticia sin título" },
                            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.sp),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.text.ifBlank { "Sin cuerpo editorial" },
                            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusChip(item.status.ifBlank { "draft" }, modifier = Modifier.width(96.dp))
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NewsMetaChip("esp ${item.specialty.ifBlank { "-" }}")
                    NewsMetaChip("tipo ${item.type.ifBlank { "-" }}")
                    NewsMetaChip("fondo ${item.backgroundType.ifBlank { "A" }.toTemplateStyle()}")
                    NewsMetaChip("${item.layers.size} capas")
                }
                Text(
                    text = if (item.publishFrom.isBlank()) "Sin fecha programada" else "Publicar desde ${item.publishFrom}",
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsCompactButton("Editar", 74, onClick = onClick)
                    if (isPublishedSection) {
                        NewsCompactButton("Archivar", 88, onClick = onArchive)
                    } else {
                        NewsCompactButton("Guardar", 86, enabled = !isDirty, onClick = onSaveLocal)
                        NewsCompactButton("Publicar", 90, enabled = !isDirty, onClick = onPublish)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsMiniPreview(item: NewsItem) {
    val palette = templatePalette(item.backgroundType)
    Box(
        modifier = Modifier
            .width(74.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(palette.background))
            .border(1.dp, palette.border, RoundedCornerShape(14.dp)),
    ) {
        if (item.backgroundPath.isNullOrBlank()) NewsTemplateArtwork(palette)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .width(42.dp)
                .height(5.dp)
                .background(palette.accent, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun NewsEditorPane(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NewsVisualEditor(state = state, onIntent = onIntent)
        NewsArticleForm(state = state, onIntent = onIntent)
        NewsLayerPanel(state = state, onIntent = onIntent)
        NewsValidationPanel(state = state)
    }
}

@Composable
private fun NewsVisualEditor(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    FormSection(
        title = if (state.selectedId == null) "Nueva noticia" else "Noticia ${state.selectedId}",
        description = "Campos alineados con la tabla noticia: título, tipo_fondo, especialidad_id, texto, tipo_noticia_id, imagen_path, estado y publicar_desde.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NewsCanvasPreview(state = state, onIntent = onIntent)
            val outOfBounds = state.editorLayers.filter { it.visible && (it.x > state.editorCanvasWidth || it.y > state.editorCanvasHeight) }
            if (outOfBounds.isNotEmpty()) {
                Text(
                    text = "${outOfBounds.size} capa(s) fuera del área visible",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NewsMetaChip("estado ${state.editorStatus.ifBlank { "draft" }}")
                NewsMetaChip("fondo ${state.editorBackgroundType.ifBlank { "A" }.toTemplateStyle()}")
                NewsMetaChip("especialidad_id ${state.editorSpecialty.ifBlank { "-" }}")
                NewsMetaChip("tipo_noticia_id ${state.editorType.ifBlank { "-" }}")
            }
            NewsPrimaryActions(state = state, onIntent = onIntent)
            NewsActionState(state)
        }
    }
}

@Composable
private fun NewsPrimaryActions(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsCompactButton("Nueva", 86) { onIntent(NewsIntent.CreateNew) }
            NewsCompactButton("Guardar local", 124) { onIntent(NewsIntent.SaveDraft) }
            NewsCompactButton("Publicar", 100) { onIntent(NewsIntent.Publish) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsCompactButton("Programar", 108) { onIntent(NewsIntent.Schedule) }
            NewsCompactButton("Archivar", 98, enabled = state.selectedId != null) { onIntent(NewsIntent.Archive) }
            NewsCompactButton("Borrar", 82, enabled = state.selectedId != null) { confirmDelete = true }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar noticia",
            message = "Se eliminará la noticia seleccionada. ¿Continuar?",
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
private fun NewsArticleForm(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    FormSection(
        title = "Datos de publicación",
        description = "Usa IDs numericos cuando trabajes contra Supabase real: especialidad_id y tipo_noticia_id.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NewsFieldLabel("Título")
            GlassTextField(
                value = state.editorTitle,
                onValueChange = { onIntent(NewsIntent.UpdateTitle(it)) },
                placeholder = "Título visible de la noticia",
                modifier = Modifier.fillMaxWidth(),
            )
            NewsFieldLabel("Texto")
            GlassTextField(
                value = state.editorText,
                onValueChange = { onIntent(NewsIntent.UpdateText(it)) },
                placeholder = "Cuerpo de la noticia",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.width(158.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsFieldLabel("Especialidad id")
                    GlassTextField(
                        value = state.editorSpecialty,
                        onValueChange = { onIntent(NewsIntent.UpdateSpecialty(it)) },
                        placeholder = "1",
                    )
                }
                Column(modifier = Modifier.width(158.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsFieldLabel("Tipo noticia id")
                    GlassTextField(
                        value = state.editorType,
                        onValueChange = { onIntent(NewsIntent.UpdateType(it)) },
                        placeholder = "1",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.width(158.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsFieldLabel("Tipo fondo")
                    GlassTextField(
                        value = state.editorBackgroundType,
                        onValueChange = { onIntent(NewsIntent.UpdateBackgroundType(it)) },
                        placeholder = "A, B, C",
                    )
                }
                Column(modifier = Modifier.width(158.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsFieldLabel("Estado")
                    GlassTextField(
                        value = state.editorStatus,
                        onValueChange = { onIntent(NewsIntent.UpdateStatus(it)) },
                        placeholder = "draft",
                    )
                }
            }
            NewsFieldLabel("Imagen path")
            GlassTextField(
                value = state.editorImagePath,
                onValueChange = { onIntent(NewsIntent.UpdateImagePath(it)) },
                placeholder = "storage/path o URL pública",
                modifier = Modifier.fillMaxWidth(),
            )
            NewsFieldLabel("Publicar desde")
            GlassTextField(
                value = state.editorPublishFrom,
                onValueChange = { onIntent(NewsIntent.UpdatePublishFrom(it)) },
                placeholder = "2026-05-11T09:00:00Z",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NewsLayerPanel(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    val selectedLayer = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }
    FormSection(
        title = "Capas visuales",
        description = "El editor conserva orden, posición, tamano, visibilidad y opacidad para construir una noticia visual.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NewsCompactButton("Título", 82) { onIntent(NewsIntent.AddTitleLayer) }
                NewsCompactButton("Texto", 76) { onIntent(NewsIntent.AddLayer) }
                NewsCompactButton("Imagen atras", 122, enabled = state.editorImagePath.isNotBlank()) { onIntent(NewsIntent.AddLayerWithImage(state.editorImagePath)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NewsCompactButton("Fondo", 84) { onIntent(NewsIntent.SelectBackground) }
                NewsCompactButton("Validar", 86) { onIntent(NewsIntent.ValidateDraft) }
            }
            NewsLayerStrip(state = state, onIntent = onIntent)
            if (selectedLayer == null) {
                NewsCanvasInspector(state = state, onIntent = onIntent)
            } else {
                NewsLayerInspector(layer = selectedLayer, onIntent = onIntent)
            }
        }
    }
}

@Composable
private fun NewsLayerStrip(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    if (state.editorLayers.isEmpty()) {
        Text(
            text = "Sin capas. Crea una capa para destacar título, cuerpo o llamada a la acción.",
            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(state.editorLayers.sortedByDescending { it.sortOrder }, key = { it.id }) { layer ->
            NewsLayerTile(
                layer = layer,
                selected = layer.id == state.selectedLayerId,
                onClick = { onIntent(NewsIntent.SelectLayer(layer.id)) },
            )
        }
    }
}

@Composable
private fun NewsLayerTile(
    layer: NewsLayerDraft,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .width(138.dp)
            .height(64.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.22f) else GlassTheme.tokens.glassFill,
        strokeColor = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = layer.name.ifBlank { "Capa" },
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${layer.kind.toLayerLabel()} | ${layer.width}x${layer.height}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsCanvasInspector(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("${state.editorCanvasWidth}x${state.editorCanvasHeight}")
            StatusChip(state.editorBackgroundType.ifBlank { "A" }.toTemplateStyle())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = state.editorCanvasWidth.toString(),
                onValueChange = { onIntent(NewsIntent.UpdateCanvasWidth(it)) },
                placeholder = "Ancho",
                modifier = Modifier.width(126.dp),
            )
            GlassTextField(
                value = state.editorCanvasHeight.toString(),
                onValueChange = { onIntent(NewsIntent.UpdateCanvasHeight(it)) },
                placeholder = "Alto",
                modifier = Modifier.width(126.dp),
            )
        }
        NewsFieldLabel("Imagen de fondo")
        GlassTextField(
            value = state.editorBackgroundPath,
            onValueChange = { onIntent(NewsIntent.UpdateBackgroundPath(it)) },
            placeholder = "content://, storage/path o URL pública",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NewsLayerInspector(
    layer: NewsLayerDraft,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(layer.kind.toLayerLabel())
            StatusChip(if (layer.visible) "visible" else "oculta")
            StatusChip(if (layer.locked) "bloqueada" else "editable")
            StatusChip("orden ${layer.sortOrder}")
        }
        NewsFieldLabel("Nombre de capa")
        GlassTextField(
            value = layer.name,
            onValueChange = { onIntent(NewsIntent.UpdateLayerName(layer.id, it)) },
            placeholder = "Título, cuerpo, CTA...",
            modifier = Modifier.fillMaxWidth(),
        )
        NewsFieldLabel("Tipo")
        GlassTextField(
            value = layer.kind,
            onValueChange = { onIntent(NewsIntent.UpdateLayerKind(layer.id, it)) },
            placeholder = "título, texto, imagen, cta",
            modifier = Modifier.fillMaxWidth(),
        )
        NewsFieldLabel("Contenido")
        GlassTextField(
            value = layer.content,
            onValueChange = { onIntent(NewsIntent.UpdateLayerContent(layer.id, it)) },
            placeholder = "Texto o ruta de imagen",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NewsNumericField("X", layer.x.toString(), 74) { onIntent(NewsIntent.UpdateLayerX(layer.id, it)) }
            NewsNumericField("Y", layer.y.toString(), 74) { onIntent(NewsIntent.UpdateLayerY(layer.id, it)) }
            NewsNumericField("W", layer.width.toString(), 74) { onIntent(NewsIntent.UpdateLayerWidth(layer.id, it)) }
            NewsNumericField("H", layer.height.toString(), 74) { onIntent(NewsIntent.UpdateLayerHeight(layer.id, it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NewsNumericField("Opacidad", layer.opacity.toString(), 104) { onIntent(NewsIntent.UpdateLayerOpacity(layer.id, it)) }
            NewsNumericField("Rotacion", layer.rotationDegrees.toString(), 104) {
                onIntent(NewsIntent.UpdateLayerRotation(layer.id, it.toIntOrNull() ?: layer.rotationDegrees))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsCompactButton("Subir", 72) { onIntent(NewsIntent.MoveLayerUp(layer.id)) }
            NewsCompactButton("Bajar", 72) { onIntent(NewsIntent.MoveLayerDown(layer.id)) }
            NewsCompactButton("Centro", 78) { onIntent(NewsIntent.CenterLayer(layer.id)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsCompactButton(if (layer.visible) "Ocultar" else "Mostrar", 86) { onIntent(NewsIntent.ToggleLayerVisible(layer.id)) }
            NewsCompactButton(if (layer.locked) "Desbloq." else "Bloquear", 90) { onIntent(NewsIntent.ToggleLayerLocked(layer.id)) }
            NewsCompactButton("Quitar", 76) { onIntent(NewsIntent.RemoveLayer(layer.id)) }
        }
    }
}

@Composable
private fun NewsNumericField(
    label: String,
    value: String,
    width: Int,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.width(width.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NewsFieldLabel(label)
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "0",
        )
    }
}

@Composable
private fun NewsValidationPanel(state: NewsState) {
    val report = state.validationReport ?: return
    FormSection(
        title = "Revisión editorial",
        description = "Validaciones antes de guardar, programar o publicar.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (report.canSave) "guardable" else "incompleta")
                StatusChip(if (report.canSchedule) "programable" else "sin programar")
                StatusChip(if (report.canPublish) "publicable" else "bloqueada")
            }
            (report.errors + report.warnings).take(6).forEach { issue ->
                Text(
                    text = issue.message,
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    color = if (issue.severity.name == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NewsCanvasPreview(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    val palette = templatePalette(state.editorBackgroundType)
    val canvasWidth = state.editorCanvasWidth.coerceAtLeast(NewsPreviewWidth)
    val canvasHeight = state.editorCanvasHeight.coerceAtLeast(NewsPreviewHeight)
    val scaleX = NewsPreviewWidth.toFloat() / canvasWidth
    val scaleY = NewsPreviewHeight.toFloat() / canvasHeight
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .width(NewsPreviewWidth.dp)
            .height(NewsPreviewHeight.dp)
            .clip(shape)
            .background(Brush.linearGradient(palette.background))
            .border(1.dp, palette.border, shape)
            .clickable { onIntent(NewsIntent.SelectBackground) },
    ) {
        if (state.editorBackgroundPath.isNotBlank()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.26f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Fondo: ${state.editorBackgroundPath.substringAfterLast('/').ifBlank { state.editorBackgroundPath }.take(34)}",
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.36f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.editorBackgroundPath.isBlank()) NewsTemplateArtwork(palette)
        state.editorLayers
            .sortedBy { it.sortOrder }
            .filter { it.visible }
            .forEach { layer ->
                NewsLayerPreview(
                    layer = layer,
                    selected = layer.id == state.selectedLayerId,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    onClick = { onIntent(NewsIntent.SelectLayer(layer.id)) },
                )
            }
        if (state.editorLayers.none { it.visible }) {
            Text(
                text = "Sin capas visibles",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun BoxScope.NewsTemplateArtwork(palette: NewsTemplatePalette) {
    Canvas(modifier = Modifier.matchParentSize()) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0xE6101722),
                    Color(0x99121A26),
                    Color.Transparent,
                ),
            ),
        )
        val wave = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.78f)
            cubicTo(
                size.width * 0.32f,
                size.height * 0.12f,
                size.width * 0.70f,
                size.height * 0.88f,
                size.width * 1.02f,
                size.height * 0.24f,
            )
        }
        val secondWave = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.62f)
            cubicTo(
                size.width * 0.28f,
                size.height * 0.06f,
                size.width * 0.60f,
                size.height * 0.72f,
                size.width * 0.94f,
                size.height * 0.10f,
            )
        }
        drawPath(
            path = wave,
            brush = Brush.linearGradient(listOf(palette.accent.copy(alpha = 0.50f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.026f, cap = StrokeCap.Round),
        )
        drawPath(
            path = secondWave,
            brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.008f, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun NewsLayerPreview(
    layer: NewsLayerDraft,
    selected: Boolean,
    scaleX: Float,
    scaleY: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val fill = when {
        selected -> Color.White.copy(alpha = 0.14f)
        layer.kind.isImageKind() -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .offset((layer.x * scaleX).roundToInt().dp, (layer.y * scaleY).roundToInt().dp)
            .width((layer.width * scaleX).roundToInt().coerceAtLeast(1).dp)
            .height((layer.height * scaleY).roundToInt().coerceAtLeast(1).dp)
            .background(fill, shape)
            .border(if (selected) 1.dp else 0.dp, if (selected) Color.White.copy(alpha = 0.75f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = (10 * scaleX).dp, vertical = (7 * scaleY).dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (layer.kind.isImageKind()) {
            Text(
                text = layer.content.substringAfterLast('/').ifBlank { "imagen" },
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                color = Color.White.copy(alpha = 0.84f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = layer.content.ifBlank { layer.name.ifBlank { "Texto" } },
                color = layer.textColor.toNewsColor(Color.White).copy(alpha = layer.opacity.coerceIn(20, 100) / 100f),
                fontFamily = layer.fontFamily.toNewsFontFamily(),
                fontSize = if (layer.kind.isTitleKind()) 25.sp * scaleY else 16.sp * scaleY,
                lineHeight = if (layer.kind.isTitleKind()) 29.sp * scaleY else 20.sp * scaleY,
                fontWeight = if (layer.kind.isTitleKind()) FontWeight.Bold else FontWeight.Medium,
                maxLines = if (layer.kind.isTitleKind()) 3 else 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsMetaChip(label: String) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewsCompactButton(
    text: String,
    width: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong
    val textColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    GlassSurface(
        modifier = Modifier
            .width(width.dp)
            .height(42.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tint = tint,
        strokeColor = tint.copy(alpha = 0.52f),
        shadowElevation = 8.dp,
    ) {
        Box(
            modifier = Modifier
                .width(width.dp)
                .height(42.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewsActionState(state: NewsState) {
    FormFeedback(
        state = state.actionState,
        loadingText = "Guardando noticia...",
        successText = if (state.selectedId?.startsWith("local-") == true) {
            "Borrador guardado localmente."
        } else {
            "Noticia sincronizada."
        },
    )
}

@Composable
private fun NewsFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class NewsTemplatePalette(
    val background: List<Color>,
    val border: Color,
    val accent: Color,
)

private fun templatePalette(value: String): NewsTemplatePalette {
    return when (value.toTemplateStyle()) {
        "MINT" -> NewsTemplatePalette(
            background = listOf(Color(0xFF25322F), Color(0xFF151E22), Color(0xFF0E141D)),
            border = Color(0xFF29423B),
            accent = Color(0xFF6EF3D5),
        )
        "GOLD" -> NewsTemplatePalette(
            background = listOf(Color(0xFF342D25), Color(0xFF211A17), Color(0xFF10131A)),
            border = Color(0xFF4A3825),
            accent = Color(0xFFE9C985),
        )
        else -> NewsTemplatePalette(
            background = listOf(Color(0xFF1C2535), Color(0xFF141B26), Color(0xFF0E141D)),
            border = Color(0xFF26364A),
            accent = Color(0xFF89D5FF),
        )
    }
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
        equals("cta", ignoreCase = true) -> "cta"
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

@Preview
@Composable
private fun NewsScreenPreview() {
    AdminTheme {
        NewsContent(
            state = NewsState(
                news = UiAsync.Success(
                    listOf(
                        NewsItem(
                            id = "news-1",
                            title = "Convocatoria TCAE",
                            specialty = "TCAE",
                            type = "Convocatoria",
                            backgroundType = "A",
                            backgroundPath = "news/backgrounds/cover.png",
                            status = "scheduled",
                            publishFrom = "2026-04-20 08:00",
                            text = "Texto",
                        ),
                    ),
                ),
                selectedId = "news-1",
                editorTitle = "Convocatoria TCAE",
                editorBackgroundType = "A",
                editorBackgroundPath = "news/backgrounds/cover.png",
                editorSpecialty = "TCAE",
                editorType = "Convocatoria",
                editorText = "Texto largo de la noticia.",
                editorStatus = "scheduled",
                editorPublishFrom = "2026-04-20 08:00",
                editorLayers = listOf(
                    NewsLayerDraft(
                        id = "layer-1",
                        name = "Título",
                        kind = "titulo",
                        content = "Convocatoria TCAE",
                        x = 24,
                        y = 30,
                        width = 280,
                        height = 64,
                        opacity = 100,
                        visible = true,
                        sortOrder = 0,
                    ),
                    NewsLayerDraft(
                        id = "layer-2",
                        name = "Cuerpo",
                        kind = "texto",
                        content = "Plazas abiertas con calendario pendiente de publicación oficial.",
                        x = 48,
                        y = 132,
                        width = 420,
                        height = 94,
                        opacity = 88,
                        visible = true,
                        sortOrder = 1,
                    ),
                ),
            ),
            onIntent = {},
        )
    }
}
