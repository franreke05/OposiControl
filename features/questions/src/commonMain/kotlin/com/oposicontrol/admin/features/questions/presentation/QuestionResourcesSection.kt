package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ButtonVariant
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

/**
 * Los recursos de una pregunta inmersiva.
 *
 * **Sólo Inmersiva.** Los recursos pertenecen a la pregunta, nunca al examen, y
 * sólo este tipo puede apoyarse en ellos: quien llama a esta sección ya lo ha
 * comprobado con [questionShowsResources], y [QuestionsState.canAttachResources]
 * añade lo que falta —que la pregunta EXISTA—. Ninguna de las dos reimplementa la
 * regla del servidor: si llega un rechazo, el texto que se lee es el suyo.
 *
 * **Nunca se enseña `storagePath`.** Es una ruta interna del almacenamiento y no
 * le dice nada a quien administra; lo que importa es si el recurso se puede abrir.
 * Lo decide [questionResourceRows], que también resuelve el caso del recurso de
 * texto —se sostiene con su propio cuerpo y no necesita dirección pública—.
 *
 * **P0-4 cerrado.** «Añadir recurso» estaba `enabled = false, onClick = {}` con una
 * nota al lado porque toda la ruta de escritura faltaba en el cliente. Ahora existe:
 * alta por referencia (`texto`, `enlace`), subida de archivo (`imagen`, `audio`,
 * `video`, `pdf`) y archivado por fila, cada uno con su estado de carga y su error.
 */
@Composable
internal fun QuestionResourcesSection(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    detail: QuestionDetail?,
) {
    val tokens = GlassTheme.tokens
    // Mismo orden en las dos listas (`questionResourceRows` ordena por `sortOrder`),
    // así que la fila y su recurso se emparejan por posición. Emparejarlos por
    // título —como se hacía para elegir el icono— fallaba en cuanto dos recursos se
    // llamaban igual, y con «Recurso sin título» eso es lo normal.
    val resources = detail?.resources.orEmpty().sortedBy { it.sortOrder }
    val rows = questionResourceRows(resources)
    val canAttach = state.canAttachResources()
    FormSection(
        title = "Recursos",
        description = "Material de apoyo de esta pregunta inmersiva.",
        icon = AppIcon.RESOURCES,
        iconRole = SemanticRole.INFO,
        action = if (canAttach) {
            {
                SecondaryButton(
                    text = "Añadir recurso",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(QuestionsIntent.RequestAddResource) },
                    icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
                )
            }
        } else {
            null
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (rows.isEmpty()) {
                Text(
                    text = if (detail == null) {
                        "Los recursos aparecerán aquí cuando la pregunta exista."
                    } else {
                        QUESTION_RESOURCES_EMPTY
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                )
            } else {
                resources.zip(rows).forEach { (resource, row) ->
                    QuestionResourceRowView(
                        resource = resource,
                        row = row,
                        canDelete = canAttach,
                        busy = state.resourceDeleteState is UiAsync.Loading,
                        onIntent = onIntent,
                    )
                }
            }
            // Un fallo de alta o de archivado se dice aquí, con el mensaje del
            // servidor: es lo que distingue un 422 «esta pregunta no admite
            // recursos» de un «no se ha podido conectar».
            (state.resourceSaveState as? UiAsync.Error)?.let { failure ->
                ErrorStateCard(
                    message = failure.error.humanMessage(),
                    title = "No hemos podido añadir el recurso",
                )
            }
            (state.resourceDeleteState as? UiAsync.Error)?.let { failure ->
                ErrorStateCard(
                    message = failure.error.humanMessage(),
                    title = "No hemos podido archivar el recurso",
                )
            }
        }
    }

    if (state.showResourceDialog) {
        QuestionResourceDialog(state, onIntent)
    }
    state.deleteResourceId?.let { resourceId ->
        val title = resources.firstOrNull { it.id == resourceId }?.title?.trim()?.ifBlank { null }
        ConfirmDialog(
            // Archivar y no borrar, que es lo que hace la ruta: la fila se queda y
            // deja de listarse. Y hoy no hay forma de desarchivarla, así que se dice.
            title = "Archivar ${title ?: "este recurso"}",
            message = QUESTION_RESOURCE_ARCHIVE_WARNING,
            confirmLabel = "Archivar",
            onConfirm = { onIntent(QuestionsIntent.ConfirmDeleteResource) },
            onDismiss = { onIntent(QuestionsIntent.DismissDeleteResourceConfirm) },
        )
    }
}

