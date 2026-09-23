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
internal fun NewsEditorPane(state: NewsState, onIntent: (NewsIntent) -> Unit) {
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
            else -> NewsLayersEditor(state, onIntent)
        }
    }
}

@Composable
private fun NewsCatalogSelector(label: String, value: String, catalog: UiAsync<List<NewsCatalogOption>>, onChange: (String) -> Unit) {
    NewsChoice(label, catalogName(value, catalog), (catalog as? UiAsync.Success)?.value.orEmpty().filter { it.active || it.id == value }.map { it.id to if (it.active) it.name else "${it.name} (inactiva)" }, catalog is UiAsync.Success, onChange)
}
