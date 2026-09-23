package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * Etapa 2: el examen destino y el archivo.
 *
 * **El examen NO es exclusivo de Ráfaga.** La pantalla anterior metía el selector
 * dentro de la tarjeta de tipo elegida, lo que daba a entender que sólo un tipo se
 * vinculaba a examen; en realidad `examsForSelectedType` filtra el catálogo por el
 * tipo elegido y los tres lo usan igual. Aquí vive fuera de las tarjetas, en su
 * propio paso, porque es una decisión del lote.
 *
 * **No hay zona de arrastre.** Arrastrar y soltar no está implementado, así que no
 * se dibuja un recuadro que invite a soltar un archivo y no responda: hay un botón
 * que abre el diálogo del sistema, que es lo que de verdad funciona.
 */
@Composable
internal fun ImportFileStep(
    state: ImportState,
    onIntent: (ImportIntent) -> Unit,
    onPickPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val exams = state.examsForSelectedType
    val selectedExam = exams.firstOrNull { it.id == state.selectedExamId }
    val hasFile = state.pickedPdfName.isNotBlank()
    val uploading = state.actionState is UiAsync.Loading

    FormSection(
        title = "Archivo y destino",
        description = "El lote entero se importa en un único examen.",
        modifier = modifier,
        icon = AppIcon.IMPORT,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            ImportDropdown(
                label = "Examen destino",
                selectedLabel = selectedExam?.let(::importExamLabel)
                    ?: if (exams.isEmpty()) "Sin exámenes de este tipo" else "Elige un examen",
                options = exams.map { it.id to importExamLabel(it) },
                onSelect = { onIntent(ImportIntent.SelectExam(it)) },
                enabled = exams.isNotEmpty(),
            )
            if (exams.isEmpty()) {
                Text(
                    text = "No hay ningún examen de tipo ${importTypeLabel(state.selectedType)} al que importar. " +
                        "Crea uno en Exámenes antes de subir el PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }

            // Zona de archivo: opaca, con el estado del archivo elegido dentro.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.card)
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, Radius.card)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SemanticIconContainer(
                    icon = AppIcon.UPLOAD,
                    role = SemanticRole.INFO,
                    size = Sizing.iconButton,
                    iconSize = Sizing.iconLarge,
                )
                if (hasFile) {
                    Text(
                        text = state.pickedPdfName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = importFileSizeLabel(state.pickedPdfSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                } else {
                    Text(
                        text = "Selecciona el PDF con las preguntas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textPrimary,
                    )
                }
                Text(
                    text = IMPORT_PDF_REQUIREMENT,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SecondaryButton(
                        text = if (hasFile) "Cambiar PDF" else "Seleccionar PDF",
                        onClick = onPickPdf,
                        size = ButtonSize.COMPACT,
                        icon = { AppIconImage(AppIcon.IMPORT, size = 13.dp) },
                    )
                    PrimaryButton(
                        text = "Subir y analizar",
                        onClick = { onIntent(ImportIntent.UploadPdf) },
                        size = ButtonSize.COMPACT,
                        enabled = hasFile && state.selectedExamId != null && !uploading,
                        loading = uploading,
                    )
                }
                // Botón desactivado con su motivo al lado: no se deja adivinar.
                if (!hasFile || state.selectedExamId == null) {
                    Text(
                        text = when {
                            state.selectedExamId == null && !hasFile -> "Elige un examen destino y un PDF."
                            state.selectedExamId == null -> "Elige el examen destino."
                            else -> "Selecciona el PDF."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }

            // Plantilla: no existe ninguna. Botón apagado con su motivo, no un
            // enlace que no lleva a ningún sitio.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                GhostButton(
                    text = "Descargar plantilla",
                    onClick = {},
                    enabled = false,
                    size = ButtonSize.COMPACT,
                    icon = { AppIconImage(AppIcon.DOWNLOAD, size = 13.dp) },
                )
                Text(
                    text = IMPORT_TEMPLATE_BLOCKED,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }

            if (state.actionState is UiAsync.Error) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    AppIconImage(AppIcon.WARNING, size = 14.dp, tint = tokens.danger)
                    Text(
                        text = (state.actionState as UiAsync.Error).error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.dangerInk,
                    )
                }
            }
        }
    }
}

/** Un examen por su código y título humanos. Nunca por su identificador interno. */
internal fun importExamLabel(exam: Exam): String {
    val code = exam.code.trim()
    return if (code.isBlank()) exam.title else "$code · ${exam.title}"
}

/** El tamaño del archivo, redondeado. Sin ruta: la ruta local no se enseña. */
internal fun importFileSizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "Tamaño desconocido"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