@Composable
private fun QuestionResourceRowView(
    resource: QuestionResource,
    row: QuestionResourceRow,
    canDelete: Boolean,
    busy: Boolean,
    onIntent: (QuestionsIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, Radius.md)
            .padding(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SemanticIconContainer(
            icon = questionResourceIcon(resource.type),
            role = SemanticRole.INFO,
            size = Sizing.iconButtonSmall,
            iconSize = 16.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
        // Accesible o no, con palabra: el estado no va sólo en color.
        Text(
            text = row.accessLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (row.accessible) tokens.textPrimary else tokens.textSecondary,
        )
        if (canDelete) {
            DesktopIconButton(
                label = "Archivar ${row.title}",
                onClick = { onIntent(QuestionsIntent.RequestDeleteResource(resource.id)) },
                variant = ButtonVariant.DANGER,
                enabled = !busy,
                size = Sizing.iconButtonSmall,
            ) { AppIconImage(AppIcon.DELETE, size = 14.dp) }
        }
    }
}

/**
 * El alta de un recurso.
 *
 * **Un tipo enseña SÓLO sus campos.** Es la regla de la base puesta en la forma del
 * formulario: `texto` no ofrece archivo ni URL, `enlace` no ofrece archivo ni
 * cuerpo, y los cuatro de archivo no ofrecen URL. Cambiar de tipo vacía lo que ese
 * tipo no admite ([QuestionResourceForm.withKind]), así que no hay estado desde el
 * que se pueda mandar una combinación que el `CHECK` rechazaría.
 */
@Composable
private fun QuestionResourceDialog(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val form = state.resourceForm
    val saving = state.resourceSaveState is UiAsync.Loading
    val picker = rememberQuestionResourceFilePicker(form.kind) { file ->
        onIntent(QuestionsIntent.PickResourceFile(file))
    }
    Dialog(onDismissRequest = { if (!saving) onIntent(QuestionsIntent.CancelResourceDialog) }) {
        GlassSurface(
            modifier = Modifier.width(QUESTION_RESOURCE_DIALOG_WIDTH),
            shape = Radius.lg,
            tint = tokens.surfaceSolid,
            strokeColor = tokens.border,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = "Añadir recurso",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                QuestionSelect(
                    selectedLabel = questionResourceKindLabel(form.kind),
                    options = QuestionResourceKind.entries.map {
                        QuestionFilterOption(it, questionResourceKindLabel(it))
                    },
                    onSelect = { onIntent(QuestionsIntent.UpdateResourceKind(it)) },
                    accessibilityLabel = "Tipo de recurso",
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuestionTextField(
                    label = "Título",
                    value = form.title,
                    onValueChange = { onIntent(QuestionsIntent.UpdateResourceTitle(it)) },
                    placeholder = "Cómo se llama este material",
                    enabled = !saving,
                )
                QuestionTextField(
                    label = "Descripción",
                    value = form.description,
                    onValueChange = { onIntent(QuestionsIntent.UpdateResourceDescription(it)) },
                    placeholder = "Opcional",
                    enabled = !saving,
                )
                when {
                    form.kind == QuestionResourceKind.ENLACE -> QuestionTextField(
                        label = "Enlace",
                        value = form.externalUrl,
                        onValueChange = { onIntent(QuestionsIntent.UpdateResourceUrl(it)) },
                        placeholder = "https://…",
                        supportingText = "Un enlace no sube nada: se guarda la dirección tal cual.",
                        enabled = !saving,
                    )
                    form.kind == QuestionResourceKind.TEXTO -> QuestionTextField(
                        label = "Contenido",
                        value = form.textBody,
                        onValueChange = { onIntent(QuestionsIntent.UpdateResourceText(it)) },
                        placeholder = "El texto que acompaña a la pregunta",
                        singleLine = false,
                        minHeight = QUESTION_RESOURCE_TEXT_HEIGHT,
                        supportingText = "El texto vive en la propia ficha; no hay archivo que abrir.",
                        enabled = !saving,
                    )
                    else -> QuestionResourceFileField(
                        form = form,
                        picker = picker,
                        saving = saving,
                        onIntent = onIntent,
                    )
                }
                (state.resourceSaveState as? UiAsync.Error)?.let { failure ->
                    Text(
                        text = failure.error.humanMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.dangerInk,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                ) {
                    SecondaryButton(
                        text = "Cancelar",
                        onClick = { onIntent(QuestionsIntent.CancelResourceDialog) },
                        enabled = !saving,
                    )
                    PrimaryButton(
                        text = "Añadir",
                        onClick = { onIntent(QuestionsIntent.ConfirmAddResource) },
                        // `loading` bloquea el clic: una subida de 40 MB tarda, y dos
                        // pulsaciones serían dos altas con dos ids distintos.
                        loading = saving,
                        enabled = form.validationError() == null,
                    )
                }
            }
        }
    }
}

/** El archivo elegido, o el botón para elegirlo. Sin selector, se dice por qué. */
@Composable
private fun QuestionResourceFileField(
    form: QuestionResourceForm,
    picker: QuestionResourceFilePicker,
    saving: Boolean,
    onIntent: (QuestionsIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = "Archivo",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        if (!picker.available) {
            Text(
                text = QUESTION_RESOURCE_PICKER_UNAVAILABLE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            return@Column
        }
        val file = form.file
        if (file == null) {
            SecondaryButton(
                text = "Elegir archivo",
                size = ButtonSize.COMPACT,
                onClick = { picker.open() },
                enabled = !saving,
                icon = { AppIconImage(AppIcon.UPLOAD, size = 13.dp) },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // Ancho EXACTO y no un `weight`: el diálogo mide
                // [QUESTION_RESOURCE_DIALOG_WIDTH] fijos, así que lo que le queda al
                // nombre del fichero junto al botón es una cuenta, no un reparto.
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(QUESTION_RESOURCE_FILENAME_WIDTH),
                )
                SecondaryButton(
                    text = "Cambiar",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(QuestionsIntent.ClearResourceFile) },
                    enabled = !saving,
                )
            }
        }
        Text(
            text = QUESTION_RESOURCE_UPLOAD_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

internal fun questionResourceIcon(type: QuestionResourceType): AppIcon = when (type) {
    QuestionResourceType.IMAGE -> AppIcon.ASSETS
    QuestionResourceType.AUDIO -> AppIcon.EVENTS
    QuestionResourceType.VIDEO -> AppIcon.VIEW
    QuestionResourceType.TEXT -> AppIcon.NEWS_TYPES
    QuestionResourceType.DOCUMENT -> AppIcon.REPORTS
}

/**
 * Los seis tipos, con el nombre que se lee.
 *
 * Aparte de `questionResourceTypeLabel`, que traduce el tipo de LECTURA: aquél
 * colapsa `pdf` y `enlace` en «Documento», que como etiqueta de un recurso ya
 * guardado vale y como opción de un desplegable no —habría dos «Documento»—.
 */
internal fun questionResourceKindLabel(kind: QuestionResourceKind): String = when (kind) {
    QuestionResourceKind.IMAGEN -> "Imagen"
    QuestionResourceKind.AUDIO -> "Audio"
    QuestionResourceKind.VIDEO -> "Vídeo"
    QuestionResourceKind.PDF -> "PDF"
    QuestionResourceKind.TEXTO -> "Texto"
    QuestionResourceKind.ENLACE -> "Enlace"
}

/** Archivar un recurso no se puede deshacer desde esta consola, y se dice antes. */
internal const val QUESTION_RESOURCE_ARCHIVE_WARNING: String =
    "El recurso dejará de verse en la pregunta. No se borra de la base, pero hoy no hay forma de recuperarlo desde aquí."

internal const val QUESTION_RESOURCE_UPLOAD_NOTE: String =
    "El archivo se sube al almacenamiento privado y la aplicación lo abre con un enlace firmado. Máximo 50 MB."

internal const val QUESTION_RESOURCE_PICKER_UNAVAILABLE_NOTE: String =
    "Subir archivos se hace desde la aplicación de escritorio. Aquí sí puedes añadir recursos de texto o de enlace."

/** Mismo ancho que el diálogo de vincular a examen: son dos formularios hermanos. */
private val QUESTION_RESOURCE_DIALOG_WIDTH = 400.dp

/**
 * Lo que le queda al nombre del fichero: 400 de diálogo − 20+20 de margen − 8 de
 * hueco − 92 del botón «Cambiar» = 260, y se deja en 240 para que un nombre largo
 * se corte con puntos suspensivos antes de tocar el botón.
 */
private val QUESTION_RESOURCE_FILENAME_WIDTH = 240.dp

/** Alto del área de texto: cuatro líneas, lo que cabe sin que el diálogo desplace. */
private val QUESTION_RESOURCE_TEXT_HEIGHT = 96.dp
