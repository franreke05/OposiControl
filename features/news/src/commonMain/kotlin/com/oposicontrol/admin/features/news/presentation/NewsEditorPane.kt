package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsEditorPane(state: NewsState, onIntent: (NewsIntent) -> Unit, paneWidth: Int = 0) {
    var tab by remember { mutableStateOf("Contenido") }
    NewsPanel(if (state.selectedId == null || state.selectedId.startsWith("local-")) "Nueva noticia" else "Editando noticia") {
        Text(state.saveLabel(), style = MaterialTheme.typography.labelMedium, color = if (state.isDirty) GlassTheme.tokens.accentInk else GlassTheme.tokens.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Contenido", "Clasificación", "Imagen y estilo", "Capas visuales").forEach { name ->
                Column {
                    DesktopButton(name, { tab = name }, variant = ButtonVariant.GHOST, size = ButtonSize.COMPACT)
                    if (tab == name) Box(Modifier.width(52.dp).height(3.dp).background(GlassTheme.tokens.accentInk))
                }
            }
        }
        HorizontalDivider(color = GlassTheme.tokens.border)
        // El lienzo vive AQUÍ, y no dentro de una pestaña.
        //
        // Antes solo aparecía en «Capas visuales»: escribir el título o elegir
        // la especialidad se hacía a ciegas, con la composición fuera de la
        // vista, y la única referencia era la miniatura del carril derecho. Lo
        // que se está montando es una imagen, así que la imagen manda y las
        // pestañas solo cambian el formulario que la acompaña.
        // El lienzo usa el ancho real de la columna, no un número fijo: estaba
        // clavado a 640 px y dejaba franjas muertas a los lados en cuanto la
        // ventana crecía. Se descuentan los 36 px de padding del panel.
        NewsCanvasFrame(
            state = state,
            onIntent = onIntent,
            canvasWidth = (paneWidth - 72).coerceIn(280, 900),
            toolbar = true,
        )
        when (tab) {
            "Contenido" -> {
                NewsField("Título de la noticia", state.editorTitle, { onIntent(NewsIntent.UpdateTitle(it)) })
                NewsField("Contenido", state.editorText, { onIntent(NewsIntent.UpdateText(it)) }, lines = 13)
                Text("${state.editorText.length} caracteres · Texto plano", style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.textSecondary)
                Text("Escribe con claridad. El contenido y las capas del lienzo se editan por separado.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
            }
            "Clasificación" -> {
                NewsCatalogSelector("Especialidad", state.editorSpecialty, state.specialties) { onIntent(NewsIntent.UpdateSpecialty(it)) }
                NewsCatalogSelector("Tipo de noticia", state.editorType, state.newsTypes) { onIntent(NewsIntent.UpdateType(it)) }
                if (state.specialties is UiAsync.Error || state.newsTypes is UiAsync.Error) SecondaryButton("Reintentar catálogos", { onIntent(NewsIntent.Refresh) })
                Text("Selecciona los catálogos que identifican esta noticia. Los valores inactivos se conservan al abrir contenido existente.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
            }
            "Imagen y estilo" -> {
                val picker = rememberNewsImagePicker { uri -> onIntent(NewsIntent.SelectBackgroundFile(uri, newsAssetName(uri), null)) }
                NewsChoice("Estilo de fondo", when (state.editorBackgroundType.uppercase()) { "A", "SKY" -> "Cielo"; "B", "MINT" -> "Menta"; "C", "GOLD" -> "Dorado"; else -> "Sin identificar" }, listOf("SKY" to "Cielo", "MINT" to "Menta", "GOLD" to "Dorado")) { onIntent(NewsIntent.UpdateBackgroundType(it)) }
                if (state.editorBackgroundPath.isNotBlank()) {
                    NewsAssetImage(state.editorBackgroundPath, Modifier.fillMaxWidth().height(220.dp))
                    Text(newsAssetName(state.editorBackgroundPath), style = MaterialTheme.typography.bodySmall)
                } else Text("Sin imagen de fondo. Puedes componer la noticia con texto y capas.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (state.editorBackgroundPath.isBlank()) "Elegir imagen" else "Cambiar imagen", picker)
                    if (state.editorBackgroundPath.isNotBlank()) GhostButton("Quitar imagen", { onIntent(NewsIntent.UpdateBackgroundPath("")) })
                }
                Text("La imagen local se sube al publicar o programar. Hasta entonces permanece en este equipo.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
                if (state.editorImagePath.isNotBlank()) {
                    Text("Imagen principal", style = MaterialTheme.typography.titleSmall)
                    NewsAssetImage(state.editorImagePath, Modifier.fillMaxWidth().height(140.dp))
                    GhostButton("Quitar imagen principal", { onIntent(NewsIntent.UpdateImagePath("")) })
                }
            }
            else -> {
                // `UpdateCanvasWidth`/`UpdateCanvasHeight` existían y el
                // ViewModel las recortaba con `withClampedLayers()`, pero
                // ninguna pantalla las ofrecía: el lienzo estaba clavado a
                // 800×500 sin forma de cambiarlo.
                Text("Tamaño del lienzo", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(132.dp)) {
                        NewsField("Ancho (px)", state.editorCanvasWidth.toString(), { onIntent(NewsIntent.UpdateCanvasWidth(it)) })
                    }
                    Column(Modifier.width(132.dp)) {
                        NewsField("Alto (px)", state.editorCanvasHeight.toString(), { onIntent(NewsIntent.UpdateCanvasHeight(it)) })
                    }
                }
                Text(
                    "Reducir el lienzo recorta las capas que quedasen fuera; no se pierden, se reposicionan dentro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.tokens.textSecondary,
                )
            }
        }
        // Las capas también son permanentes: son el índice de lo que hay en el
        // lienzo, y esconderlas tras una pestaña obliga a cambiar de contexto
        // para saber qué se está mirando.
        HorizontalDivider(color = GlassTheme.tokens.border)
        NewsLayersEditor(state, onIntent)
    }
}

@Composable
private fun NewsCatalogSelector(label: String, value: String, catalog: UiAsync<List<NewsCatalogOption>>, onChange: (String) -> Unit) {
    NewsChoice(label, catalogName(value, catalog), (catalog as? UiAsync.Success)?.value.orEmpty().filter { it.active || it.id == value }.map { it.id to if (it.active) it.name else "${it.name} (inactiva)" }, catalog is UiAsync.Success, onChange)
}
