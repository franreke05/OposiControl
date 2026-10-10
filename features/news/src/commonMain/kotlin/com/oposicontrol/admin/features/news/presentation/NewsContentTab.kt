package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FieldLabel
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Alto de la vista previa del fondo y de la imagen de la tarjeta dentro de la ficha. */
private val ASSET_PREVIEW_HEIGHT = 180.dp

private val BACKGROUND_STYLES = listOf<Pair<String?, String>>("SKY" to "Cielo", "MINT" to "Menta", "GOLD" to "Dorado")

/**
 * Pestana «Contenido»: lo que el contrato guarda de una noticia (D4-03/D4-04). Ni enlaces, ni prioridad, ni
 * destacados: no existen en `noticia` y no se inventan aqui.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsContentTab(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val readOnly = state.isReadOnly() || state.actionState is UiAsync.Loading
    val titleTooLong = state.editorTitle.length > NEWS_TITLE_MAX

    FormSection(
        title = "Texto y clasificación",
        description = "Título, cuerpo y a quién va dirigida. Los tres primeros son obligatorios para guardar.",
        icon = AppIcon.EDIT,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Título",
                value = state.editorTitle,
                onValueChange = { onIntent(NewsIntent.UpdateTitle(it)) },
                placeholder = "Ej. Convocatoria TCAE 2026",
                required = true,
                readOnly = readOnly,
                isError = titleTooLong,
                supportingText = "${state.editorTitle.length} de $NEWS_TITLE_MAX caracteres. También es el texto de la capa de título.",
            )
            WorkspaceEqualRow(columns = 2, gap = Spacing.sm) {
                NewsCatalogField("Especialidad", state.editorSpecialty, state.specialties, enabled = !readOnly) { onIntent(NewsIntent.UpdateSpecialty(it)) }
                NewsCatalogField("Tipo de noticia", state.editorType, state.newsTypes, enabled = !readOnly) { onIntent(NewsIntent.UpdateType(it)) }
            }
            if (state.specialties is UiAsync.Error || state.newsTypes is UiAsync.Error) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "No se pudieron leer los catálogos",
                    detail = "Sin especialidades o tipos no se puede clasificar la noticia. Reintenta con «Actualizar».",
                )
                SecondaryButton(text = "Reintentar catálogos", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.Refresh) })
            }
            LabeledField(
                label = "Cuerpo",
                value = state.editorText,
                onValueChange = { onIntent(NewsIntent.UpdateText(it)) },
                placeholder = "Texto de la noticia tal como se leerá en OposiBot.",
                readOnly = readOnly,
                singleLine = false,
                minLines = 8,
                maxWidth = Sizing.inputDescriptionMaxWidth,
                supportingText = "${state.editorText.length} caracteres · texto plano. El cuerpo y las capas del lienzo se editan por separado.",
            )
        }
    }

    FormSection(
        title = "Fondo e imagen",
        description = "La plantilla de color pinta el lienzo cuando no hay imagen de fondo. La imagen de la tarjeta es la miniatura del listado.",
        icon = AppIcon.ASSETS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            DropdownField(
                label = "Plantilla de color",
                selectedLabel = BACKGROUND_STYLES.firstOrNull { it.first == state.editorBackgroundType.toNewsBackgroundStyle() }?.second.orEmpty(),
                options = BACKGROUND_STYLES,
                onSelect = { onIntent(NewsIntent.UpdateBackgroundType(it ?: "SKY")) },
                enabled = !readOnly,
            )
            NewsAssetField(
                label = "Imagen de fondo",
                path = state.editorBackgroundPath,
                url = state.editorBackgroundUrl,
                upload = state.uploads[NEWS_UPLOAD_BACKGROUND],
                emptyText = "Sin imagen de fondo: se usa la plantilla de color.",
                readOnly = readOnly,
                onPick = { onIntent(NewsIntent.UploadBackground(it)) },
                onClear = { onIntent(NewsIntent.UpdateBackgroundPath("")) },
                onIntent = onIntent,
            )
            NewsAssetField(
                label = "Imagen de la tarjeta",
                path = state.editorImagePath,
                url = state.editorImageUrl,
                upload = state.uploads[NEWS_UPLOAD_IMAGE],
                emptyText = "Sin imagen de tarjeta: el listado enseña el icono de noticias.",
                readOnly = readOnly,
                onPick = { onIntent(NewsIntent.UploadImage(it)) },
                onClear = { onIntent(NewsIntent.UpdateImagePath("")) },
                onIntent = onIntent,
            )
            Text(
                text = "PNG, JPG, WebP o GIF de hasta 10 MB. Se suben al elegirlas y se comprimen en el servidor.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun NewsCatalogField(label: String, value: String, catalog: UiAsync<List<NewsCatalogOption>>, enabled: Boolean, onChange: (String) -> Unit) {
    val options = (catalog as? UiAsync.Success)?.value.orEmpty()
        .filter { it.active || it.id == value }
        .map { it.id to if (it.active) it.name else "${it.name} (inactiva)" }
    val selected = (catalog as? UiAsync.Success)?.value?.firstOrNull { it.id == value }
    DropdownField(
        label = label,
        selectedLabel = catalogName(value, catalog).takeIf { value.isNotBlank() || catalog !is UiAsync.Success }.orEmpty(),
        options = options,
        onSelect = { it?.let(onChange) },
        required = true,
        enabled = enabled && catalog is UiAsync.Success && options.isNotEmpty(),
        placeholder = "Selecciona",
        supportingText = when {
            catalog is UiAsync.Loading -> "Cargando el catálogo…"
            catalog is UiAsync.Error -> "Catálogo no disponible."
            selected != null && !selected.active -> "Inactiva: el servidor no admite guardar con ella. Elige otra."
            value.isNotBlank() && selected == null -> "No está en el catálogo: elige una del listado."
            else -> null
        },
        isError = selected?.active == false || (value.isNotBlank() && selected == null && catalog is UiAsync.Success),
    )
}

/** Imagen de fondo o de tarjeta: vista previa, subida en marcha o fallida, y los dos unicos botones que hacen algo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsAssetField(
    label: String,
    path: String,
    url: String,
    upload: NewsUploadState?,
    emptyText: String,
    readOnly: Boolean,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onIntent: (NewsIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val picker = rememberNewsImagePicker(onPick)
    Column(modifier = Modifier.widthIn(max = Sizing.inputDescriptionMaxWidth), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldLabel(label = label)
        when {
            upload != null -> NewsUploadRow(upload, onIntent)
            path.isNotBlank() && url.isNotBlank() -> {
                NewsAssetImage(url, Modifier.fillMaxWidth().height(ASSET_PREVIEW_HEIGHT).clip(Radius.card))
                Text(text = newsAssetName(path), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
            path.isNotBlank() -> Text(
                text = "Enlazada: ${newsAssetName(path)} (sin vista previa disponible).",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            else -> Text(text = emptyText, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        if (!readOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SecondaryButton(
                    text = if (path.isBlank()) "Elegir imagen" else "Cambiar imagen",
                    size = ButtonSize.COMPACT,
                    onClick = picker,
                    enabled = upload == null || upload.failed,
                )
                if (path.isNotBlank()) GhostButton(text = "Quitar", size = ButtonSize.COMPACT, onClick = onClear)
            }
        }
    }
}
