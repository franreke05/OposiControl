package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
internal fun NewsLibraryPane(state: NewsState, onIntent: (NewsIntent) -> Unit, modifier: Modifier) {
    NewsPanel("Biblioteca", modifier) {
        GlassSearchBar(state.searchQuery, { onIntent(NewsIntent.UpdateSearch(it)) }, "Buscar noticias…")
        NewsChoice("Estado", if (state.statusFilter == "all") "Todos" else newsStatusLabel(state.statusFilter),
            listOf("all" to "Todos", "draft" to "Borradores", "scheduled" to "Programadas", "published" to "Publicadas", "archived" to "Archivadas")) { onIntent(NewsIntent.UpdateStatusFilter(it)) }
        NewsChoice("Tipo de noticia", if (state.typeFilter == "all") "Todos" else catalogName(state.typeFilter, state.newsTypes),
            listOf("all" to "Todos") + ((state.newsTypes as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name })) { onIntent(NewsIntent.UpdateTypeFilter(it)) }
        NewsChoice("Especialidad", if (state.specialtyFilter == "all") "Todas" else catalogName(state.specialtyFilter, state.specialties),
            listOf("all" to "Todas") + ((state.specialties as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name })) { onIntent(NewsIntent.UpdateSpecialtyFilter(it)) }
        HorizontalDivider(color = GlassTheme.tokens.border)
        when (state.libraryState) {
            UiAsync.Loading, UiAsync.Idle -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UiAsync.Error -> {
                Text("No se pudo actualizar la biblioteca. Los borradores locales siguen disponibles.", style = MaterialTheme.typography.bodySmall)
                SecondaryButton("Reintentar", { onIntent(NewsIntent.Refresh) })
            }
            else -> Unit
        }
        val items = (state.news as? UiAsync.Success)?.value.orEmpty()
        if (state.libraryState is UiAsync.Success) Text("${items.size} en esta vista", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        if (items.isEmpty() && state.libraryState !is UiAsync.Loading) {
            Text("Todavía no hay noticias en esta vista", style = MaterialTheme.typography.titleSmall)
            Text("Crea una noticia o ajusta los filtros para encontrar contenido.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                GlassSurface(Modifier.fillMaxWidth().clickable { onIntent(NewsIntent.Select(item.id)) },
                    tint = if (state.selectedId == item.id) GlassTheme.tokens.surfaceSelected else GlassTheme.tokens.glassFill,
                    strokeColor = if (state.selectedId == item.id) GlassTheme.tokens.accentInk else GlassTheme.tokens.border) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        val image = item.backgroundPath?.takeIf { it.isNotBlank() } ?: item.imagePath?.takeIf { it.isNotBlank() }
                        if (image != null) NewsAssetImage(image, Modifier.fillMaxWidth().height(76.dp))
                        Text(item.title.ifBlank { "Nueva noticia" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.text.ifBlank { "Sin contenido todavía" }, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${catalogName(item.type, state.newsTypes)} · ${catalogName(item.specialty, state.specialties)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusChip(newsStatusLabel(item.status), tone = when (item.status) { "published" -> StatusTone.POSITIVE; "scheduled" -> StatusTone.NEUTRAL; "draft", "archived" -> StatusTone.ATTENTION; else -> StatusTone.UNKNOWN })
                        if (item.isLocalDraft) Text("Copia local", style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.textSecondary)
                        if (item.publishFrom.isNotBlank()) Text(newsDateLabel(item.publishFrom), style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.textSecondary)
                    }
                }
            }
        }
    }
}
