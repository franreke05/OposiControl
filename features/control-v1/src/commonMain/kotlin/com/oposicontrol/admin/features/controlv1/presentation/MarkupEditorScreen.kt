package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.RazonadoMarkup
import com.oposicontrol.admin.domain.model.RazonadoMarkupMessages

internal const val MARKUP_INPUT_TAG = "control-markup-input"
internal const val MARKUP_PREVIEW_TAG = "control-markup-preview"
internal const val MARKUP_ISSUES_TAG = "control-markup-issues"

/** Ejemplo del editor: recorre toda la gramática. */
internal const val MARKUP_SAMPLE = "[align:center]**Razonamiento**[/align]\n" +
    "La respuesta correcta es la __B__ porque ++el plazo++ es de [color:red]diez días[/color] ~~hábiles~~.[p]\n" +
    "[-] Primer argumento\n[-] Segundo argumento con [size:lg]énfasis[/size]"

/**
 * Editor de marcado de los razonados: escribes, ves la vista previa EXACTA que verá la app y los problemas con su causa.
 * Solo produce un árbol tipado y nada se interpreta como HTML. El servidor repite el análisis al guardar (autoridad).
 */
@Composable
fun MarkupEditorScreen(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val result = remember(text) { RazonadoMarkup.parse(text) }
    ControlColumn(modifier) {
        FormSection(
            title = "Marcado de razonados",
            description = "Negrita **x**, cursiva __x__, subrayado ++x++, tachado ~~x~~ (solo el marcador DOBLE es sintaxis: un * o _ suelto es texto). " +
                "Color y tamaño salen de listas cerradas. Un salto de línea suelto es un espacio: para cortar párrafo, [p]. " +
                "Para escribir un marcador tal cual, antepón \\.",
            icon = AppIcon.EDIT,
            action = { SecondaryButton(text = "Insertar ejemplo", size = ButtonSize.COMPACT, onClick = { text = MARKUP_SAMPLE }) },
        ) {
            MarkupToolbar(onInsert = { snippet -> text += snippet })
            LabeledField(
                label = "Texto",
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.testTag(MARKUP_INPUT_TAG),
                placeholder = "Escribe o pega el razonamiento…",
                singleLine = false,
                minLines = 8,
                maxWidth = Dp.Unspecified,
                supportingText = "${text.length} / ${RazonadoMarkup.MAX_SOURCE} caracteres",
                isError = text.length > RazonadoMarkup.MAX_SOURCE,
            )
        }
        MarkupIssues(result)
        FormSection(title = "Vista previa", description = "Así lo verá quien abra el razonado. Si el texto no tiene ninguna etiqueta, se guarda tal cual.") {
            WorkspacePanel(modifier = Modifier.testTag(MARKUP_PREVIEW_TAG)) {
                when (result) {
                    is RazonadoMarkup.Result.Ok -> MarkupPreview(result.doc)
                    is RazonadoMarkup.Result.Rejected -> Text(
                        "Corrige los problemas de arriba para ver la vista previa.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
            }
        }
        if (result is RazonadoMarkup.Result.Ok && result.doc.blocks.isNotEmpty()) {
            FormSection(title = "Texto plano de respaldo", description = "Lo que ven las versiones de la app que no dibujan estilos.") {
                Text(
                    text = RazonadoMarkup.plain(result.doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.tokens.textSecondary,
                )
            }
        }
    }
}

/** Botones que añaden la plantilla de una etiqueta al final del texto. Color, tamaño y alineación salen de listas, nunca de texto libre. */
@Composable
private fun MarkupToolbar(onInsert: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SecondaryButton(text = "Negrita", size = ButtonSize.COMPACT, onClick = { onInsert("**texto**") })
            SecondaryButton(text = "Cursiva", size = ButtonSize.COMPACT, onClick = { onInsert("__texto__") })
            SecondaryButton(text = "Subrayado", size = ButtonSize.COMPACT, onClick = { onInsert("++texto++") })
            SecondaryButton(text = "Tachado", size = ButtonSize.COMPACT, onClick = { onInsert("~~texto~~") })
            SecondaryButton(text = "Párrafo", size = ButtonSize.COMPACT, onClick = { onInsert("[p]") })
            SecondaryButton(text = "Viñeta", size = ButtonSize.COMPACT, onClick = { onInsert("\n[-] ") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DropdownField(
                label = "Color",
                selectedLabel = "Insertar color…",
                options = RazonadoMarkup.COLORS.map { it to it },
                onSelect = { name -> if (name != null) onInsert("[color:$name]texto[/color]") },
            )
            DropdownField(
                label = "Tamaño",
                selectedLabel = "Insertar tamaño…",
                options = RazonadoMarkup.SIZES.map { it to it },
                onSelect = { size -> if (size != null) onInsert("[size:$size]texto[/size]") },
            )
            DropdownField(
                label = "Alineación",
                selectedLabel = "Insertar alineación…",
                options = RazonadoMarkup.ALIGNS.map { it to it },
                onSelect = { align -> if (align != null) onInsert("[align:$align]texto[/align]") },
            )
        }
    }
}

@Composable
internal fun MarkupIssues(result: RazonadoMarkup.Result) {
    val issues = (result as? RazonadoMarkup.Result.Rejected)?.issues ?: return
    Column(modifier = Modifier.testTag(MARKUP_ISSUES_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        WorkspaceNotice(WorkspaceNoticeTone.DANGER, "No se puede importar así", detail = "El servidor rechaza este texto mientras tenga estos problemas.")
        issues.forEach { issue ->
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.DANGER,
                title = RazonadoMarkupMessages.describe(issue.code),
                detail = "Cerca del carácter ${issue.at}",
            )
        }
    }
}

/** Pinta los bloques con el estilo del diseño. Sin HTML: se construye un `AnnotatedString` desde el árbol tipado. */
@Composable
fun MarkupPreview(doc: RazonadoMarkup.Doc, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val typography = MaterialTheme.typography
    val color: (String) -> Color = { name -> markupColor(name, tokens) }
    val size: (String) -> TextUnit = { token ->
        when (token) {
            "xs" -> typography.labelMedium.fontSize
            "sm" -> typography.bodySmall.fontSize
            "md" -> typography.bodyMedium.fontSize
            "lg" -> typography.titleSmall.fontSize
            else -> typography.titleMedium.fontSize
        }
    }
    if (doc.blocks.isEmpty()) {
        Text("Nada que mostrar todavía.", style = typography.bodyMedium, color = tokens.textSecondary, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        doc.blocks.forEach { block ->
            Text(
                text = markupAnnotated(block, color, size),
                style = typography.bodyMedium,
                color = tokens.textPrimary,
                textAlign = when (block.align) {
                    "center" -> TextAlign.Center
                    "right" -> TextAlign.End
                    "justify" -> TextAlign.Justify
                    else -> TextAlign.Start
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Los nombres de la paleta apuntan a tokens del tema; un `#RRGGBB` se pinta tal cual (el servidor ya lo validó). */
internal fun markupColor(name: String, tokens: com.oposicontrol.admin.core.ui.theme.GlassTokens): Color = when (name) {
    "black" -> tokens.textPrimary
    "gray" -> tokens.textSecondary
    "red" -> tokens.danger
    "orange", "yellow" -> tokens.warning
    "green" -> tokens.success
    "blue" -> tokens.info
    "purple" -> tokens.accent
    else -> hexColor(name) ?: tokens.textPrimary
}

internal fun hexColor(value: String): Color? {
    if (value.length != 7 || value[0] != '#') return null
    val rgb = value.substring(1).toLongOrNull(16) ?: return null
    return Color((0xFF000000L or rgb).toInt())
}

/** Convierte un bloque en `AnnotatedString`. Interno para probar que cada tramo conserva su estilo. */
internal fun markupAnnotated(block: RazonadoMarkup.Block, color: (String) -> Color, size: (String) -> TextUnit): AnnotatedString =
    buildAnnotatedString {
        if (block.kind == RazonadoMarkup.Kind.BULLET) append("•  ")
        block.spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.underline && span.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    span.underline -> TextDecoration.Underline
                    span.strike -> TextDecoration.LineThrough
                    else -> null
                },
                color = span.color?.let(color) ?: Color.Unspecified,
                fontSize = span.size?.let(size) ?: TextUnit.Unspecified,
            )
            withStyle(style) { append(span.text) }
        }
    }
