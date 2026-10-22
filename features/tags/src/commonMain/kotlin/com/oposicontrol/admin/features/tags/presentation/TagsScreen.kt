package com.oposicontrol.admin.features.tags.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerButton
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Tag

/**
 * Etiquetas (temas), con el diseño del panel: cifras arriba, catálogo de cristal a la izquierda y ficha a la derecha,
 * como Especialidades. Vuelve al menú de Contenido por decisión del propietario (2026-10-22); se siguen pudiendo
 * asignar y crear desde el editor de cada pregunta.
 */
@Composable
fun TagsScreen(
    viewModel: TagsViewModel,
) {
    val state by viewModel.state.collectAsState()
    TagsContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
internal fun TagsContent(state: TagsState, onIntent: (TagsIntent) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(TagsSizing.columnGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TagsSizing.columnGap)) {
            TagsStatTile("Etiquetas", state.totalCount.toString(), AppIcon.FILTER, SemanticRole.INFO)
            TagsStatTile("Activas", state.activeCount.toString(), AppIcon.CHECK, SemanticRole.SUCCESS)
            TagsStatTile("Desactivadas", (state.totalCount - state.activeCount).toString(), AppIcon.BLOCKED, SemanticRole.WARNING)
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(TagsSizing.columnGap),
        ) {
            TagsSection(
                icon = AppIcon.FILTER,
                title = "Catálogo de etiquetas",
                subtitle = "Los temas que clasifican las preguntas.",
                modifier = Modifier.width(TagsSizing.catalogWidth).fillMaxHeight(),
            ) {
                GlassSearchBar(
                    value = state.searchQuery,
                    onValueChange = { onIntent(TagsIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o slug…",
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    text = "Nueva etiqueta",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(TagsIntent.CreateNew) },
                )
                AsyncStatePane(state.items, emptyMessage = "No hay etiquetas para este filtro.") { items ->
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items.forEach { tag ->
                            TagCatalogRow(tag, selected = tag.id == state.selectedId, onClick = { onIntent(TagsIntent.Select(tag.id)) })
                        }
                    }
                }
            }
            TagsSection(
                icon = AppIcon.EDIT,
                title = if (state.selectedId == null) "Nueva etiqueta" else "Detalle de etiqueta",
                subtitle = "El nombre es el que se escribe en ETIQUETA: del PDF de importación.",
                modifier = Modifier.width(TagsSizing.editorWidth),
            ) {
                LabeledField(
                    label = "Nombre",
                    required = true,
                    value = state.editorName,
                    onValueChange = { onIntent(TagsIntent.UpdateName(it)) },
                    placeholder = "Ej. Constitución",
                    maxWidth = TagsSizing.fieldWidth,
                )
                LabeledField(
                    label = "Slug (opcional, se genera del nombre)",
                    value = state.editorSlug,
                    onValueChange = { onIntent(TagsIntent.UpdateSlug(it)) },
                    placeholder = "constitucion",
                    maxWidth = TagsSizing.fieldWidth,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PrimaryButton(
                        text = "Guardar etiqueta",
                        enabled = state.editorName.isNotBlank(),
                        onClick = { onIntent(TagsIntent.Save) },
                    )
                    if (state.selectedId != null) {
                        DangerButton(text = "Eliminar etiqueta", onClick = { confirmDelete = true })
                    }
                }
                FormFeedback(state.actionState, successText = "Etiqueta sincronizada.")
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar etiqueta",
            message = "Se eliminará la etiqueta seleccionada. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                onIntent(TagsIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun TagsSection(
    icon: AppIcon,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        modifier = modifier,
        contentPadding = PaddingValues(TagsSizing.sectionPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            SemanticIconContainer(icon = icon, role = SemanticRole.INFO, size = TagsSizing.iconChip, iconSize = 18.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        content()
    }
}

@Composable
private fun TagsStatTile(label: String, value: String, icon: AppIcon, role: SemanticRole) {
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        modifier = Modifier.width(TagsSizing.statWidth).height(TagsSizing.statHeight),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            SemanticIconContainer(icon = icon, role = role, size = TagsSizing.iconChip, iconSize = 18.dp)
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            }
        }
    }
}

@Composable
private fun TagCatalogRow(tag: Tag, selected: Boolean, onClick: () -> Unit) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TagsSizing.rowHeight)
            .clip(Radius.card)
            .background(if (selected) tokens.surfaceSelected else tokens.surfaceMuted)
            .border(1.dp, if (selected) tokens.accent.copy(alpha = 0.45f) else tokens.border, Radius.card)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) tokens.accent else tokens.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(TagsSizing.rowTextWidth)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tag.slug.ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(
                status = if (tag.active) "Activa" else "Desactivada",
                tone = if (tag.active) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            )
        }
    }
}

private object TagsSizing {
    val columnGap = 16.dp
    val catalogWidth = 400.dp
    val editorWidth = 560.dp
    val fieldWidth = 496.dp
    val sectionPadding = 20.dp
    val iconChip = 36.dp
    val statWidth = 200.dp
    val statHeight = 76.dp
    val rowHeight = 56.dp
    val rowTextWidth = 230.dp
}
