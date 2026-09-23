package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

private val LocalNewsEditable = staticCompositionLocalOf { true }

@Composable
fun NewsContent(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    var width by remember { mutableStateOf(0) }
    var height by remember { mutableStateOf(0) }
    var compactLibrary by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<NewsIntent?>(null) }
    val density = LocalDensity.current
    val dispatch: (NewsIntent) -> Unit = { intent ->
        if (state.actionState !is UiAsync.Loading) {
            if (state.isDirty && (intent is NewsIntent.Select || intent == NewsIntent.CreateNew)) pending = intent
            else onIntent(intent)
        }
    }
    val panes = newsPaneSizes(width)
    val bodyHeight = (height - 156 - if (panes.library == 0) 52 else 0).coerceAtLeast(120)
    CompositionLocalProvider(LocalNewsEditable provides (state.actionState !is UiAsync.Loading)) {
    Column(Modifier.fillMaxSize().onSizeChanged { width = with(density) { it.width.toDp().value.toInt() }; height = with(density) { it.height.toDp().value.toInt() } }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Mesa de edición", style = MaterialTheme.typography.titleLarge)
                Text("Prepara el contenido y su composición visual.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
            }
            SecondaryButton("+ Nueva noticia", { dispatch(NewsIntent.CreateNew) }, enabled = state.actionState !is UiAsync.Loading)
        }
        if (width > 0) {
            if (panes.library == 0) {
                SecondaryButton(if (compactLibrary) "Volver al editor" else "Abrir biblioteca", { compactLibrary = !compactLibrary })
                if (compactLibrary) NewsLibraryPane(state, { dispatch(it); if (it is NewsIntent.Select) compactLibrary = false }, Modifier.fillMaxWidth().height(bodyHeight.dp))
                else NewsScrollPane(Modifier.fillMaxWidth().height(bodyHeight.dp)) {
                    NewsEditorPane(state, dispatch, width)
                    NewsPreviewPane(state, dispatch, width)
                    NewsPublicationPane(state, dispatch)
                }
            } else Row(Modifier.height(bodyHeight.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NewsLibraryPane(state, dispatch, Modifier.width(panes.library.dp).fillMaxHeight())
                // El editor es el protagonista: ocupa el ancho que sobra y es lo
                // único que crece cuando la ventana crece.
                NewsScrollPane(Modifier.width(panes.editor.dp).fillMaxHeight()) {
                    NewsEditorPane(state, dispatch, panes.editor)
                    // Cuando no hay columnas propias, su contenido cae aquí en vez
                    // de estrujarse: mejor desplazarse que no poder leer.
                    if (panes.inspector == 0) NewsLayerInspectorPane(state, dispatch)
                    if (panes.rail == 0) {
                        NewsPreviewPane(state, dispatch, panes.editor)
                        NewsPublicationPane(state, dispatch)
                    }
                }
                if (panes.inspector > 0) NewsScrollPane(Modifier.width(panes.inspector.dp).fillMaxHeight()) {
                    NewsLayerInspectorPane(state, dispatch)
                    if (panes.rail == 0) {
                        NewsPreviewPane(state, dispatch, panes.inspector)
                        NewsPublicationPane(state, dispatch)
                    }
                }
                if (panes.rail > 0) NewsScrollPane(Modifier.width(panes.rail.dp).fillMaxHeight()) {
                    NewsPublicationPane(state, dispatch)
                    NewsPreviewPane(state, dispatch, panes.rail)
                }
            }
            NewsSaveBar(state, dispatch)
        }
    }
    pending?.let { intent -> ConfirmDialog(title = "Cambiar de noticia", message = "Hay cambios sin guardar. Guarda el borrador local antes de salir si quieres conservarlo.", confirmLabel = "Continuar", onConfirm = { pending = null; onIntent(intent) }, onDismiss = { pending = null }) }
    }
}

@Composable
private fun NewsScrollPane(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun NewsPanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tint = GlassTheme.tokens.sectionGlass, strokeColor = GlassTheme.tokens.sectionGlassStroke) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable
internal fun NewsField(label: String, value: String, onChange: (String) -> Unit, lines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), readOnly = !LocalNewsEditable.current,
            singleLine = lines == 1, minLines = lines, shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GlassTheme.tokens.accentInk,
                unfocusedBorderColor = GlassTheme.tokens.fieldBorder, focusedTextColor = GlassTheme.tokens.textPrimary,
                unfocusedTextColor = GlassTheme.tokens.textPrimary, cursorColor = GlassTheme.tokens.accentInk))
    }
}
@Composable
internal fun NewsChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        Box {
            SecondaryButton("$value  ▾", { expanded = true }, Modifier.fillMaxWidth(), enabled = LocalNewsEditable.current && enabled && options.isNotEmpty())
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onChange(id) }) }
            }
        }
    }
}
