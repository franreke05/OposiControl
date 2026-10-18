package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.TextUnit
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.MarkupAlign
import com.oposicontrol.admin.domain.model.MarkupBlock
import com.oposicontrol.admin.domain.model.MarkupColor
import com.oposicontrol.admin.domain.model.MarkupInline
import com.oposicontrol.admin.domain.model.MarkupSize
import com.oposicontrol.admin.domain.model.MarkupStyle
import com.oposicontrol.admin.domain.model.RazonadoMarkup

internal const val MARKUP_INPUT_TAG = "control-markup-input"
internal const val MARKUP_PREVIEW_TAG = "control-markup-preview"
internal const val MARKUP_ISSUES_TAG = "control-markup-issues"

/** El texto de ejemplo del editor: recorre toda la gramática. */
internal const val MARKUP_SAMPLE = "[align:center]**Razonamiento**[/align][p]" +
    "La respuesta correcta es la __B__ porque ++el plazo++ es de [color:red]diez días[/color] ~~hábiles~~.[p]" +
    "[-]Primer argumento\n[-]Segundo argumento con [size:large]énfasis[/size]"

/**
 * Editor de marcado de los razonados: escribes, ves el resultado EXACTO que verá la app y la lista de problemas. Solo
 * produce un árbol tipado: nada de lo escrito se interpreta como HTML, ni aquí ni al guardar.
 */
@Composable
fun MarkupEditorScreen(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val result = remember(text) { RazonadoMarkup.parse(text) }
    ControlColumn(modifier) {
        FormSection(
            title = "Marcado de razonados",
            description = "Negrita **x**, cursiva __x__, subrayado ++x++, tachado ~~x~~ (marcadores dobles: un * o _ suelto es texto). " +
                "Color [color:red]x[/color] y tamaño [size:large]x[/size] salen de listas cerradas. Párrafo [p], viñeta [-] al empezar línea, " +
                "alineación [align:center]x[/align]. Para escribir un marcador tal cual, antepón \\.",
            icon = AppIcon.EDIT,
            action = { SecondaryButton(text = "Insertar ejemplo", size = ButtonSize.COMPACT, onClick = { text = MARKUP_SAMPLE }) },
        ) {
            LabeledField(
                label = "Texto",
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.testTag(MARKUP_INPUT_TAG),
                placeholder = "Escribe o pega el razonamiento…",
                singleLine = false,
                minLines = 8,
                maxWidth = androidx.compose.ui.unit.Dp.Unspecified,
                supportingText = "${text.length} / ${RazonadoMarkup.MAX_LENGTH} caracteres",
                isError = text.length > RazonadoMarkup.MAX_LENGTH,
            )
        }
        MarkupIssues(result)
        FormSection(title = "Vista previa", description = "Así lo verá quien abra el razonado.") {
            WorkspacePanel(modifier = Modifier.testTag(MARKUP_PREVIEW_TAG)) {
                MarkupPreview(result.document)
            }
        }
        if (result.document.isNotEmpty() && !result.hasErrors) {
            FormSection(title = "Forma que se guarda", description = "El texto normalizado: abrirlo de nuevo da exactamente la misma vista previa.") {
                Text(
                    text = RazonadoMarkup.normalize(result.document),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
internal fun MarkupIssues(result: RazonadoMarkup.Result) {
    if (result.issues.isEmpty()) return
    Column(modifier = Modifier.testTag(MARKUP_ISSUES_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (result.hasErrors) {
            WorkspaceNotice(WorkspaceNoticeTone.DANGER, "Hay errores: no se puede importar así", detail = "Corrígelos y vuelve a revisar. El texto se muestra como mejor se ha podido leer.")
        }
        result.issues.take(MAX_ISSUES_SHOWN).forEach { issue ->
            WorkspaceNotice(
                tone = if (issue.severity == RazonadoMarkup.Severity.ERROR) WorkspaceNoticeTone.DANGER else WorkspaceNoticeTone.WARNING,
                title = issue.message,
                detail = "Posición ${issue.position}",
            )
        }
        if (result.issues.size > MAX_ISSUES_SHOWN) {
            Text("… y ${result.issues.size - MAX_ISSUES_SHOWN} más.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        }
    }
}

private const val MAX_ISSUES_SHOWN = 10

/** Pinta los bloques con el estilo del diseño. Sin HTML: se construye un `AnnotatedString` desde el árbol tipado. */
@Composable
fun MarkupPreview(document: List<MarkupBlock>, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val typography = MaterialTheme.typography
    val palette: (MarkupColor) -> Color = { color ->
        when (color) {
            MarkupColor.RED -> tokens.danger
            MarkupColor.GREEN -> tokens.success
            MarkupColor.BLUE -> tokens.info
            MarkupColor.ORANGE -> tokens.warning
            MarkupColor.PURPLE -> tokens.accent
            MarkupColor.GRAY -> tokens.textSecondary
        }
    }
    val sizes: (MarkupSize) -> TextUnit = { size ->
        when (size) {
            MarkupSize.SMALL -> typography.bodySmall.fontSize
            MarkupSize.NORMAL -> typography.bodyMedium.fontSize
            MarkupSize.LARGE -> typography.titleMedium.fontSize
            MarkupSize.XLARGE -> typography.titleLarge.fontSize
        }
    }
    if (document.isEmpty()) {
        Text("Nada que mostrar todavía.", style = typography.bodyMedium, color = tokens.textSecondary, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        document.forEach { block ->
            Text(
                text = markupAnnotated(block, palette, sizes),
                style = typography.bodyMedium,
                color = tokens.textPrimary,
                textAlign = when (block.align) {
                    MarkupAlign.LEFT -> TextAlign.Start
                    MarkupAlign.CENTER -> TextAlign.Center
                    MarkupAlign.RIGHT -> TextAlign.End
                    MarkupAlign.JUSTIFY -> TextAlign.Justify
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Convierte un bloque en `AnnotatedString`. Pública para probar que cada nodo del árbol tiene su estilo. */
internal fun markupAnnotated(block: MarkupBlock, color: (MarkupColor) -> Color, size: (MarkupSize) -> TextUnit): AnnotatedString =
    buildAnnotatedString {
        if (block.bullet) append("•  ")
        block.inlines.forEach { appendInline(it, color, size) }
    }

private fun AnnotatedString.Builder.appendInline(node: MarkupInline, color: (MarkupColor) -> Color, size: (MarkupSize) -> TextUnit) {
    when (node) {
        is MarkupInline.Text -> append(node.text)
        is MarkupInline.Styled -> withStyle(
            when (node.style) {
                MarkupStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                MarkupStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                MarkupStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                MarkupStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            },
        ) { node.children.forEach { appendInline(it, color, size) } }
        is MarkupInline.Colored -> withStyle(SpanStyle(color = color(node.color))) { node.children.forEach { appendInline(it, color, size) } }
        is MarkupInline.Sized -> withStyle(SpanStyle(fontSize = size(node.size))) { node.children.forEach { appendInline(it, color, size) } }
    }
}
